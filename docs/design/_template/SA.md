# <module> — System Analysis (SA)

**Status:** draft | active | stable · **Last updated:** YYYY-MM-DD · **Owner:** <name>

## 1. Purpose and scope

What this module is for, in two or three sentences. What is explicitly **out** of
scope.

## 2. Actors and context

Who or what interacts with this module (other modules, the model, the user, the
camera, an external agent). A context sketch if useful.

## 3. Functional requirements

What the module must do. Number them (FR-1, FR-2, …) so SD and tests can trace
back to them.

## 4. Non-functional requirements

Latency, memory, power/thermal, privacy, reliability, testability targets. Number
them (NFR-1, …). Cite the project's key metrics where relevant.

## 5. Domain model

The `claustrum` domain types this module produces or consumes (`Actant`,
`Kineme`, `Ethogram`, …) and any module-specific concepts. Note invariants that
must hold.

## 6. Constraints and assumptions

Hardware (phone-first, single-node — ADR-0004), platform, dependency and data
constraints. Assumptions that, if false, change the design.

## 7. Acceptance criteria

How we know the module is done and correct — observable, ideally testable,
statements. These become the module's tests.

## 8. Open questions

Unresolved decisions, and what would resolve them.

## Traceability

Related ADRs, roadmap milestone(s), and the matching `SD.md`.
