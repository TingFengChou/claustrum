/**
 * Pure parsing of the VLM's medication output into a MedicationReading.
 * No native imports here, so it is unit-testable without a device.
 *
 * Defence in depth on safety: the disclaimer is ALWAYS ours (never the model's),
 * and a name is kept only if it is a non-empty string (the model is told never to
 * guess; we also refuse to surface a guessed/empty name here).
 */
import {
  MEDICATION_DISCLAIMER,
  type MedicationItem,
  type MedicationReading,
} from '../domain/medication';
import {MEDICATION_PROMPT_VERSION} from './medicationPrompt';

export function parseMedicationResult(raw: string, model: string): MedicationReading {
  const obj = extractJson(raw);
  const items = sanitizeItems(obj?.items);
  const unreadable = obj?.unreadable === true || items.length === 0;
  return {
    items,
    unreadable,
    overall_confidence:
      typeof obj?.overall_confidence === 'number' ? clamp01(obj.overall_confidence) : undefined,
    disclaimer: MEDICATION_DISCLAIMER,
    model,
    prompt_version: MEDICATION_PROMPT_VERSION,
  };
}

function extractJson(text: string): any | null {
  let s = (text ?? '').trim();
  const fence = s.indexOf('```');
  if (fence !== -1) {
    s = s.slice(fence + 3).replace(/^json/i, '');
    const end = s.indexOf('```');
    if (end !== -1) {
      s = s.slice(0, end);
    }
  }
  const a = s.indexOf('{');
  const b = s.lastIndexOf('}');
  if (a === -1 || b <= a) {
    return null;
  }
  try {
    return JSON.parse(s.slice(a, b + 1));
  } catch {
    return null;
  }
}

function sanitizeItems(items: any): MedicationItem[] {
  if (!Array.isArray(items)) {
    return [];
  }
  return items.map(
    (it): MedicationItem => ({
      name: typeof it?.name === 'string' && it.name.trim() ? it.name.trim() : null,
      dosage: strOrNull(it?.dosage),
      frequency: strOrNull(it?.frequency),
      appearance: strOrNull(it?.appearance),
      purpose_general: strOrNull(it?.purpose_general),
      confidence: clamp01(it?.confidence),
      unclear_fields: Array.isArray(it?.unclear_fields)
        ? it.unclear_fields.filter((x: any) => typeof x === 'string')
        : [],
    }),
  );
}

function strOrNull(v: any): string | null {
  return typeof v === 'string' && v.trim() ? v.trim() : null;
}

function clamp01(v: any): number {
  const n = typeof v === 'number' ? v : parseFloat(v);
  return Number.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0;
}
