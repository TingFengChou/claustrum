# M0 — backend benchmark

**Run this before writing anything else.** The p95 single-call latency of the L1
caption stage determines the L0 keyframe budget, whether a two-model tier is
needed, and the ceiling on realtime alerting. Every architectural decision
downstream is a guess until these numbers exist.

**Phone-first (ADR-0004).** The model runs on the Pixel 10; this harness runs on
your laptop and reaches it over `adb forward`. It samples the phone's thermal,
power and memory state over adb (`phone_monitor.py`). The `--monitor tegra` path
is kept for the eventual Jetson.

```bash
adb devices                        # confirm the Pixel 10 is attached
adb forward tcp:8081 tcp:8081      # once per served port
```

## What it measures

| Metric | Why |
|---|---|
| Latency p50 / p95 | Sets the keyframe budget |
| Time to first token | Relevant if streaming partial captions is ever wanted |
| Cold start | A service that restarts pays this repeatedly |
| Peak RAM | Phone RAM is shared between the model, the camera pipeline and the OS |
| Peak temp + **temp drift** | Drift across a run predicts whether a long run throttles |
| Mean power | Continuous inference drains the battery; sets the sustainability limit |
| JSON parse rate | Below ~98 % is a prompt problem, not a backend problem |

## Building the fixture set

Put 20–40 frames in `bench/frames/`, named so they sort chronologically
(`0001.jpg`, `0002.jpg`, ...). Cover the cases that matter:

- ordinary activity — someone walking through, sitting, eating
- **deliberate lying down / sitting** — the false-positive case that must be
  distinguished from a fall
- a staged fall, captured before / during / after
- an empty room
- poor lighting, and near-darkness
- a pet doing something
- an ambiguous frame where "unclear" is the correct answer

That last category is the one people skip. Without it there is no way to tell
whether the model knows how to decline.

## Running it

```bash
pip install -r bench/requirements.txt
cp bench/backends.example.yaml bench/backends.yaml   # edit ports / model names

# serve the model on the phone, forward the port, then:
python bench/run_bench.py --backend gemma-e2b --repeats 5

# the E2B-vs-E4B question -- is the smaller model good enough?
python bench/run_bench.py --backend gemma-e4b --repeats 5

# the grid experiment -- potentially a 4x reduction in VLM calls
python bench/run_bench.py --backend gemma-e4b --grid 1x1
python bench/run_bench.py --backend gemma-e4b --grid 2x2
```

## The grid experiment

Gemma 4's variable-aspect-ratio vision handling means several frames composited
into one image may still be legible as a temporal sequence. If a `2x2` call costs
less than twice a `1x1` call, the effective VLM call budget stretches by up to
four times, and the entire power and latency envelope changes.

Run both and compare. This is the highest-leverage single experiment in M0.

## After the run

Reports land in `eval/reports/`. Then, by hand:

1. Open the JSON, score each retained sample: `manual_score` 1–5 for caption
   usefulness, `hallucinated` true/false.
2. Hallucination rate is the number that decides whether the project is viable.
   Anything above ~5 % means the safety alerting path cannot be trusted and the
   prompt or model needs work before M1 proceeds.
3. Record the chosen backend and keyframe budget as an ADR.
