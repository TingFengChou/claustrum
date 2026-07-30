# core — System Analysis (SA)

**Status:** active · **Last updated:** 2026-07-30 · **Owner:** claustrum

## 1. Purpose and scope

`core` defines the shared domain vocabulary of the system as Python types and the
JSON Schema that mirrors them, and enforces the project's privacy and
anti-hallucination commitments **as type-level invariants** rather than as
convention. In scope: `Actant`, `Kineme`, `Risk`, `SpatialAnchor`, `Ethogram`
and friends, their serialisation, and schema validation. Out of scope: the
pipeline stages that produce/consume these types (that is `ethogram/` and later
modules), and the tool/transport contract (a future `core/tools/`).

## 2. Actors and context

- **L1 caption** produces `Kineme`s (via the model + pipeline).
- **L0 / L3** populate pipeline-computed fields (e.g. `novelty`).
- **L4 / transports** consume `Kineme`s and `Ethogram`s.
- **CI** consumes the schema and the types to check they agree.

`core` sits below every other module and depends on none of them.

## 3. Functional requirements

- **FR-1** Define the domain types and the closed enumerations (`ActantType`,
  `RiskLevel`, `RiskCategory`).
- **FR-2** Enforce invariants at construction: id format, time ordering,
  ranges for `confidence`/`novelty`, action length.
- **FR-3** Enforce the privacy invariant: `Actant.label` is a role slot, never an
  identity; no identity field exists anywhere.
- **FR-4** Enforce the anti-hallucination invariant: `risk.level != none`
  requires a `reason`; `level == none` pairs with `category == none`.
- **FR-5** Serialise (`to_dict`) and provide an off-node-safe `redacted` form.
- **FR-6** Validate a payload against the JSON Schema, timestamps included.

## 4. Non-functional requirements

- **NFR-1 Dependency-light.** Stdlib only; `jsonschema` imported lazily and only
  for validation.
- **NFR-2 Schema/type agreement.** The dataclasses and `schemas/kineme.schema.json`
  must not drift — enforced by CI in both value and field-name dimensions.
- **NFR-3 Testability.** Every invariant is unit-testable without hardware.

## 5. Domain model

```
Actant        a scene participant — role slot, never an identity
Kineme        one observed behaviour over one time span (L1 output)
Risk          level + closed category + evidence reason
SpatialAnchor optional map coordinates (robot deployments only)
Ethogram      a catalogue of kinemes over a period (L3 output)
```

Invariants are described in FR-3 and FR-4 and in [ADR-0002](../../adr/0002-naming-and-domain-language.md).

## 6. Constraints and assumptions

- No face recognition, no identity attribution — structural, expressed as types.
- `novelty` is pipeline-computed, not model-reported ([ADR-0004](../../adr/0004-phone-first-single-node.md) reframing; see also ARCHITECTURE.md).
- Timestamps are ISO-8601 / RFC-3339.

## 7. Acceptance criteria

- Constructing a type with a bad id, reversed timestamps, out-of-range
  confidence/novelty, an identity-shaped label, or an unjustified risk **raises**.
- A well-formed `Kineme` serialises and validates against the schema; a malformed
  timestamp is rejected by validation.
- The schema enums equal the Python enums; the dataclass fields equal the schema
  properties (both directions).

All of the above are realised as tests in `tests/test_domain.py`.

## 8. Open questions

- **Model-output subset schema.** The prompt asks the model for a *subset* of
  `Kineme` (no `id`/timestamps/`source_id`/`model`/`prompt_version`); that subset
  is not yet formally defined or validated. Deferred to M1.
- **`Ethogram` schema.** Only `Kineme` has a JSON Schema; `Ethogram` (the primary
  output, and one that crosses the node boundary in M3/M4) needs one. Deferred to M3.

## Traceability

[ADR-0002](../../adr/0002-naming-and-domain-language.md) (naming/invariants),
[ADR-0004](../../adr/0004-phone-first-single-node.md) (novelty reframing),
roadmap M1 (freeze schema). Design in [`SD.md`](SD.md).
