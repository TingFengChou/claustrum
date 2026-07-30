# ADR-0001 — Platform: Jetson AGX Orin over Android

**Status:** accepted · **Date:** 2026-07-30

## Context

Available hardware: a Jetson AGX Orin developer kit (32 GB unified LPDDR5) and a Pixel 10.

The initial plan favoured Android, on two premises:
1. Forking `google-ai-edge/gallery` would give the fastest path to a first result — model management, LiteRT-LM bindings and benchmark UI inherited for free.
2. Constrained hardware implied a small model (Gemma 4 E2B/E4B).

## Decision

**Jetson AGX Orin is the primary platform from M0. Gemma 4 12B Unified is the primary model.** The Pixel 10 becomes the query surface (M4), not a compute node.

## Rationale

Both original premises fail against this hardware.

**On iteration speed.** The premise conflated "convenient model management" with "fast iteration". What actually gets iterated hundreds of times in M0–M3 is prompts, gating parameters, schemas and eval fixtures. On Linux that is edit-and-run; on Android it is Gradle build → APK install → adb observe. An order of magnitude difference. The model-download UI that Gallery provides is not needed at this stage.

**On model size.** 32 GB of unified memory comfortably holds a 12B multimodal model. Gemma 4 12B Unified is documented as running locally on machines with 16 GB of VRAM or unified memory, and its encoder-free architecture — raw image patches projected directly into the LLM embedding space — reduces multimodal latency rather than increasing it.

This matters because **VLM hallucination is the project's first-order risk** (see ARCHITECTURE.md). Moving from a 2B to a 12B model is the single most effective available intervention against it, and the hardware already affords it. Prompt engineering is the second line of defence, not the first.

## Consequences

- Diverges from the Gallery codebase. Mitigated by `litert-lm serve`, which exposes an OpenAI-compatible local endpoint over the same `.litertlm` model format — so quantisation scheme and prompts remain portable if an Android build is revisited.
- LiteRT-LM's Linux GPU path (ML Drift) is unproven on Jetson's CUDA/ARM combination. Jetson's native strengths are TensorRT-LLM and llama.cpp CUDA. **M0 must benchmark all three** before committing.
- Requires mains power, active cooling, and cameras (none built in). RTSP IP cameras, or a spare phone running an RTSP server app.
- AGX Orin is standard robotics compute. The robot extension moves from speculative to "add a ROS 2 node to the same machine".
- Fan noise and 15–60 W draw need evaluating for a domestic setting. Test 30 W mode viability in M0.
