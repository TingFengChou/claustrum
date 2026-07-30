# ADR-0002 — Naming and domain language

**Status:** accepted · **Date:** 2026-07-30

## Context

The project needs a name that survives its own scope. The near-term deliverable is behaviour perception from video; the stated long-term goal is a complete embodied cognition stack — ASR, TTS, VLM, LLM — for a robot.

A name chosen for what a project will become in two years is usually wrong for what it is now. A name chosen only for what it is now has to be abandoned later.

## Decision

**Two-layer naming.**

- `claustrum` — the umbrella. The structure Crick and Koch proposed as the binder of separate sensory modalities into unified experience; they likened it to an orchestra conductor. Its function *is* multimodal binding, which is what the umbrella does.
- `ethogram` — the behaviour perception module, and the current focus. In ethology, an ethogram is a formal catalogue of a species' discrete behaviours, compiled through systematic observation. It is the literal name of this system's output artifact.

**Domain vocabulary**, used consistently in code, schemas and documentation:

| Term | Meaning | Origin |
|---|---|---|
| `Actant` | A scene participant. A role slot, not an identity. | Actor-network theory (Latour); structural semiotics (Greimas) |
| `Kineme` | Smallest recorded unit of observed behaviour. | Kinesics (Birdwhistell) |
| `Ethogram` | A catalogue of kinemes over a period. | Ethology |

## Rationale

The three terms come from three traditions but share one methodological commitment: **systematically record observed behaviour without asserting motive.** An ethologist writes "individual A approached B and vocalised", not "A wanted to greet B".

That is precisely this project's anti-hallucination discipline. The vocabulary therefore carries the design principle, which is cheaper than explaining it repeatedly.

Two specific alignments:
- `Actant` as role-slot rather than person is the privacy design expressed as a type. Greimas's point is that narrative structure is analysable without knowing who anyone is — which is exactly what doing no face recognition forces, and gets for free.
- `Ethogram` contains no reference to vision, camera or home, so extending to audio, radar, or robot site perception does not invalidate it.

## Alternatives rejected

| Candidate | Reason |
|---|---|
| `saccade` | Excellent mechanism metaphor for L0 gating, but bound to ocular imagery — poor fit once audio and language are in scope. |
| `actant` (as repo name) | Names an element of the input data, not the system. Also reads as an actor-model or agent framework in 2026 — a crowded, misleading semantic space. Retained as a type name. |
| `theia`, `kairos`, `mnemosyne`, `tarsier`, `metis`, `nous` | Semantically apt but each collides with an established project (Eclipse Theia 21.6k★, Nous Research, etc.). |
| `viscribe`, `sightlog` | Clean and discoverable, but vision-scoped. |

## Consequences

- The name is undiscoverable by search. Mitigation: descriptive keywords carry in the repository description and topics, which GitHub indexes.
- `claustrum` and `ethogram` both need pronouncing at least once for any new collaborator: CLAW-strum, ETH-o-gram.
- Build `ethogram` now. Open the `claustrum` umbrella structure but leave sibling modules empty until a second modality is genuinely being integrated — at which point their shape will be better understood.
