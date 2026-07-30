# claustrum

**On-device perception and cognition for embodied AI.** Binds separate sensory modalities — vision, audio, language — into a unified, queryable stream of observed events. Runs entirely on edge hardware.

Pixel 10 · Gemma E2B / E4B · LiteRT-LM · Android AppFunctions

> **Current phase: phone-first, single-node.** Everything runs on one Pixel 10
> while the Jetson is unavailable. This changes the platform and the privacy
> topology — see [ADR-0004](docs/adr/0004-phone-first-single-node.md). The Jetson
> two-node design ([ADR-0003](docs/adr/0003-two-node-topology.md)) is the target
> to restore later, not the current build.

---

## ⚠️ Read this first

**This is not a medical device and not a substitute for human care.**

Fall detection and hazard alerting in this project will miss events and will produce false positives. Do not rely on it as anyone's only safety net. If you deploy it in a household, tell everyone who lives there, get their informed consent, and give them a working off switch.

See [`docs/PRIVACY.md`](docs/PRIVACY.md) before pointing a camera at anyone.

---

## What it does

Continuous camera and microphone streams are cheap to capture and impossible to review. `claustrum` compresses them into something a human — or a robot — can actually use:

| Ask | Get |
|---|---|
| "What happened at home today?" | A timeline of discrete, timestamped events |
| "Did anyone go near the medicine box yesterday afternoon?" | A natural-language answer grounded in recorded events |
| Someone falls in the hallway | A push notification within ~5 seconds, with the reason |
| *(robot)* "Where did I last see the cart?" | A query against semantic spatial memory |

Everything runs on-device. On the phone-first single node, frames stay on the phone by policy; the *structural* "frames cannot leave" guarantee returns with the two-node Jetson topology (ADR-0003).

## Why the name

The **claustrum** is a thin sheet of neurons connected to nearly every cortical region. Crick and Koch proposed it as the structure that binds separate sensory modalities into a single unified experience — they compared it to the conductor of an orchestra.

That is the job of this project: take ASR, VLM, and LLM outputs and bind them into one coherent, temporally ordered understanding.

## Domain vocabulary

The codebase uses a deliberate, consistent vocabulary drawn from ethology, kinesics, and actor-network theory. All three traditions share a methodological commitment: **record what was observed; do not assume motive.** That commitment is this project's core discipline, so the names carry it.

| Term | Meaning here | Origin |
|---|---|---|
| **Actant** | A participant in a scene — `person_1`, `cat`, `robot_1`. A **role slot, not an identity.** | Actor-network theory (Latour); structural semiotics (Greimas) |
| **Kineme** | The smallest recorded unit of observed behaviour. One action, one time span. | Kinesics (Birdwhistell) — the gestural analogue of a phoneme |
| **Ethogram** | A catalogue of kinemes over a period. The system's primary output. | Ethology — a formal inventory of a species' discrete behaviours |

`Actant` being a role slot rather than a person is not incidental — it is the privacy design. This project does no face recognition and no identity attribution. See [`docs/adr/0002-naming-and-domain-language.md`](docs/adr/0002-naming-and-domain-language.md).

## Architecture

Continuous video cannot be fed to a VLM frame by frame. The core design is a **temporal compression pyramid**:

```
 30 fps raw stream
     │
 L0  Gating          motion diff · pose landmarks · object detect · frame embedding
     │               → decides which instants deserve a VLM call
     │               → target: 100×+ compression
     ▼
 L1  Caption         Gemma 4 12B Unified → structured Kineme (JSON)
     │
     ▼
 L2  Alerting        fast path: pose heuristic  (recall)
     │               slow path: VLM confirmation (precision)
     ▼
 L3  Summarize       hierarchical: kinemes → 15 min → hour → daily Ethogram
     │
     ▼
 L4  Query           embedding index → natural-language retrieval
     │
     ▼
 consumers           push notification · AppFunctions · MCP · ROS 2
```

Full detail, including why L2 is split into two paths, in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Modules

| Module | Status | Purpose |
|---|---|---|
| `ethogram/` | 🚧 active | Behaviour perception. Vision → Kineme stream. **Current focus.** |
| `core/` | 🚧 active | Domain types, tool contracts, shared schemas |
| `bench/` | ✅ ready | M0 backend benchmark — run this first |
| `asr/` | planned | Speech recognition → transcript kinemes |
| `tts/` | planned | Spoken response |
| `planner/` | planned | LLM orchestration, cloud escalation policy |
| `bridge/` | planned | AppFunctions provider · MCP server · ROS 2 node |

Only `ethogram/` is in scope for the current roadmap. The umbrella exists so that adding a second modality later does not require restructuring.

## Deployment topology

**Now — phone-first, single node** (ADR-0004):

```
┌──────────────────────────────────────────────┐
│  Pixel 10  ─ single node ─                     │
│                                                │
│  camera (on-device)                            │
│  L0 gating  ·  L1 Gemma E2B/E4B (LiteRT-LM)    │
│  L2 alerting · L3 summarize · L4 KinemeStore   │
│  AppFunctions provider · notifications          │
│  consent tiers · audit log                      │
│                                                │
│  ★ frames stay on device by policy             │
└──────────────────────────────────────────────┘
```

One process holds frames and answers queries, so frame isolation is enforced by
policy, not topology. That is the deliberate, temporary cost of the phone-first
pivot; PRIVACY.md is explicit about it.

**Later — two nodes, once a Jetson is available** (ADR-0003, deferred):

```
┌──────────────────────────────┐        ┌─────────────────────────────┐
│  Jetson  ─ sensor node ─     │  LAN   │  Pixel 10  ─ query surface ─│
│  L0–L4 · frames only here    │─mTLS──▶│  AppFunctions · no frame API│
│  ★ frames exist only here    │  gRPC  │  ★ structurally cannot      │
└──────────────────────────────┘        │    access frames            │
                                         └─────────────────────────────┘
```

The two-node split is a privacy mechanism, not just a performance one: the query
surface has no path to image data, so "we choose not to return frames" becomes
"we cannot". Restoring that guarantee is the reason the Jetson topology remains
the target.

## Quickstart

```bash
git clone https://github.com/TingFengChou/claustrum.git
cd claustrum

# M0 — the only thing that matters right now.
# Serve Gemma E2B/E4B on the phone, then expose it to the host over adb.
# On-device serving is not one command — see docs/M0-phone-setup.md.
#   adb forward tcp:8082 tcp:8082
pip install -r bench/requirements.txt
cp bench/backends.example.yaml bench/backends.yaml   # point at 127.0.0.1:<forwarded port>
python bench/run_bench.py --frames bench/frames --out eval/reports
```

The harness runs on your laptop and talks to the phone over `adb forward`; it
samples the phone's thermal and battery state via `adb` (`bench/phone_monitor.py`).

Nothing downstream can be designed before M0 produces numbers. See [`bench/README.md`](bench/README.md).

## Roadmap

| M | Name | Outcome | Est. |
|---|---|---|---|
| **M0** | Backend spike | Latency / memory / thermal table for on-device Gemma E2B/E4B on the Pixel 10; keyframe budget decided | 1–2 wk |
| **M1** | Structured caption | Prompt v1 + Kineme schema; caption acceptability > 70 %, JSON parse > 98 % | 2–3 wk |
| **M2** | Offline pipeline | L0 gating + L1 batch + KinemeStore; > 100× compression on a 1 h video | 3–4 wk |
| **M3** | Ethogram + query | L3 hierarchical summary + L4 retrieval | 3 wk |
| **M4** | AppFunctions | Pixel 10 provider, consent tiers, audit log | 3–4 wk |
| **M5** | Realtime + alerting | L2 dual path; false positives < 3 / 24 h | 4–5 wk |
| **M6** | Hardening | 7-day continuous run; false positives < 1 / 24 h | 4 wk |
| **M7** | Robot bridge | MCP server + ROS 2 node + spatial anchoring PoC | 3 wk |

M4 depends only on M3, not on the realtime pipeline — it is sequenced early because it validates whether kineme quality is good enough to support natural-language querying, before the expensive M5–M6 work begins.

Full plan with acceptance criteria: [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Key metrics

The project is judged on these, not on demo quality:

| Metric | Target |
|---|---|
| Keyframe compression ratio | > 100× |
| Caption hallucination rate | < 5 % |
| Fall detection recall | > 90 % |
| **False alerts per 24 h** | **< 1** |
| End-to-end alert latency (p95) | < 5 s |
| Continuous uptime without thermal throttle | 7 days |

False alerts per 24 h is the primary metric. A system that cries wolf once a day gets its notifications muted within two weeks, at which point recall is irrelevant.

## Decisions

- [ADR-0001 — Platform: Jetson AGX Orin over Android](docs/adr/0001-platform-choice.md)
- [ADR-0002 — Naming and domain language](docs/adr/0002-naming-and-domain-language.md)
- [ADR-0003 — Two-node topology and the frame isolation boundary](docs/adr/0003-two-node-topology.md)

## Licence

Apache-2.0. See [`LICENSE`](LICENSE).
