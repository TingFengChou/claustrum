//! L0 change gate — the compute saver.
//!
//! The expensive step is L1 (the VLM). Running it every frame on a mostly-static
//! scene wastes power. This gate cheaply decides whether the scene changed enough
//! to be worth an L1 call, using an 8×8 average-hash (aHash) of the frame's luma
//! and the Hamming distance between consecutive hashes. Robust to sensor noise and
//! small lighting jitter, and costs microseconds.
//!
//! Pure logic: fed single-channel luma bytes, unit-testable on the host.

/// A compact perceptual signature of a frame (64-bit average hash).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Signature(pub u64);

/// Compute an 8×8 average-hash from single-channel `luma` of a `width`×`height`
/// frame. Block-averages into 8×8 cells, then sets one bit per cell that is at or
/// above the overall mean.
pub fn frame_signature(luma: &[u8], width: usize, height: usize) -> Signature {
    if width == 0 || height == 0 || luma.len() < width * height {
        return Signature(0);
    }
    let mut cells = [0u32; 64];
    for by in 0..8 {
        let y0 = by * height / 8;
        let y1 = (((by + 1) * height / 8).max(y0 + 1)).min(height);
        for bx in 0..8 {
            let x0 = bx * width / 8;
            let x1 = (((bx + 1) * width / 8).max(x0 + 1)).min(width);
            let mut sum = 0u64;
            let mut n = 0u64;
            for y in y0..y1 {
                for x in x0..x1 {
                    sum += luma[y * width + x] as u64;
                    n += 1;
                }
            }
            cells[by * 8 + bx] = sum.checked_div(n).unwrap_or(0) as u32;
        }
    }
    let mean = (cells.iter().map(|&c| c as u64).sum::<u64>() / 64) as u32;
    let mut bits = 0u64;
    for (i, &c) in cells.iter().enumerate() {
        if c >= mean {
            bits |= 1u64 << i;
        }
    }
    Signature(bits)
}

/// Hamming distance between two signatures (0..=64).
pub fn distance(a: Signature, b: Signature) -> u32 {
    (a.0 ^ b.0).count_ones()
}

/// Stateful L0 gate: holds the last admitted signature and a change threshold
/// (minimum Hamming distance, in bits, to count as "changed").
pub struct ChangeGate {
    threshold: u32,
    prev: Option<Signature>,
}

impl ChangeGate {
    /// `threshold`: minimum aHash Hamming distance (0..=64) to treat as a change.
    /// ~6–10 is a sensible starting band; tune against real footage.
    pub fn new(threshold: u32) -> Self {
        Self {
            threshold,
            prev: None,
        }
    }

    /// Returns true if this frame should be passed to L1 (scene changed enough,
    /// or it is the first frame). Updates internal state only when admitted, so a
    /// slow drift accumulates against the last *processed* frame rather than the
    /// immediately previous one.
    pub fn admit(&mut self, luma: &[u8], width: usize, height: usize) -> bool {
        let sig = frame_signature(luma, width, height);
        let admit = match self.prev {
            None => true,
            Some(p) => distance(p, sig) >= self.threshold,
        };
        if admit {
            self.prev = Some(sig);
        }
        admit
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // 16×16 synthetic luma helpers.
    const W: usize = 16;
    const H: usize = 16;

    fn solid(v: u8) -> Vec<u8> {
        vec![v; W * H]
    }

    /// Left half dark, right half bright — a strong, structured pattern.
    fn split() -> Vec<u8> {
        let mut f = vec![0u8; W * H];
        for y in 0..H {
            for x in 0..W {
                f[y * W + x] = if x < W / 2 { 20 } else { 220 };
            }
        }
        f
    }

    /// `split` with light per-pixel noise — same scene, sensor jitter.
    fn split_noisy() -> Vec<u8> {
        let mut f = split();
        for (i, p) in f.iter_mut().enumerate() {
            let d = ((i % 5) as i16) - 2; // -2..=2
            *p = (*p as i16 + d).clamp(0, 255) as u8;
        }
        f
    }

    #[test]
    fn identical_frames_have_zero_distance() {
        let a = frame_signature(&split(), W, H);
        let b = frame_signature(&split(), W, H);
        assert_eq!(distance(a, b), 0);
    }

    #[test]
    fn different_scenes_are_far_apart() {
        let a = frame_signature(&split(), W, H);
        // vertical split (top dark / bottom bright) — very different structure.
        let mut vf = vec![0u8; W * H];
        for y in 0..H {
            for x in 0..W {
                vf[y * W + x] = if y < H / 2 { 20 } else { 220 };
            }
        }
        let b = frame_signature(&vf, W, H);
        assert!(
            distance(a, b) >= 10,
            "expected large distance, got {}",
            distance(a, b)
        );
    }

    #[test]
    fn first_frame_is_always_admitted() {
        let mut g = ChangeGate::new(6);
        assert!(g.admit(&solid(128), W, H));
    }

    #[test]
    fn static_scene_is_gated_out() {
        let mut g = ChangeGate::new(6);
        assert!(g.admit(&split(), W, H)); // first: admitted
        assert!(!g.admit(&split(), W, H)); // identical: skipped
        assert!(!g.admit(&split_noisy(), W, H)); // noise only: skipped
    }

    #[test]
    fn real_change_is_admitted() {
        let mut g = ChangeGate::new(6);
        assert!(g.admit(&solid(10), W, H)); // first
        assert!(g.admit(&split(), W, H)); // structure appeared: admitted
    }

    #[test]
    fn malformed_input_is_safe() {
        // luma shorter than w*h must not panic.
        assert_eq!(frame_signature(&[1, 2, 3], W, H), Signature(0));
    }
}
