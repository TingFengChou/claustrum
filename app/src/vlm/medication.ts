/**
 * Read a medication label/prescription image on-device and return a structured,
 * safety-wrapped MedicationReading. EDUCATIONAL ONLY, NOT MEDICAL ADVICE.
 */
import type {MedicationReading} from '../domain/medication';
import {describeImage} from './vlmService';
import {MEDICATION_PROMPT} from './medicationPrompt';
import {parseMedicationResult} from './medicationParse';

export {parseMedicationResult};

/** Requires loadVlm() to have been called. `imagePath` is an on-device file path. */
export async function readMedication(
  imagePath: string,
  model: string,
): Promise<MedicationReading> {
  const raw = await describeImage(imagePath, MEDICATION_PROMPT);
  return parseMedicationResult(raw, model);
}
