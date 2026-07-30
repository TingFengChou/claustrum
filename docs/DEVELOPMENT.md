# Development process

How work on `claustrum` is done. These rules are also encoded in the
`dev-standards` agent skill (`.claude/skills/dev-standards/`) so they are applied
automatically; this document is the human-readable version.

## Ship via PR, gated by review

- No direct pushes to `main`. Branch → commit → push → open a PR.
- Each PR runs two checks:
  - **`ci.yml`** — tests + schema/identity guards. This is the **hard gate**.
  - **`ai-code-review.yml`** — an advisory Gemini review comment. See below.
- **Merge is a fact-based decision, not rubber-stamping.** Every AI review
  comment is examined and **replied to** — fixed, or answered with why it does
  not hold after checking. Merge over an AI comment only when it is verified
  wrong (and say so); hold a green PR when verification finds a real problem the
  review missed. The decision rests on evidence — tests, reading the code,
  running it — not on the verdict or the checkmark alone.

### Enabling AI review

The AI review needs a `GEMINI_API_KEY` repository secret (Settings → Secrets and
variables → Actions). Only a repo admin can add it. Until it is set, the review
workflow skips cleanly. Optionally set a `GEMINI_MODEL` repo variable to change
the model. The review is advisory — it never blocks a merge; the tests do.

## Design docs per module (SA/SD)

Every module keeps a System Analysis and System Design doc under
[`docs/design/<module>/`](design/). Start from
[`docs/design/_template/`](design/_template/). Update them in the same PR as the
code — see [`docs/design/README.md`](design/README.md). `core` is the worked
example.

## Testability

Modules are built to be testable without hardware: dependencies behind
interfaces, side-effects at the edges, tests shipping with the module and run by
CI. A module without tests is not done.

## App UI

Any app UI is designed to near-production quality using Claude's design
capabilities (load the `artifact-design` skill; Figma / `dataviz` where
relevant), not default or placeholder widgets. Branding assets live in
[`assets/`](../assets/).

## Docs track reality

Completing a checkpoint or milestone includes updating the README,
[`ROADMAP.md`](ROADMAP.md) status, [`ARCHITECTURE.md`](ARCHITECTURE.md) if the
design moved, and the affected SA/SD docs — in the same PR. Docs must not lag the
code.

## Definition of done

- [ ] Tests written; `python -m unittest discover -s tests` green
- [ ] SA/SD updated for touched modules
- [ ] README / ROADMAP / ARCHITECTURE updated if a milestone or design change
- [ ] On a branch with an open PR; CI green; review addressed
- [ ] Any app UI designed to near-production quality
