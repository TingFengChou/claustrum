/**
 * Live-caption prompt for the on-device VLM (the "即時字幕" effect).
 * Kept short so per-frame latency stays low.
 *
 * SmolVLM-256M is English-centric, so captions come out in English (reliable);
 * faithful zh-TW captioning needs a larger multilingual model (e.g. Gemma E-series),
 * which is much slower per frame on a phone CPU. Model choice is in models.ts.
 */
export const CAPTION_PROMPT = 'Describe the scene in one short sentence.';
