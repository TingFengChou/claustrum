---
name: dev-standards
description: Development standards for the claustrum project — PR + AI-review quality gate, SA/SD design docs per module, maximal testability, Claude-designed app UI, and docs-updated-on-milestone. Use whenever developing, reviewing, shipping, or documenting any claustrum module or the app, and before opening a PR or calling a checkpoint done.
---

# claustrum development standards

These are the standing rules for how work on `claustrum` is done. Follow them for
every module and every change. They exist because this is a safety-adjacent
on-device perception system: quality must be enforced by process and tests, not
asserted by hand.

Project context: development is **phone-first, single-node** on a Pixel 10 with
bundled Gemma E2B/E4B (see `docs/adr/0004-phone-first-single-node.md`). The
Jetson two-node design is a deferred target, not the current build.

## 1. Ship via PR, gated by AI code review

- **Never push to `main` directly.** Branch → commit → push branch → open a PR
  with `gh pr create`.
- Every PR triggers `.github/workflows/ai-review.yml`, which runs an AI code
  review (Gemini via `run-gemini-cli`). Let the review and CI complete; address
  findings before merge.
- The review workflow needs a `GEMINI_API_KEY` repo secret. Only the user can
  add secrets — do not attempt it; remind them if the review job is skipped.
- Keep PRs focused and reviewable; prefer several small PRs over one sprawling
  one so the AI reviewer and CI give sharp signal.

## 2. SA/SD design docs per module

- Every module or section keeps **complete SA (System Analysis) and SD (System
  Design)** docs under `docs/design/<module>/SA.md` and `SD.md`.
- Start from `docs/design/_template/`. Keep them in sync with the code in the
  same PR that changes the code — a stale design doc is a bug.
- SA = what/why (scope, actors, functional + non-functional requirements, domain
  model, acceptance criteria, traceability to ADRs/roadmap).
- SD = how (components + responsibilities, interfaces/contracts, data structures,
  key flows with Mermaid diagrams, error handling, dependencies, and a required
  **testing strategy** section).

## 3. Build for testability

- Design modules to be **as testable as possible**: dependencies injectable
  (e.g. the VLM backend is an interface, not a hardcoded HTTP call), side-effects
  pushed to the edges, pure logic separable from I/O.
- Every module lands with a test suite that CI runs. No module is "done" without
  tests. The SD doc's testing-strategy section says how it is tested and with
  what fakes.

## 4. App UI designed with Claude, to near-production quality

- Any app screen or visual surface is designed to **near-production quality using
  Claude's design capabilities** — load the `artifact-design` skill (and Figma
  skills / `dataviz` where relevant) and build a real visual system: theming,
  spacing, typography, component states, empty/error/loading states.
- Not default widgets, not throwaway placeholder UI.

## 5. Update docs on every checkpoint/milestone

- Completing a checkpoint or milestone is **not done until the docs reflect it.**
  In the same PR, update the README, `docs/ROADMAP.md` status, `docs/ARCHITECTURE.md`
  if the design moved, and the relevant SA/SD docs.
- Documentation must never lag the actual state of the project.

## Definition of done (checklist)

Before opening a PR or calling a checkpoint complete:

- [ ] Code has tests; CI passes locally (`python -m unittest discover -s tests`)
- [ ] SA/SD docs for the touched module created/updated
- [ ] README / ROADMAP / ARCHITECTURE updated if the change is a milestone or moves the design
- [ ] Change is on a branch with a PR — never pushed to `main` directly
- [ ] App UI (if any) designed with Claude to near-production quality
