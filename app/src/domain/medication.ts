/**
 * TypeScript view of schemas/medication.schema.json (the single source of truth).
 * EDUCATIONAL INFORMATION ONLY, NOT MEDICAL ADVICE.
 */

export interface MedicationItem {
  /** Drug name exactly as visible, or null if not legible. Never guessed. */
  name: string | null;
  dosage?: string | null;
  frequency?: string | null;
  appearance?: string | null;
  /** General educational note about the drug class, in zh-TW. null if not identified. */
  purpose_general?: string | null;
  confidence: number;
  unclear_fields?: string[];
}

export interface MedicationReading {
  items: MedicationItem[];
  unreadable: boolean;
  overall_confidence?: number;
  disclaimer: string;
  model: string;
  prompt_version: string;
}

/** Fixed safety disclaimer, always shown with a reading. */
export const MEDICATION_DISCLAIMER =
  '⚠️ 僅供資訊參考,非醫療建議。藥品辨識可能有誤,請以藥師/醫師與藥袋標示為準。';
