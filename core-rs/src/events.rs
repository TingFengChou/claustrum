//! L2 temporal event engine.
//!
//! The engine consumes compact, on-device pose/motion observations produced by a
//! lightweight fast path. It never sees or stores image pixels. A slow L1 VLM caption
//! may be attached later as secondary context, but cannot promote a candidate or change
//! risk by itself: alert eligibility always comes from visible fast-path evidence.

use std::collections::{BTreeMap, VecDeque};
use std::fmt;

use serde::{Deserialize, Serialize};

/// Anonymous, short-lived role slot within one camera stream. This is not an identity.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ActantSlot(pub u16);

impl ActantSlot {
    pub fn label(self) -> String {
        format!("person_{}", self.0)
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Pose {
    #[default]
    Unknown,
    Upright,
    Seated,
    Crouched,
    Horizontal,
    Prone,
}

/// One timestamped output from the lightweight pose/motion/action feature extractor.
/// Scores are expected in 0..=1; non-finite/out-of-range values are clamped safely.
#[derive(Clone, Debug)]
pub struct Observation {
    pub at_ms: u64,
    pub actant: ActantSlot,
    /// Second anonymous role slot when a two-person action feature is available.
    pub secondary_actant: Option<ActantSlot>,
    pub pose: Pose,
    pub rapid_descent_score: f32,
    pub impact_score: f32,
    pub motion_score: f32,
    pub close_contact_score: f32,
    pub strike_score: f32,
    pub visible_people: u8,
    /// A one-shot, visibly observed crossing of a configured frame/zone boundary.
    pub zone_exit: bool,
}

impl Observation {
    pub fn new(at_ms: u64, actant: ActantSlot) -> Self {
        Self {
            at_ms,
            actant,
            secondary_actant: None,
            pose: Pose::Unknown,
            rapid_descent_score: 0.0,
            impact_score: 0.0,
            motion_score: 0.0,
            close_contact_score: 0.0,
            strike_score: 0.0,
            visible_people: 0,
            zone_exit: false,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EventKind {
    Fall,
    ZoneExit,
    Violence,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EventStatus {
    Candidate,
    Confirmed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RiskLevel {
    None,
    Low,
    Medium,
    High,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RiskCategory {
    None,
    Fall,
    Violence,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceSource {
    FastPath,
    Vlm,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceKind {
    UprightPose,
    RapidDescent,
    HorizontalOrPronePose,
    ImpactMotion,
    SustainedProne,
    ZoneBoundaryCrossing,
    TwoPeopleVisible,
    CloseContact,
    RepeatedRapidMotion,
    RepeatedStrikeMotion,
    VlmVisibleDescription,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Evidence {
    pub kind: EvidenceKind,
    pub source: EvidenceSource,
    pub observed_at_ms: u64,
    pub detail: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ActantType {
    Person,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Actant {
    #[serde(rename = "type")]
    pub actant_type: ActantType,
    pub label: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Risk {
    pub level: RiskLevel,
    pub category: RiskCategory,
    pub reason: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Event {
    pub id: String,
    #[serde(rename = "type")]
    pub event_type: EventKind,
    pub status: EventStatus,
    pub ts_start_ms: u64,
    pub ts_end_ms: u64,
    pub detected_at_ms: u64,
    pub source_id: String,
    pub actants: Vec<Actant>,
    pub evidence: Vec<Evidence>,
    pub risk: Risk,
    pub confidence: f32,
    pub detector: String,
    pub latency_ms: u64,
}

impl Event {
    /// Serialize using the exact `schemas/event.schema.json` transport shape.
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    /// Downstream notification code may only consider confirmed medium/high-risk events.
    pub fn alert_eligible(&self) -> bool {
        self.status == EventStatus::Confirmed
            && matches!(self.risk.level, RiskLevel::Medium | RiskLevel::High)
            && self
                .evidence
                .iter()
                .any(|evidence| evidence.source == EvidenceSource::FastPath)
    }

    /// Attach bounded L1 context without changing status, risk, confidence, or latency.
    /// A VLM caption is useful for review but is too slow and hallucination-prone to be
    /// the sole reason for an external alert.
    pub fn add_vlm_corroboration(&mut self, observed_at_ms: u64, caption: &str) -> bool {
        let detail: String = caption.trim().chars().take(240).collect();
        if detail.is_empty() {
            return false;
        }
        let evidence = Evidence {
            kind: EvidenceKind::VlmVisibleDescription,
            source: EvidenceSource::Vlm,
            observed_at_ms,
            detail,
        };
        // One bounded VLM context slot per event; later captions replace, not append.
        if let Some(existing) = self
            .evidence
            .iter_mut()
            .find(|existing| existing.source == EvidenceSource::Vlm)
        {
            *existing = evidence;
        } else {
            self.evidence.push(evidence);
        }
        true
    }
}

#[derive(Clone, Debug)]
pub struct EventConfig {
    pub observation_max_gap_ms: u64,
    pub fall_transition_ms: u64,
    pub fall_candidate_expiry_ms: u64,
    pub fall_prone_confirm_ms: u64,
    pub rapid_descent_threshold: f32,
    pub impact_threshold: f32,
    pub violence_window_ms: u64,
    pub violence_candidate_hits: usize,
    pub violence_confirm_hits: usize,
    pub violence_motion_threshold: f32,
    pub violence_contact_threshold: f32,
    pub violence_strike_threshold: f32,
    pub violence_cooldown_ms: u64,
    pub zone_exit_cooldown_ms: u64,
    pub track_retention_ms: u64,
}

impl Default for EventConfig {
    fn default() -> Self {
        Self {
            observation_max_gap_ms: 750,
            fall_transition_ms: 1_000,
            fall_candidate_expiry_ms: 5_000,
            fall_prone_confirm_ms: 2_000,
            rapid_descent_threshold: 0.85,
            impact_threshold: 0.85,
            violence_window_ms: 1_000,
            violence_candidate_hits: 2,
            violence_confirm_hits: 4,
            violence_motion_threshold: 0.85,
            violence_contact_threshold: 0.80,
            violence_strike_threshold: 0.90,
            violence_cooldown_ms: 30_000,
            zone_exit_cooldown_ms: 5_000,
            track_retention_ms: 60_000,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EventConfigError(&'static str);

impl fmt::Display for EventConfigError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.0)
    }
}

impl std::error::Error for EventConfigError {}

impl EventConfig {
    fn validate(&self) -> Result<(), EventConfigError> {
        let scores = [
            self.rapid_descent_threshold,
            self.impact_threshold,
            self.violence_motion_threshold,
            self.violence_contact_threshold,
            self.violence_strike_threshold,
        ];
        if scores
            .iter()
            .any(|score| !score.is_finite() || !(0.0..=1.0).contains(score) || *score == 0.0)
        {
            return Err(EventConfigError(
                "score thresholds must be finite 0<score<=1",
            ));
        }
        if self.observation_max_gap_ms == 0
            || self.fall_transition_ms == 0
            || self.fall_candidate_expiry_ms < self.fall_transition_ms
            || self.fall_prone_confirm_ms == 0
        {
            return Err(EventConfigError(
                "fall timing configuration is inconsistent",
            ));
        }
        if self.violence_window_ms == 0
            || self.violence_candidate_hits < 2
            || self.violence_confirm_hits <= self.violence_candidate_hits
            || self.violence_cooldown_ms == 0
        {
            return Err(EventConfigError(
                "violence timing/hit configuration is inconsistent",
            ));
        }
        if self.zone_exit_cooldown_ms == 0
            || self.track_retention_ms < self.observation_max_gap_ms
            || self.track_retention_ms < self.violence_window_ms
        {
            return Err(EventConfigError(
                "retention/cooldown configuration is inconsistent",
            ));
        }
        Ok(())
    }
}

#[derive(Default)]
struct TrackState {
    last_seen_at: Option<u64>,
    last_upright_at: Option<u64>,
    fall_upright_at: Option<u64>,
    descent_at: Option<u64>,
    horizontal_since: Option<u64>,
    fall_candidate_emitted: bool,
    fall_confirmed: bool,
    last_zone_exit_at: Option<u64>,
}

impl TrackState {
    fn reset_fall(&mut self) {
        self.fall_upright_at = None;
        self.descent_at = None;
        self.horizontal_since = None;
        self.fall_candidate_emitted = false;
        self.fall_confirmed = false;
    }
}

#[derive(Default)]
struct ViolenceState {
    hits: VecDeque<u64>,
    candidate_emitted: bool,
    cooldown_until: u64,
    last_seen_at: u64,
}

struct EventDraft {
    kind: EventKind,
    status: EventStatus,
    ts_start_ms: u64,
    detected_at_ms: u64,
    actants: Vec<ActantSlot>,
    evidence: Vec<Evidence>,
    risk_level: RiskLevel,
    risk_category: RiskCategory,
    risk_reason: Option<String>,
    confidence: f32,
    detector: &'static str,
}

/// Stateful L2 detector for one logical camera source.
pub struct EventEngine {
    source_id: String,
    config: EventConfig,
    tracks: BTreeMap<ActantSlot, TrackState>,
    violence: BTreeMap<(ActantSlot, ActantSlot), ViolenceState>,
    sequence: u64,
    last_observation_at: Option<u64>,
}

impl EventEngine {
    pub fn new(
        source_id: impl Into<String>,
        config: EventConfig,
    ) -> Result<Self, EventConfigError> {
        let source_id = source_id.into();
        let source_id = source_id.trim();
        if source_id.is_empty() || source_id.chars().count() > 80 {
            return Err(EventConfigError("source_id must contain 1..=80 characters"));
        }
        config.validate()?;
        Ok(Self {
            source_id: source_id.to_owned(),
            config,
            tracks: BTreeMap::new(),
            violence: BTreeMap::new(),
            sequence: 0,
            last_observation_at: None,
        })
    }

    /// Consume one chronological observation and return only newly crossed transitions.
    /// Stale out-of-order observations are ignored rather than rewinding detector state.
    pub fn process(&mut self, observation: Observation) -> Vec<Event> {
        if self
            .last_observation_at
            .is_some_and(|last| observation.at_ms < last)
        {
            return Vec::new();
        }
        self.last_observation_at = Some(observation.at_ms);

        let mut drafts = Vec::new();
        self.tracks.retain(|_, track| {
            track.last_seen_at.is_some_and(|last| {
                observation.at_ms.saturating_sub(last) <= self.config.track_retention_ms
            })
        });
        self.violence.retain(|_, state| {
            observation.at_ms.saturating_sub(state.last_seen_at) <= self.config.track_retention_ms
        });
        {
            let track = self.tracks.entry(observation.actant).or_default();
            if track.last_seen_at.is_some_and(|last| {
                observation.at_ms.saturating_sub(last) > self.config.observation_max_gap_ms
            }) {
                track.reset_fall();
                track.last_upright_at = None;
            }
            track.last_seen_at = Some(observation.at_ms);
            drafts.extend(process_fall(track, &observation, &self.config));
            if let Some(event) = process_zone_exit(track, &observation, &self.config) {
                drafts.push(event);
            }
        }
        if let Some(secondary) = observation
            .secondary_actant
            .filter(|secondary| *secondary != observation.actant)
        {
            let pair = ordered_pair(observation.actant, secondary);
            let state = self.violence.entry(pair).or_default();
            drafts.extend(process_violence(state, pair, &observation, &self.config));
        }

        drafts
            .into_iter()
            .map(|draft| self.materialize(draft))
            .collect()
    }

    fn materialize(&mut self, draft: EventDraft) -> Event {
        self.sequence += 1;
        Event {
            id: format!("evt_{}_{}", draft.detected_at_ms, self.sequence),
            event_type: draft.kind,
            status: draft.status,
            ts_start_ms: draft.ts_start_ms,
            ts_end_ms: draft.detected_at_ms,
            detected_at_ms: draft.detected_at_ms,
            source_id: self.source_id.clone(),
            actants: draft
                .actants
                .into_iter()
                .map(|slot| Actant {
                    actant_type: ActantType::Person,
                    label: slot.label(),
                })
                .collect(),
            evidence: draft.evidence,
            risk: Risk {
                level: draft.risk_level,
                category: draft.risk_category,
                reason: draft.risk_reason,
            },
            confidence: draft.confidence,
            detector: draft.detector.into(),
            latency_ms: draft.detected_at_ms.saturating_sub(draft.ts_start_ms),
        }
    }
}

fn process_fall(
    track: &mut TrackState,
    observation: &Observation,
    config: &EventConfig,
) -> Vec<EventDraft> {
    let mut events = Vec::new();
    let at = observation.at_ms;

    if observation.pose == Pose::Upright {
        track.reset_fall();
        track.last_upright_at = Some(at);
        return events;
    }

    let descent = score(observation.rapid_descent_score) >= config.rapid_descent_threshold;
    if descent
        && track
            .last_upright_at
            .is_some_and(|upright| at.saturating_sub(upright) <= config.fall_transition_ms)
    {
        track.fall_upright_at = track.last_upright_at;
        track.descent_at = Some(at);
        track.horizontal_since = None;
        track.fall_candidate_emitted = false;
        track.fall_confirmed = false;
    }

    let Some(descent_at) = track.descent_at else {
        return events;
    };
    let upright_at = track.fall_upright_at.unwrap_or(descent_at);
    if at.saturating_sub(descent_at) > config.fall_candidate_expiry_ms {
        track.reset_fall();
        return events;
    }

    if matches!(observation.pose, Pose::Seated | Pose::Crouched) {
        // A normal sit/crouch after vertical motion is not a fall.
        track.reset_fall();
        return events;
    }
    if !matches!(observation.pose, Pose::Horizontal | Pose::Prone) {
        // Unknown is not visible proof that the person stayed prone. Break the
        // continuous dwell clock instead of stitching evidence across a tracking
        // miss; a later horizontal/prone sample must start a fresh dwell period.
        track.horizontal_since = None;
        return events;
    }
    if track.horizontal_since.is_none()
        && !track.fall_candidate_emitted
        && at.saturating_sub(descent_at) > config.fall_transition_ms
    {
        // A horizontal pose first appearing long after the descent is not the same
        // visible transition and must not be stitched into a fall.
        track.reset_fall();
        return events;
    }

    let horizontal_since = *track.horizontal_since.get_or_insert(at);
    let fast_impact = score(observation.impact_score) >= config.impact_threshold
        && at.saturating_sub(descent_at) <= config.fall_transition_ms;

    if fast_impact && !track.fall_confirmed {
        track.fall_candidate_emitted = true;
        track.fall_confirmed = true;
        events.push(fall_event(
            EventStatus::Confirmed,
            upright_at,
            descent_at,
            at,
            observation.actant,
            true,
        ));
        return events;
    }

    if !track.fall_candidate_emitted {
        track.fall_candidate_emitted = true;
        events.push(fall_event(
            EventStatus::Candidate,
            upright_at,
            descent_at,
            at,
            observation.actant,
            false,
        ));
    }

    if !track.fall_confirmed && at.saturating_sub(horizontal_since) >= config.fall_prone_confirm_ms
    {
        track.fall_confirmed = true;
        let mut event = fall_event(
            EventStatus::Confirmed,
            upright_at,
            descent_at,
            at,
            observation.actant,
            false,
        );
        event.evidence.push(fast_evidence(
            EvidenceKind::SustainedProne,
            at,
            "一人維持水平或倒臥姿態達確認時間",
        ));
        event.confidence = 0.88;
        events.push(event);
    }
    events
}

fn fall_event(
    status: EventStatus,
    upright_at: u64,
    started_at: u64,
    detected_at: u64,
    actant: ActantSlot,
    with_impact: bool,
) -> EventDraft {
    let mut evidence = vec![
        fast_evidence(
            EvidenceKind::UprightPose,
            upright_at,
            "先前可見一人呈站立姿態",
        ),
        fast_evidence(EvidenceKind::RapidDescent, started_at, "一人由站立快速下降"),
        fast_evidence(
            EvidenceKind::HorizontalOrPronePose,
            detected_at,
            "下降後可見一人呈水平或倒臥姿態",
        ),
    ];
    if with_impact {
        evidence.push(fast_evidence(
            EvidenceKind::ImpactMotion,
            detected_at,
            "同一時窗偵測到高強度撞擊動作",
        ));
    }
    EventDraft {
        kind: EventKind::Fall,
        status,
        ts_start_ms: started_at,
        detected_at_ms: detected_at,
        actants: vec![actant],
        evidence,
        risk_level: if status == EventStatus::Confirmed {
            RiskLevel::High
        } else {
            RiskLevel::Low
        },
        risk_category: RiskCategory::Fall,
        risk_reason: Some(if with_impact {
            "一人由站立快速下降後呈水平/倒臥姿態，且同時可見高強度撞擊動作".into()
        } else {
            "一人由站立快速下降後呈水平/倒臥姿態".into()
        }),
        confidence: if status == EventStatus::Confirmed {
            0.93
        } else {
            0.65
        },
        detector: "fall_pose_motion_v1",
    }
}

fn process_zone_exit(
    track: &mut TrackState,
    observation: &Observation,
    config: &EventConfig,
) -> Option<EventDraft> {
    if !observation.zone_exit
        || track.last_zone_exit_at.is_some_and(|last| {
            observation.at_ms.saturating_sub(last) < config.zone_exit_cooldown_ms
        })
    {
        return None;
    }
    track.last_zone_exit_at = Some(observation.at_ms);
    Some(EventDraft {
        kind: EventKind::ZoneExit,
        status: EventStatus::Confirmed,
        ts_start_ms: observation.at_ms,
        detected_at_ms: observation.at_ms,
        actants: vec![observation.actant],
        evidence: vec![fast_evidence(
            EvidenceKind::ZoneBoundaryCrossing,
            observation.at_ms,
            "一人可見地穿越已設定的畫面區域邊界",
        )],
        // A visible departure is an objective event, not automatically a hazard.
        risk_level: RiskLevel::None,
        risk_category: RiskCategory::None,
        risk_reason: None,
        confidence: 0.90,
        detector: "zone_exit_v1",
    })
}

fn process_violence(
    state: &mut ViolenceState,
    pair: (ActantSlot, ActantSlot),
    observation: &Observation,
    config: &EventConfig,
) -> Vec<EventDraft> {
    let at = observation.at_ms;
    state.last_seen_at = at;
    while state
        .hits
        .front()
        .is_some_and(|first| at.saturating_sub(*first) > config.violence_window_ms)
    {
        state.hits.pop_front();
    }
    if state.hits.is_empty() {
        state.candidate_emitted = false;
    }
    if at < state.cooldown_until {
        return Vec::new();
    }

    let qualifies = observation.visible_people >= 2
        && score(observation.motion_score) >= config.violence_motion_threshold
        && score(observation.close_contact_score) >= config.violence_contact_threshold
        && score(observation.strike_score) >= config.violence_strike_threshold;
    if !qualifies {
        return Vec::new();
    }
    if state.hits.back().copied() != Some(at) {
        state.hits.push_back(at);
    }

    let started_at = *state.hits.front().unwrap_or(&at);
    let mut events = Vec::new();
    if state.hits.len() >= config.violence_confirm_hits {
        events.push(violence_event(EventStatus::Confirmed, started_at, at, pair));
        state.hits.clear();
        state.candidate_emitted = false;
        state.cooldown_until = at.saturating_add(config.violence_cooldown_ms);
    } else if state.hits.len() >= config.violence_candidate_hits && !state.candidate_emitted {
        state.candidate_emitted = true;
        events.push(violence_event(EventStatus::Candidate, started_at, at, pair));
    }
    events
}

fn violence_event(
    status: EventStatus,
    started_at: u64,
    detected_at: u64,
    pair: (ActantSlot, ActantSlot),
) -> EventDraft {
    EventDraft {
        kind: EventKind::Violence,
        status,
        ts_start_ms: started_at,
        detected_at_ms: detected_at,
        actants: vec![pair.0, pair.1],
        evidence: vec![
            fast_evidence(
                EvidenceKind::TwoPeopleVisible,
                detected_at,
                "畫面內可見兩人",
            ),
            fast_evidence(EvidenceKind::CloseContact, detected_at, "兩人近距離接觸"),
            fast_evidence(
                EvidenceKind::RepeatedRapidMotion,
                detected_at,
                "短時窗內重複出現高強度動作",
            ),
            fast_evidence(
                EvidenceKind::RepeatedStrikeMotion,
                detected_at,
                "短時窗內重複偵測到打擊型動作特徵",
            ),
        ],
        risk_level: if status == EventStatus::Confirmed {
            RiskLevel::High
        } else {
            RiskLevel::Medium
        },
        risk_category: RiskCategory::Violence,
        risk_reason: Some("畫面內兩人近距離接觸，短時窗內重複可見高強度打擊型動作".into()),
        confidence: if status == EventStatus::Confirmed {
            0.92
        } else {
            0.70
        },
        detector: "violence_motion_v1",
    }
}

fn fast_evidence(kind: EvidenceKind, observed_at_ms: u64, detail: &str) -> Evidence {
    Evidence {
        kind,
        source: EvidenceSource::FastPath,
        observed_at_ms,
        detail: detail.into(),
    }
}

fn score(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(0.0, 1.0)
    } else {
        0.0
    }
}

fn ordered_pair(a: ActantSlot, b: ActantSlot) -> (ActantSlot, ActantSlot) {
    if a <= b {
        (a, b)
    } else {
        (b, a)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine() -> EventEngine {
        EventEngine::new("living_room", EventConfig::default()).unwrap()
    }

    fn observation(at_ms: u64, pose: Pose) -> Observation {
        let mut observation = Observation::new(at_ms, ActantSlot(1));
        observation.pose = pose;
        observation
    }

    #[test]
    fn normal_sit_does_not_become_fall() {
        let mut engine = engine();
        assert!(engine.process(observation(0, Pose::Upright)).is_empty());
        let mut sit = observation(400, Pose::Seated);
        sit.rapid_descent_score = 0.95;
        assert!(engine.process(sit).is_empty());
        assert!(engine.process(observation(2_600, Pose::Seated)).is_empty());
    }

    #[test]
    fn impact_fast_path_confirms_fall_under_one_second() {
        let mut engine = engine();
        engine.process(observation(10_000, Pose::Upright));
        let mut fall = observation(10_600, Pose::Prone);
        fall.rapid_descent_score = 0.95;
        fall.impact_score = 0.90;

        let events = engine.process(fall);
        assert_eq!(events.len(), 1);
        let event = &events[0];
        assert_eq!(event.event_type, EventKind::Fall);
        assert_eq!(event.status, EventStatus::Confirmed);
        assert_eq!(event.risk.level, RiskLevel::High);
        assert!(event.alert_eligible());
        assert!(event.latency_ms < 1_000);
        assert!(event
            .evidence
            .iter()
            .any(|e| e.kind == EvidenceKind::ImpactMotion));
    }

    #[test]
    fn prone_without_impact_is_candidate_then_confirmed_after_dwell() {
        let mut engine = engine();
        engine.process(observation(1_000, Pose::Upright));
        let mut fall = observation(1_500, Pose::Horizontal);
        fall.rapid_descent_score = 0.95;
        let candidate = engine.process(fall);
        assert_eq!(candidate[0].status, EventStatus::Candidate);
        assert!(candidate[0].latency_ms < 1_000);

        assert!(engine.process(observation(2_000, Pose::Prone)).is_empty());
        assert!(engine.process(observation(2_500, Pose::Prone)).is_empty());
        assert!(engine.process(observation(3_000, Pose::Prone)).is_empty());
        let confirmed = engine.process(observation(3_500, Pose::Prone));
        assert_eq!(confirmed.len(), 1);
        assert_eq!(confirmed[0].status, EventStatus::Confirmed);
        assert!(confirmed[0]
            .evidence
            .iter()
            .any(|e| e.kind == EvidenceKind::SustainedProne));
    }

    #[test]
    fn recovery_before_dwell_cancels_fall_candidate() {
        let mut engine = engine();
        engine.process(observation(0, Pose::Upright));
        let mut fall = observation(400, Pose::Horizontal);
        fall.rapid_descent_score = 0.95;
        assert_eq!(engine.process(fall)[0].status, EventStatus::Candidate);
        assert!(engine.process(observation(900, Pose::Upright)).is_empty());
        assert!(engine.process(observation(3_000, Pose::Prone)).is_empty());
    }

    #[test]
    fn unknown_pose_breaks_continuous_prone_dwell() {
        let mut engine = engine();
        engine.process(observation(0, Pose::Upright));
        let mut fall = observation(400, Pose::Horizontal);
        fall.rapid_descent_score = 0.95;
        assert_eq!(engine.process(fall)[0].status, EventStatus::Candidate);
        assert!(engine.process(observation(900, Pose::Prone)).is_empty());

        // A tracking miss must not count as visible sustained-prone evidence.
        assert!(engine.process(observation(1_400, Pose::Unknown)).is_empty());
        assert!(engine.process(observation(1_900, Pose::Prone)).is_empty());
        assert!(engine.process(observation(2_400, Pose::Prone)).is_empty());
        assert!(engine.process(observation(2_900, Pose::Prone)).is_empty());
        assert!(engine.process(observation(3_400, Pose::Prone)).is_empty());

        let confirmed = engine.process(observation(3_900, Pose::Prone));
        assert_eq!(confirmed.len(), 1);
        assert_eq!(confirmed[0].status, EventStatus::Confirmed);
    }

    #[test]
    fn observation_gap_breaks_pose_transition() {
        let mut engine = engine();
        engine.process(observation(0, Pose::Upright));
        let mut stale_fall = observation(2_000, Pose::Prone);
        stale_fall.rapid_descent_score = 1.0;
        stale_fall.impact_score = 1.0;
        assert!(engine.process(stale_fall).is_empty());
    }

    #[test]
    fn late_horizontal_pose_is_not_stitched_to_old_descent() {
        let mut engine = engine();
        engine.process(observation(0, Pose::Upright));
        let mut descent = observation(400, Pose::Unknown);
        descent.rapid_descent_score = 0.95;
        assert!(engine.process(descent).is_empty());
        assert!(engine.process(observation(900, Pose::Unknown)).is_empty());
        assert!(engine.process(observation(1_500, Pose::Prone)).is_empty());
    }

    #[test]
    fn visible_zone_exit_is_neutral_and_deduplicated() {
        let mut engine = engine();
        let mut exit = observation(1_000, Pose::Upright);
        exit.zone_exit = true;
        let events = engine.process(exit.clone());
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event_type, EventKind::ZoneExit);
        assert_eq!(events[0].risk.level, RiskLevel::None);
        assert!(events[0].risk.reason.is_none());
        assert!(!events[0].alert_eligible());

        exit.at_ms = 1_100;
        assert!(engine.process(exit).is_empty());
    }

    #[test]
    fn isolated_motion_does_not_become_violence() {
        let mut engine = engine();
        let mut hit = observation(1_000, Pose::Upright);
        hit.secondary_actant = Some(ActantSlot(2));
        hit.visible_people = 2;
        hit.motion_score = 1.0;
        hit.close_contact_score = 1.0;
        hit.strike_score = 1.0;
        assert!(engine.process(hit).is_empty());
    }

    #[test]
    fn repeated_visible_hits_confirm_violence_under_one_second() {
        let mut engine = engine();
        let mut statuses = Vec::new();
        for at in [1_000, 1_200, 1_400, 1_600] {
            let mut hit = observation(at, Pose::Upright);
            hit.secondary_actant = Some(ActantSlot(2));
            hit.visible_people = 2;
            hit.motion_score = 0.95;
            hit.close_contact_score = 0.90;
            hit.strike_score = 0.95;
            statuses.extend(engine.process(hit));
        }
        assert_eq!(statuses.len(), 2);
        assert_eq!(statuses[0].status, EventStatus::Candidate);
        assert_eq!(statuses[1].status, EventStatus::Confirmed);
        assert!(statuses[1].latency_ms < 1_000);
    }

    #[test]
    fn violence_hits_from_different_role_pairs_are_not_combined() {
        let mut engine = engine();
        for (at, second) in [(1_000, 2), (1_200, 3), (1_400, 2), (1_600, 3)] {
            let mut hit = observation(at, Pose::Upright);
            hit.secondary_actant = Some(ActantSlot(second));
            hit.visible_people = 2;
            hit.motion_score = 0.95;
            hit.close_contact_score = 0.90;
            hit.strike_score = 0.95;
            let events = engine.process(hit);
            assert!(events
                .iter()
                .all(|event| event.status != EventStatus::Confirmed));
        }
    }

    #[test]
    fn vlm_corroboration_never_promotes_or_changes_risk() {
        let mut engine = engine();
        engine.process(observation(0, Pose::Upright));
        let mut fall = observation(400, Pose::Horizontal);
        fall.rapid_descent_score = 0.95;
        let mut event = engine.process(fall).remove(0);
        let original = (
            event.status,
            event.risk.level,
            event.confidence,
            event.latency_ms,
        );

        assert!(event.add_vlm_corroboration(7_000, "畫面中可見一人倒臥於地面。"));
        assert_eq!(
            (
                event.status,
                event.risk.level,
                event.confidence,
                event.latency_ms
            ),
            original
        );
        assert_eq!(event.evidence.last().unwrap().source, EvidenceSource::Vlm);
        let count = event.evidence.len();
        assert!(event.add_vlm_corroboration(8_000, "畫面中仍可見一人倒臥。"));
        assert_eq!(event.evidence.len(), count);
        assert!(event.evidence.last().unwrap().detail.contains("仍可見"));
        assert!(!event.alert_eligible());
    }

    #[test]
    fn serialized_event_matches_cross_language_contract_shape() {
        let mut engine = engine();
        let mut exit = observation(1_000, Pose::Upright);
        exit.zone_exit = true;
        let event = engine.process(exit).remove(0);
        let json = event.to_json().unwrap();
        let payload: serde_json::Value = serde_json::from_str(&json).unwrap();

        assert_eq!(payload["type"], "zone_exit");
        assert_eq!(payload["status"], "confirmed");
        assert_eq!(payload["actants"][0]["type"], "person");
        assert_eq!(payload["actants"][0]["label"], "person_1");
        assert_eq!(payload["risk"]["level"], "none");
        assert!(payload.get("kind").is_none());
        assert!(payload.get("risk_level").is_none());

        let fixture: serde_json::Value =
            serde_json::from_str(include_str!("../../schemas/examples/event-zone-exit.json"))
                .unwrap();
        assert_eq!(payload, fixture);
    }

    #[test]
    fn invalid_scores_and_out_of_order_observations_are_ignored_safely() {
        let mut engine = engine();
        engine.process(observation(1_000, Pose::Upright));
        let mut invalid = observation(1_200, Pose::Prone);
        invalid.rapid_descent_score = f32::NAN;
        invalid.impact_score = f32::INFINITY;
        assert!(engine.process(invalid).is_empty());
        assert!(engine.process(observation(900, Pose::Prone)).is_empty());
    }

    #[test]
    fn invalid_configuration_fails_before_monitoring_starts() {
        let config = EventConfig {
            violence_strike_threshold: f32::NAN,
            ..EventConfig::default()
        };
        assert!(EventEngine::new("living_room", config).is_err());
        let zero_threshold = EventConfig {
            violence_motion_threshold: 0.0,
            ..EventConfig::default()
        };
        assert!(EventEngine::new("living_room", zero_threshold).is_err());
        let single_hit_confirmation = EventConfig {
            violence_candidate_hits: 1,
            violence_confirm_hits: 1,
            ..EventConfig::default()
        };
        assert!(EventEngine::new("living_room", single_hit_confirmation).is_err());
        assert!(EventEngine::new("   ", EventConfig::default()).is_err());
    }
}
