/**
 * On-device VLM service — the app-side wrapper around llama.rn (llama.cpp).
 * This is the "L1 caption" runtime from ADR-0005, running on-device (edge AI).
 * Frames/images stay on the device; only the resulting text leaves this layer.
 */

import {initLlama, type LlamaContext} from 'llama.rn';

export interface VlmModelPaths {
  /** Absolute on-device path to the Gemma vision GGUF. */
  model: string;
  /** Absolute on-device path to the multimodal projector (mmproj) GGUF. */
  mmproj: string;
}

let ctx: LlamaContext | null = null;

export function isVlmReady(): boolean {
  return ctx !== null;
}

export async function loadVlm(
  paths: VlmModelPaths,
  onProgress?: (pct: number) => void,
): Promise<void> {
  if (ctx) {
    return;
  }
  // CPU inference by default (n_gpu_layers: 0) for reliability across devices;
  // GPU/NPU offload is a later optimisation. The vision encoder uses GPU by
  // default via initMultimodal.
  ctx = await initLlama({model: paths.model, n_ctx: 4096, n_gpu_layers: 0}, onProgress);
  await ctx.initMultimodal({path: paths.mmproj});
}

/** Run a single multimodal completion over one image. Requires loadVlm() first. */
export async function describeImage(imagePath: string, prompt: string): Promise<string> {
  if (!ctx) {
    throw new Error('VLM not loaded — call loadVlm() first');
  }
  const res = await ctx.completion({
    messages: [{role: 'user', content: prompt}],
    media_paths: [imagePath],
    n_predict: 512,
    temperature: 0.1,
  });
  return res.text ?? '';
}

export async function releaseVlm(): Promise<void> {
  if (ctx) {
    await ctx.release();
    ctx = null;
  }
}
