# Design documentation

Every module or section of `claustrum` keeps a complete **SA** (System Analysis)
and **SD** (System Design) document. This is a standing project rule — see the
`dev-standards` skill. A stale design doc is treated as a bug: SA/SD are updated
in the same PR as the code they describe.

## Layout

```
docs/design/
  README.md            this file — the convention
  _template/
    SA.md              copy to start a module's System Analysis
    SD.md              copy to start a module's System Design
  <module>/
    SA.md              what the module must do, and why
    SD.md              how it does it
```

Modules mirror the top-level layout: `core/`, `bench/`, `ethogram/`, and later
`asr/`, `planner/`, `bridge/`, plus the phone app when it exists.

## SA vs SD — the split

- **SA answers *what* and *why*.** Scope, actors, functional and non-functional
  requirements, the domain model the module touches, constraints, acceptance
  criteria, and traceability to the ADRs and roadmap. No implementation.
- **SD answers *how*.** Components and their responsibilities, interfaces and
  contracts, data structures, key flows (with Mermaid diagrams), error handling,
  dependencies, and — required — a **testing strategy** section, because every
  module must be testable (dev-standards skill).

Keep each document short and true rather than long and aspirational. If a section
does not apply, write "n/a" and why, rather than padding it.

## Traceability

Design decisions with lasting consequence are recorded as ADRs in
[`docs/adr/`](../adr/), not buried in SD. SA/SD reference the relevant ADR rather
than restating it. The current platform reality (phone-first, single-node) is
[ADR-0004](../adr/0004-phone-first-single-node.md).
