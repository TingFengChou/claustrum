/**
 * TypeScript view of the domain contract. The single source of truth is
 * schemas/kineme.schema.json (ADR-0005); this mirrors it for the RN/UI side.
 * The native core produces Kinemes and passes the redacted (frame-free) form
 * across the bridge — frames never cross into JS (ADR-0004 / PRIVACY.md).
 *
 * Keep these in sync with schemas/kineme.schema.json and core/domain.py; the
 * schema is the authority if they ever disagree.
 */

export type ActantType = 'person' | 'animal' | 'robot' | 'vehicle' | 'unknown';
export type RiskLevel = 'none' | 'low' | 'medium' | 'high';
export type RiskCategory =
  | 'none'
  | 'fall'
  | 'fire_smoke'
  | 'water_leak'
  | 'intrusion'
  | 'child_hazard'
  | 'medical'
  | 'unknown';

export interface Actant {
  /** Role slot, never an identity, e.g. "person_1", "cat". */
  type: ActantType;
  label: string;
  count?: number;
}

export interface Risk {
  level: RiskLevel;
  category: RiskCategory;
  /** Required when level !== 'none': evidence visible in frame. */
  reason?: string | null;
}

/** One observed behaviour over one time span (L1 output). Frame-free on the JS side. */
export interface Kineme {
  id: string;
  ts_start: string; // ISO-8601
  ts_end: string; // ISO-8601
  source_id: string;
  actants: Actant[];
  action: string;
  risk: Risk;
  confidence: number; // 0..1
  model: string;
  prompt_version: string;
  objects?: string[];
  location_hint?: string | null;
  novelty?: number; // 0..1, pipeline-computed
  // keyframe_refs is intentionally absent on the JS side (see redacted() in core).
}

export const isUncertain = (k: Kineme): boolean =>
  k.confidence < 0.5 || k.action.trim().toLowerCase() === 'unclear';
