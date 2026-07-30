# Evaluation

Most projects of this kind fail here, not at the model.

## Datasets

| Source | Purpose | Note |
|---|---|---|
| Self-recorded household video, including staged falls **and deliberate lying down** | Primary | Written consent from everyone on camera |
| UP-Fall Detection Dataset | Fall benchmark | Public, multi-view |
| Charades / ActivityNet Captions | Everyday action caption quality | Public |
| **72 hours of real, uneventful footage** | **False-alert rate** | The most important and most skipped |

That last row is the one that decides the project. A system running twenty-four
hours a day needs to be measured on how much it interrupts when nothing is
happening. Start recording it now — it is the longest-lead asset here.

## Metrics and targets

| Layer | Metric | M6 target |
|---|---|---|
| L0 | Keyframe compression / miss rate | > 100x, < 5 % of salient events missed |
| L1 | Caption acceptability (LLM-as-judge + manual sample) | > 80 % |
| L1 | **Hallucination rate** (describes something not in frame) | **< 5 %** |
| L2 | Fall recall | > 90 % |
| L2 | **False alerts per 24 h** | **< 1** |
| L2 | End-to-end alert latency p95 | < 5 s |
| L3 | Ethogram usefulness (manual, 1–5) | > 3.5 |
| System | 7-day continuous run | No crash, no thermal throttle |

False alerts per 24 h is the primary metric. Recall is worthless once the user
has muted notifications.

## Regression harness

`eval/harness/` runs against fixed fixtures and writes results to
`eval/reports/{timestamp}_{prompt_version}_{model}.json`.

Any change to a prompt, a model, or a gating parameter requires a run, with the
before/after comparison pasted into the pull request. Pull requests carrying the
`prompt-change` label are blocked until this exists.
