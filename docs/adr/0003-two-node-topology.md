# ADR-0003 — Two-node topology and the frame isolation boundary

**Status:** accepted · **Date:** 2026-07-30

## Context

The Pixel 10 satisfies AppFunctions' requirements (Android 16+, and it is among the first devices where the feature is actually available). This makes exposing household queries to Gemini viable now rather than eventually.

But AppFunctions' own documentation notes that system agents may process queries server-side. The project's core privacy premise is that video does not leave the device.

## Decision

Split into two nodes with an **asymmetric capability boundary**:

- **Sensor node** (Jetson AGX Orin) — L0 through L4. Frames are stored here and only here.
- **Query surface** (Pixel 10) — AppFunctions provider, notifications, consent tiers, audit log. Communicates with the sensor node over LAN via mTLS gRPC. Paired by QR code.

The query surface is given **no API through which frames can be requested.** Not a disabled endpoint — no endpoint.

## Rationale

The split is primarily a privacy mechanism and secondarily a deployment convenience. It converts "we choose not to return frames" into "we cannot return frames" — a guarantee that survives future code changes, careless refactors, and the author's own later convenience.

Tiered consent, caller allowlisting and audit logging (see PRIVACY.md) protect the *text* channel. This boundary protects the *image* channel, and does so structurally.

## Consequences

- LAN discovery, pairing and certificate management to build. mTLS with QR-code pairing.
- Offline degradation: if the LAN link drops, the query surface cannot answer. Acceptable. If local fallback inference is ever wanted, Android AICore (where Gemma 4 is available as Gemini Nano) is preferable to bundling a `.litertlm` — system-managed memory and versioning.
- Two deployables instead of one; two release processes.
- Spare phones are repurposed as RTSP camera sources rather than compute nodes, which suits them better anyway.
