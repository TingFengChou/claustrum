//! L1 caption (scene description) seam.
//!
//! The perception pipeline wakes L1 only on *admitted* frames (the L0 change-gate
//! decides — see [`crate::gate`]). L1 turns a frame into a short scene description.
//!
//! The real backend is an on-device VLM via **llama.cpp** (Rust FFI, see
//! ADR-0008): a GGUF model + multimodal projector (mmproj), invoked once per
//! admitted frame. That backend needs the native llama.cpp build (cmake + NDK)
//! and the model files on device, so it lands in its own focused change.
//!
//! Until then, [`PlaceholderCaptioner`] keeps the L0→L1 pipeline real and
//! testable end-to-end: it computes an **honest diagnostic** from the luma frame
//! (dimensions, mean brightness, a 2×2 brightness grid) and clearly labels itself
//! as "not the VLM". It deliberately does **not** fabricate a scene description —
//! it only proves the frame arrived intact with the right shape.

/// Turns a single-channel luma frame into a short description string.
///
/// `&mut self` so a real backend can hold model/context state across calls
/// (the placeholder is stateless).
pub trait Captioner {
    fn describe(&mut self, luma: &[u8], width: usize, height: usize) -> String;

    /// Human-readable backend name (shown in the UI so it's obvious which L1 is live).
    fn backend(&self) -> &'static str;
}

/// Diagnostic placeholder backend — no model, no VLM. Proves the pipeline.
#[derive(Default)]
pub struct PlaceholderCaptioner;

impl Captioner for PlaceholderCaptioner {
    fn describe(&mut self, luma: &[u8], width: usize, height: usize) -> String {
        if width == 0 || height == 0 || luma.len() < width * height {
            return "L1 佔位:無效幀".to_string();
        }
        let stats = LumaStats::of(luma, width, height);
        format!(
            "L1 佔位(未載入 VLM)· {w}×{h} · 亮度 {mean}% · 網格[{tl} {tr} / {bl} {br}]",
            w = width,
            h = height,
            mean = stats.mean_pct(),
            tl = stats.quad_pct(0),
            tr = stats.quad_pct(1),
            bl = stats.quad_pct(2),
            br = stats.quad_pct(3),
        )
    }

    fn backend(&self) -> &'static str {
        "placeholder"
    }
}

/// Mean brightness overall and per 2×2 quadrant (0=TL, 1=TR, 2=BL, 3=BR).
struct LumaStats {
    mean: u32,
    quads: [u32; 4],
}

impl LumaStats {
    fn of(luma: &[u8], width: usize, height: usize) -> Self {
        let mut sum: u64 = 0;
        let mut qsum = [0u64; 4];
        let mut qcount = [0u64; 4];
        let half_w = width / 2;
        let half_h = height / 2;
        for y in 0..height {
            let row = y * width;
            let vbit = if y >= half_h { 2 } else { 0 };
            for x in 0..width {
                let v = luma[row + x] as u64;
                sum += v;
                let q = vbit + if x >= half_w { 1 } else { 0 };
                qsum[q] += v;
                qcount[q] += 1;
            }
        }
        let total = (width * height) as u64;
        let mean = (sum / total.max(1)) as u32;
        let mut quads = [0u32; 4];
        for i in 0..4 {
            quads[i] = (qsum[i] / qcount[i].max(1)) as u32;
        }
        LumaStats { mean, quads }
    }

    fn mean_pct(&self) -> u32 {
        self.mean * 100 / 255
    }

    fn quad_pct(&self, i: usize) -> u32 {
        self.quads[i] * 100 / 255
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_frame_is_safe() {
        let mut c = PlaceholderCaptioner;
        assert!(c.describe(&[], 0, 0).contains("無效幀"));
        assert!(c.describe(&[1, 2, 3], 64, 64).contains("無效幀")); // luma too short
    }

    #[test]
    fn reports_dimensions_and_backend() {
        let mut c = PlaceholderCaptioner;
        let out = c.describe(&vec![128u8; 32 * 24], 32, 24);
        assert!(out.contains("32×24"), "got: {out}");
        assert_eq!(c.backend(), "placeholder");
    }

    #[test]
    fn dark_frame_reads_low_bright_frame_reads_high() {
        let mut c = PlaceholderCaptioner;
        let dark = c.describe(&vec![0u8; 16 * 16], 16, 16);
        let bright = c.describe(&vec![255u8; 16 * 16], 16, 16);
        assert!(dark.contains("亮度 0%"), "got: {dark}");
        assert!(bright.contains("亮度 100%"), "got: {bright}");
    }

    #[test]
    fn quadrant_grid_localizes_brightness() {
        // Bright bottom half, dark top half → TL/TR low, BL/BR high.
        let w = 16;
        let h = 16;
        let mut luma = vec![0u8; w * h];
        for y in h / 2..h {
            for x in 0..w {
                luma[y * w + x] = 255;
            }
        }
        let mut c = PlaceholderCaptioner;
        let out = c.describe(&luma, w, h);
        assert!(out.contains("網格[0 0 / 100 100]"), "got: {out}");
    }
}
