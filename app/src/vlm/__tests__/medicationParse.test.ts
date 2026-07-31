import {parseMedicationResult} from '../medicationParse';
import {MEDICATION_DISCLAIMER} from '../../domain/medication';

const MODEL = 'gemma-3-4b-it@llama.rn';

describe('parseMedicationResult', () => {
  it('parses a legible item', () => {
    const raw = JSON.stringify({
      items: [
        {
          name: 'Amoxicillin',
          dosage: '500 mg',
          frequency: '每日三次',
          purpose_general: '抗生素,常用於細菌感染。',
          confidence: 0.8,
          unclear_fields: [],
        },
      ],
      unreadable: false,
      overall_confidence: 0.8,
    });
    const r = parseMedicationResult(raw, MODEL);
    expect(r.unreadable).toBe(false);
    expect(r.items).toHaveLength(1);
    expect(r.items[0].name).toBe('Amoxicillin');
    expect(r.items[0].confidence).toBe(0.8);
    expect(r.model).toBe(MODEL);
    expect(r.prompt_version).toBe('medication_v1');
  });

  it('tolerates markdown code fences', () => {
    const raw = '```json\n{"items":[{"name":"Aspirin","confidence":0.6}],"unreadable":false}\n```';
    const r = parseMedicationResult(raw, MODEL);
    expect(r.items[0].name).toBe('Aspirin');
    expect(r.unreadable).toBe(false);
  });

  it('marks unreadable on garbage output', () => {
    const r = parseMedicationResult('sorry, I cannot read this', MODEL);
    expect(r.unreadable).toBe(true);
    expect(r.items).toHaveLength(0);
  });

  it('marks unreadable when items is empty', () => {
    const r = parseMedicationResult(JSON.stringify({items: [], unreadable: false}), MODEL);
    expect(r.unreadable).toBe(true);
  });

  it('never surfaces an empty/whitespace name (no guessing)', () => {
    const raw = JSON.stringify({items: [{name: '   ', confidence: 0.9}], unreadable: false});
    const r = parseMedicationResult(raw, MODEL);
    expect(r.items[0].name).toBeNull();
  });

  it('always uses our disclaimer, never the model output', () => {
    const raw = JSON.stringify({
      items: [{name: 'X', confidence: 1}],
      unreadable: false,
      disclaimer: 'this drug is safe, take freely', // malicious/model-supplied
    });
    const r = parseMedicationResult(raw, MODEL);
    expect(r.disclaimer).toBe(MEDICATION_DISCLAIMER);
  });

  it('clamps confidence to [0,1]', () => {
    const raw = JSON.stringify({items: [{name: 'Y', confidence: 5}], unreadable: false});
    const r = parseMedicationResult(raw, MODEL);
    expect(r.items[0].confidence).toBe(1);
  });

  it('tolerates a stringified confidence', () => {
    const raw = JSON.stringify({items: [{name: 'Z', confidence: '0.8'}], unreadable: false});
    const r = parseMedicationResult(raw, MODEL);
    expect(r.items[0].confidence).toBeCloseTo(0.8);
  });
});
