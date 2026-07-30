# ADR-0004 — Phone-first, single-node bring-up

**Status:** accepted · **Date:** 2026-07-30
**Supersedes:** [ADR-0001](0001-platform-choice.md) (platform and model choice)
**Defers:** [ADR-0003](0003-two-node-topology.md) (two-node topology)

## Context

ADR-0001 chose the Jetson AGX Orin and Gemma 4 12B, on the strength of 32 GB of
unified memory and a Linux edit-and-run loop. That reasoning still holds — for an
AGX Orin. But the AGX Orin is not in hand, and the hardware that will actually
arrive is a **Jetson Nano**, not an AGX Orin. Development cannot wait on it, and
even when it lands it will not run a 12B model.

The available hardware today is the Pixel 10.

## Decision

**Develop phone-first on a single node.** The Pixel 10 does everything —
camera capture, L0–L4, and the query surface — in one process space. The model
is a **bundled Gemma E2B / E4B** served on-device through LiteRT-LM; M0
benchmarks it over an adb-forwarded OpenAI-compatible endpoint, reusing the
existing harness.

The Jetson is deferred to whenever it arrives, at which point ADR-0003's
two-node split is revisited — as a *second* node behind the phone, not as the
primary compute.

## Rationale

**Hardware reality overrides the iteration-speed argument.** ADR-0001 rejected
Android because of the Gradle → APK → adb loop. That cost is real, but it is
paid against a device that exists; the AGX Orin's edit-and-run loop is worth
nothing while the AGX Orin is hypothetical. A slower loop on real hardware beats
a fast loop on none.

**The 12B anti-hallucination argument does not survive the Nano.** ADR-0001's
load-bearing claim was that moving 2B → 12B is the single most effective defence
against VLM hallucination. Neither a phone nor a Jetson Nano can hold a 12B
model, so that lever is gone on *both* the current and the eventual hardware.
The consequence is not small: **prompt design and eval discipline become the
primary hallucination defence, not model capacity.** The four structural
defences in ARCHITECTURE.md (single-instant framing, `unclear` as a valid
answer, `risk` requires visible evidence, closed risk enumeration) move from
"second line" to "first line", and the M1 hallucination gate matters more, not
less.

**Single node is the fastest path to a first result, and the pivot is honest
about what it costs.** Collapsing to one device means ADR-0003's *structural*
frame-isolation boundary cannot exist yet: when one process both holds frames
and answers queries, "frames cannot leave" downgrades from a guarantee to a
policy. This is a genuine regression in the privacy model and is recorded as
such — it is a deliberate, temporary trade for development speed, to be undone
when a second node exists. See Consequences.

**Bundled Gemma over Gemini Nano (AICore).** Bundling a `.litertlm` keeps full
control of the model, quantisation and prompt, which the eval discipline
depends on, and keeps the artifact portable to the Jetson later. AICore's
system-managed model is attractive operationally but constrains us to what it
exposes and to variable on-device multimodal support — the wrong constraints for
a project whose core risk is caption quality.

## Consequences

- **Frame isolation is policy, not structure, during the phone era.** The README
  and PRIVACY.md must say so plainly rather than implying the ADR-0003 guarantee
  is in force. The "we cannot return frames" claim returns only with a second
  node.
- **M0 changes shape.** Backends become on-device LiteRT-LM (and optionally a
  MediaPipe LLM Inference shim), not Jetson's llama.cpp / TensorRT-LLM. Thermal
  and power sampling moves from `tegrastats` to an Android sampler
  (`bench/phone_monitor.py`). The HTTP harness itself is unchanged — it talks to
  `127.0.0.1:<port>` over `adb forward`.
- **Model size is now a hard ceiling on both platforms.** Plan for E2B/E4B
  quality for the foreseeable future; do not design anything that only works if a
  12B model appears.
- **Thermal and battery, not fan noise, are the sustainability limits.** A phone
  running continuous inference will throttle and drain. The 7-day continuous-run
  target (M6) is harder on a phone than it would have been on an AGX Orin; it may
  need a mains-powered, actively-cooled phone rig, or it waits for the Jetson.
- **AppFunctions (M4) gets simpler, not harder.** With a single node the store
  and the provider are co-resident; there is no LAN pairing to build yet. The
  mTLS gRPC work from ADR-0003 is deferred with the topology.
- **The domain model, schema, pyramid and eval harness are untouched.** The
  pivot is a platform and topology decision; nothing above L0 changes.

## Revisit criteria

Reopen this decision when a Jetson (any model) is physically available. At that
point: re-run M0 on it, decide whether it becomes the sensor node with the phone
demoted to query surface (restoring ADR-0003), and record the outcome as a new
ADR that supersedes this one.
