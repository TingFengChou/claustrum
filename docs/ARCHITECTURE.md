# Architecture

## The one design decision that matters

On-device VLMs consume **images**, not video streams. Feeding 30 fps to a VLM is not a tuning problem; it is arithmetic that never closes. Every other decision in this project follows from how aggressively the temporal axis is compressed before inference.

## Layers

### L0 — Gating

Cheap, always-on, millisecond-scale. Decides which instants deserve an expensive call.

Signals:
- Frame differencing — is anything moving at all
- Object detection — person / animal / relevant object present
- Pose landmarks — skeletal keypoints, feeding both gating and L2's fast path
- Frame embedding — cosine similarity against the last captioned frame

Policy:

```
if no motion and last kineme < 15 min ago:        skip, record "quiet"
elif no motion and last VLM call > 15 min ago:    heartbeat call (confirm scene state)
elif motion and scene similarity > 0.9:           skip (same ongoing action)
elif pose matches hazard pattern:                 → L2 fast path, highest priority
else:                                             → L1 caption
```

The similarity check exists because a person sitting still watching television for twenty minutes must not produce twenty identical kinemes. Without it, L3 summaries drown in restatements of nothing.

Target: 0.05–0.5 keyframes per second — 60× to 600× compression.

### L1 — Caption

Gemma E2B / E4B on the phone (LiteRT-LM), resident in memory, one call per selected keyframe (or per 2×4 temporal grid — see the M0 spike). The 12B model assumed in ADR-0001 does not fit a phone or a Jetson Nano; see [ADR-0004](adr/0004-phone-first-single-node.md).

Input: keyframe(s) + a one-line summary of the previous kineme, for continuity.
Output: a structured `Kineme` conforming to [`schemas/kineme.schema.json`](../schemas/kineme.schema.json).

The model reports `confidence` but **not** `novelty`. Novelty is a property of a
kineme relative to its neighbours, which a model shown a single instant cannot
compute; the pipeline fills it from the L0 frame-embedding distance. Anything
requiring comparison across kinemes is the pipeline's job, not the model's.

Model choice is a hallucination-control decision, not a performance one. See ADR-0001.

### L2 — Alerting

**Split into two paths, deliberately.**

A VLM call takes seconds. As the sole judge of a fall, that latency is unacceptable. A pose heuristic responds in milliseconds but fires on sitting down, bending to pick something up, and lying on a sofa — its false positive rate makes it unusable alone.

So:

```
L0 pose heuristic detects candidate
    │
    ├──▶ immediately enter PENDING state, start buffering frames
    │
    └──▶ VLM examines 3 frames (before / during / after):
         "Is this person falling, or lying down / sitting deliberately?
          Did they get up afterwards?"
              │
              ├── confirmed  → dispatch alert   (total latency ~3–5 s)
              └── rejected   → record silently, do not notify the user
```

**The heuristic owns recall. The VLM owns precision.** This is the project's principal technical contribution and the main thing distinguishing it from off-the-shelf AI cameras.

Suppression rules on top: deduplicate within a window, rate-limit per category, and require a cooldown after a rejected candidate in the same location.

### L3 — Summarize

Text-only LLM, run in batches during idle periods. Hierarchical: kinemes → 15-minute windows → hours → a daily `Ethogram`.

Kinemes are weighted for inclusion by `novelty` and `confidence`. Anomalies are detected by comparison against the subject's **own** history over the preceding fortnight, not against absolute rules. This is a cheap way to move the system from "rule-based alarm" to "observer with memory".

### L4 — Query

Kinemes and their embeddings in SQLite on NVMe. Natural-language retrieval over the event log, returning text and timestamps.

## Domain types

Defined once in [`core/domain.py`](../core/domain.py), mirrored by JSON Schema in [`schemas/`](../schemas/).

```
Actant    a participant — role slot, never an identity
Kineme    one observed behaviour, one time span
Ethogram  a catalogue of kinemes over a period
```

Schema and dataclass drifting apart is the most common invisible bug in this kind of pipeline. CI validates both directions.

## Anti-hallucination

A small model shown a single static frame will invent causal narrative — "he fell and then got up to fetch his medicine", from one photograph. For a safety alerting system this is not a quality issue, it is a correctness failure.

Model capacity used to be the first-line defence — ADR-0001 leaned on 12B for
exactly this. On a phone (and on the eventual Jetson Nano) that lever is gone: an
E2B/E4B model confabulates *more* than a 12B one, not less. So the structural and
prompt-level defences carry the load, and they are now the first line, not the
second — see [ADR-0004](adr/0004-phone-first-single-node.md).

Defences, in order of effectiveness on a small model:

1. **Explicit single-instant framing.** The prompt states that the model is seeing one moment and must not infer off-frame events.
2. **`unclear` is a valid answer.** Give the model an exit that is not fabrication.
3. **`risk` requires evidence of occurrence.** "Could be dangerous" does not qualify. Without this, a knife resting on a counter is reported as a child hazard.
4. **Closed risk enumeration.** A fixed `RiskCategory` set stops the model inventing categories the L2 rules will silently never match.

Because the model is small, the M1 hallucination gate is more load-bearing, not
less. Treat a regression there as a release blocker.

Hallucination rate is a tracked regression metric, not an aspiration. See [`eval/`](../eval/).

## Cloud escalation

Default: fully offline. One optional path exists:

```
L1 confidence < 0.5 and risk != none
  or user explicitly asks for a closer look
      │
      ▼ per-instance explicit user consent
      └──▶ Gemini Robotics-ER 1.6 / Gemini 3 Flash
           precise spatial reasoning, pointing, multi-view success detection
```

Constraints: user-visible, disabled by default, single de-identified frame only, never video.

## Robot extension

The home deployment is the first vertical. The durable assets are L1 perception and L4 semantic memory.

| Home | Robot |
|---|---|
| L0 gating | Perception resource scheduling |
| L1 Kineme | Semantic annotation stream of the environment |
| L2 alerting | Safety supervision layer |
| L3 Ethogram | Long-term site memory — what this space normally looks like |
| L4 query | "Where did I last see the cart?" |

Integration order: MCP server first (lowest cost, highest reuse — any agent can query), then ROS 2 node, then spatial anchoring. Attaching odometry and map coordinates to kinemes turns the event log into a semantic map, which is the point at which this becomes site understanding rather than camera subtitles.

## Tool contract layer

AppFunctions and the network MCP server are two transports over one contract:

```
core/tools/           single definition and implementation
  ├─ contract.py
  └─ impl.py
       ▲                    ▲
       │                    │
bridge/appfunctions/   bridge/mcp/
(Android, on-device)   (network — robots, desktop agents)
```

Robots generally run Linux rather than Android, so they use the network MCP path. AppFunctions earns its place through the household user experience — asking a phone assistant about the house directly.
