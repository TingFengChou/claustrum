# Roadmap

Estimates assume part-time work. The sequencing matters more than the numbers.

---

## M0 — Backend spike · 1–2 weeks

Establish the numbers everything else depends on.

**Work**
- Stand up all three inference backends on the AGX Orin: `litert-lm serve`, `llama-server` (CUDA), `trtllm-serve`
- Build the 20–40 frame fixture set (see `bench/README.md`) — including the ambiguous frames where `unclear` is correct
- Run `bench/run_bench.py` across backends and grid configurations
- Score retained samples by hand for caption usefulness and hallucination
- Evaluate 30 W power mode: latency penalty, fan noise, temperature drift

**Exit criteria**
- Three-way comparison table exists in `eval/reports/`
- A backend is chosen and recorded as an ADR
- **Keyframe budget decided** — calls per second the pipeline can sustain
- Grid question answered: does a 2×2 composite cost less than 2× a single frame?

**Why first:** if p95 latency is 3 s the architecture looks nothing like it does at 12 s. At 12 s, realtime alerting needs a two-model tier — a small model for immediate captions, 12B for periodic depth. That is a structural difference, not a tuning one.

---

## M1 — Structured caption · 2–3 weeks

**Work**
- Freeze `schemas/kineme.schema.json`; wire `core/domain.py` validation both ways in CI
- Iterate `prompts/caption_v1.md` against 100 hand-labelled frames
- JSON tolerance layer (fences, preamble, trailing commentary)
- Stand up `eval/harness` skeleton

**Exit criteria**
- Caption acceptability > 70 %
- JSON parse rate > 98 %
- **Hallucination rate < 10 %** (tightens to < 5 % by M6)
- Harness runs from one command and writes a report

**Freeze the schema here.** Every module downstream depends on it; the cost of changing it compounds weekly.

---

## M2 — Offline pipeline · 3–4 weeks

**Work**
- L0 gating: frame differencing, object detection, pose landmarks, frame embedding similarity
- L1 batch runner over a video file
- `KinemeStore` — SQLite on NVMe, retention policy, frame encryption

**Exit criteria**
- Feed in a one-hour video, get a kineme stream out
- Compression > 100×
- A human reading the kineme stream can tell what happened in that video

That last criterion is subjective and non-negotiable. If the stream is not legible to a person, no amount of downstream summarisation will rescue it.

---

## M3 — Ethogram and query · 3 weeks

**Work**
- L3 hierarchical summarisation: kinemes → 15 min → hour → daily `Ethogram`
- Anomaly detection by comparison against the subject's own trailing fortnight
- L4 embedding index and natural-language retrieval
- Minimal review UI

**Exit criteria**
- "What happened at home today?" produces a useful timeline
- "Did anyone go near the medicine box yesterday afternoon?" answers correctly with timestamps
- Ethogram usefulness > 3.0 (manual, 1–5)

---

## M4 — AppFunctions on Pixel 10 · 3–4 weeks

**Depends only on M3.** Deliberately sequenced before the realtime pipeline.

**Work**
- Extract `core/tools/` contract layer — one definition, two transports
- LAN pairing over mTLS with QR-code exchange; gRPC transport
- AppFunctions provider: `getHomeStatus`, `queryKinemes`, `getEthogram`
- **Tiered consent UI, caller allowlist, user-visible audit log** — all three ship with the feature, not after it
- Run the official AppFunctions agent skill first to generate boilerplate and refine KDoc

**Exit criteria**
- Ask the phone assistant about the house, get a correct answer sourced from the on-device store
- `adb shell cmd app_function list-app-functions` shows correct metadata
- Frames provably unreachable from the query surface — no code path exists
- Audit screen shows who queried what and when

**Why here:** it validates whether kineme quality is sufficient to support natural-language querying *before* the expensive M5–M6 work. If captions cannot sustain a conversation, that surfaces now rather than in month six. It is also the most demonstrable milestone in the plan.

---

## M5 — Realtime pipeline and alerting · 4–5 weeks

**Work**
- RTSP / CSI ingest, DeepStream multi-stream decode
- L0 as a resident systemd daemon
- L2 dual path: pose heuristic for recall, VLM confirmation for precision
- Alert suppression: deduplication, per-category rate limiting, post-rejection cooldown
- Push notification delivery to the Pixel

**Exit criteria**
- Staged fall produces a notification within p95 < 5 s
- Fall recall > 90 %
- False alerts < 3 per 24 h
- The three-frame confirmation demonstrably rejects deliberate lying down

---

## M6 — Hardening · 4 weeks

**Work**
- Seven-day continuous run
- Thermal-aware gating throttle; power mode tuning
- Drive false alerts below 1 per 24 h against the 72-hour uneventful corpus
- OpenTelemetry export: latency, calls per hour, temperature, false-alert count
- Retention and key rotation verified end to end

**Exit criteria**
- Seven days, no crash, no thermal throttle
- **False alerts < 1 per 24 h**
- Hallucination rate < 5 %
- Dashboard shows all primary metrics

---

## M7 — Robot bridge · 3 weeks

**Work**
- Network MCP server over the same `core/tools/` contract
- ROS 2 node publishing `/kinemes`
- Spatial anchoring proof of concept — odometry and map coordinates attached to kinemes

**Exit criteria**
- An external agent can query site memory over MCP
- A ROS 2 subscriber receives kinemes
- Anchored kinemes render on a map

At which point the event log is a semantic map, and this stops being camera subtitles.

---

## Second-modality gate

The `claustrum` umbrella exists for ASR, TTS and LLM orchestration. Do not open those modules until `ethogram` clears M6. Two reasons:

1. A second modality doubles the eval surface. Doing that before the first one is trustworthy means never knowing which modality is at fault.
2. The shape of the fusion layer will be much clearer once one modality has been through six milestones of contact with reality.

When the gate opens, the first question is not "which ASR model" but "what does a transcript kineme look like, and does it share `ts_start` semantics with a visual one".
