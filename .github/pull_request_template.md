## What and why

<!-- One or two sentences. -->

## Type

- [ ] feature
- [ ] fix
- [ ] spike / experiment
- [ ] prompt or model change  ← **requires the eval table below**
- [ ] docs

## Eval regression (required for prompt / model / gating changes)

Run `eval/harness` before and after. Paste the comparison:

| Metric | Before | After |
|---|---|---|
| Caption acceptability | | |
| **Hallucination rate** | | |
| JSON parse rate | | |
| Keyframe compression | | |
| Fall recall | | |
| **False alerts / 24 h** | | |
| Latency p95 | | |

Report link: `eval/reports/...`

## Privacy check

- [ ] No new path by which frames could leave the sensor node
- [ ] No identity attribution introduced into `Actant` or anywhere else
- [ ] Any newly exposed tool is behind the correct consent tier and is logged

## Domain language

- [ ] Uses `Actant` / `Kineme` / `Ethogram` consistently; no synonyms introduced
