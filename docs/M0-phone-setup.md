# M0 on the Pixel 10 — serving setup

The M0 harness ([`bench/run_bench.py`](../bench/run_bench.py)) is a plain HTTP
client: it POSTs to an OpenAI-compatible `/v1/chat/completions` on
`127.0.0.1:<port>`, which `adb forward` tunnels to a server running on the phone.
The harness does not care *what* serves the model — only that the endpoint
exists. This document is about standing that server up, because on Android it is
not a single command.

## The honest problem

The chosen production runtime is **Gemma E2B/E4B via LiteRT-LM** (ADR-0004). But
LiteRT-LM — like MediaPipe LLM Inference and AICore — is designed to run **inside
an Android app**, not as a headless HTTP server you reach over adb. There is no
turnkey `litert-lm serve` for on-device Android. So "bundled LiteRT-LM" (the
production path) and "headless HTTP bench" (the fast M0 path) are not the same
runtime, and pretending otherwise would make the M0 latency numbers a fiction.

Resolve it in two steps.

## Step 1 — fast, headless numbers with llama.cpp in Termux (recommended for M0)

`llama-server` is OpenAI-compatible, supports Gemma multimodal via an mmproj
projector, builds on Android in Termux, and runs fully headless. This is the
cheapest way to get real latency / thermal / quality signal on the actual phone.

```bash
# on the phone, in Termux
pkg install git cmake clang
git clone https://github.com/ggml-org/llama.cpp && cd llama.cpp
cmake -B build && cmake --build build -j        # add -DGGML_VULKAN=ON to try the GPU
# fetch a Gemma E2B/E4B GGUF + its mmproj into ~/models, then:
./build/bin/llama-server -m ~/models/gemma-e4b-Q4_K_M.gguf \
    --mmproj ~/models/mmproj-gemma-e4b.gguf --port 8082 --host 127.0.0.1
```

```bash
# on the host
adb forward tcp:8082 tcp:8082
python bench/run_bench.py --backend gemma-e4b --repeats 5
```

**Caveat that must be recorded in the M0 report:** Vulkan on mobile GPUs is
uneven; if the build falls back to CPU, latency is a *pessimistic* bound and
power draw is unrepresentative of the production runtime. Use these numbers for
the E2B-vs-E4B **quality** decision and the grid experiment (both runtime-
independent), and treat latency as provisional.

## Step 2 — production-fidelity numbers with LiteRT-LM (before freezing the budget)

The keyframe budget depends on real production latency, so it must be measured
on the runtime that ships. Two ways, in increasing effort:

- **Minimal harness app** — a tiny Android app that loads the `.litertlm` via
  LiteRT-LM and exposes a localhost `/v1/chat/completions` over an embedded HTTP
  server. Then the exact same `bench/run_bench.py` works unchanged over
  `adb forward`. This keeps one benchmark, two runtimes.
- **In-app microbench** — measure inside the app and export a JSON in the same
  shape as the harness. Less reuse, but no embedded server to write.

Prefer the harness app: it is the seed of the real capture app anyway.

## What blocks this right now

1. **Authorize adb on the phone.** The Pixel currently shows `unauthorized`;
   accept the "Allow USB debugging" dialog and tick "always allow".
2. **Build the fixture set** — see [`bench/README.md`](../bench/README.md). No
   numbers mean anything without the ambiguous `unclear` frames.
3. **Decide the Step-1 model files** — which E2B/E4B GGUF quantisation to pull.

Once adb is authorized, `adb shell getprop ro.product.model ro.build.version.release`
and `ro.soc.model` confirm the exact device and SoC, which decides whether the
Vulkan build is worth attempting.
