"""Domain types for claustrum.

Vocabulary is drawn from ethology, kinesics and actor-network theory. All three
traditions share one methodological commitment: record what was observed, assert
nothing about motive. That commitment is this project's anti-hallucination
discipline, so the types carry it.

    Actant    a scene participant -- a role slot, never an identity
    Kineme    the smallest recorded unit of observed behaviour
    Ethogram  a catalogue of kinemes over a period

These definitions are mirrored by JSON Schema in schemas/. CI validates that the
two do not drift apart; schema/dataclass divergence is the most common invisible
bug in this kind of pipeline.
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any

SCHEMA_DIR = Path(__file__).resolve().parent.parent / "schemas"

_LABEL_RE = re.compile(r"^[a-z_]+(_[0-9]+)?$")
_ID_RE = re.compile(r"^kin_[0-9]{8}_[0-9]{6}_[0-9a-f]{3,8}$")


class ActantType(str, Enum):
    PERSON = "person"
    ANIMAL = "animal"
    ROBOT = "robot"
    VEHICLE = "vehicle"
    UNKNOWN = "unknown"


class RiskLevel(str, Enum):
    NONE = "none"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class RiskCategory(str, Enum):
    """Closed enumeration, deliberately.

    An open string field here makes the L2 rules engine unmaintainable: every
    new phrasing the model invents becomes a rule that silently never matches.
    """

    NONE = "none"
    FALL = "fall"
    FIRE_SMOKE = "fire_smoke"
    WATER_LEAK = "water_leak"
    INTRUSION = "intrusion"
    CHILD_HAZARD = "child_hazard"
    MEDICAL = "medical"
    UNKNOWN = "unknown"


@dataclass(frozen=True)
class Actant:
    """A participant in an observed scene.

    `label` is a role slot -- person_1, cat, robot_1 -- and MUST NOT encode
    identity. This project performs no face recognition and no identity
    attribution; the constraint is expressed here as a type so that it cannot be
    quietly relaxed downstream.
    """

    type: ActantType
    label: str
    count: int = 1

    def __post_init__(self) -> None:
        if not _LABEL_RE.match(self.label):
            raise ValueError(
                f"actant label {self.label!r} must be a lowercase role slot "
                "such as 'person_1' or 'cat', not an identity"
            )
        if self.count < 1:
            raise ValueError("actant count must be >= 1")


@dataclass(frozen=True)
class Risk:
    level: RiskLevel = RiskLevel.NONE
    category: RiskCategory = RiskCategory.NONE
    reason: str | None = None

    def __post_init__(self) -> None:
        if self.level is not RiskLevel.NONE and not self.reason:
            raise ValueError(
                "a non-none risk level requires a reason describing evidence "
                "visible in frame; anticipated risk does not qualify"
            )
        if self.level is RiskLevel.NONE and self.category is not RiskCategory.NONE:
            raise ValueError("risk level 'none' must pair with category 'none'")


@dataclass(frozen=True)
class SpatialAnchor:
    """Map coordinates. Populated only in robot deployments.

    Attaching these to kinemes is what turns the event log into a semantic map
    rather than camera subtitles.
    """

    frame_id: str
    x: float
    y: float
    z: float = 0.0


@dataclass
class Kineme:
    """One observed behaviour over one time span."""

    id: str
    ts_start: datetime
    ts_end: datetime
    source_id: str
    actants: list[Actant]
    action: str
    risk: Risk
    confidence: float
    model: str
    prompt_version: str
    objects: list[str] = field(default_factory=list)
    location_hint: str | None = None
    novelty: float = 0.0
    keyframe_refs: list[str] = field(default_factory=list)
    spatial_anchor: SpatialAnchor | None = None

    def __post_init__(self) -> None:
        if not _ID_RE.match(self.id):
            raise ValueError(f"kineme id {self.id!r} must match kin_YYYYMMDD_HHMMSS_<hex>")
        if self.ts_end < self.ts_start:
            raise ValueError("ts_end precedes ts_start")
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("confidence must be in [0, 1]")
        if not 0.0 <= self.novelty <= 1.0:
            raise ValueError("novelty must be in [0, 1]")
        if len(self.action) > 120:
            raise ValueError("action must be one short sentence (<= 120 chars)")

    @property
    def is_uncertain(self) -> bool:
        return self.confidence < 0.5 or self.action.strip().lower() == "unclear"

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["ts_start"] = self.ts_start.isoformat()
        d["ts_end"] = self.ts_end.isoformat()
        if self.spatial_anchor is None:
            d.pop("spatial_anchor")
        return d

    def redacted(self) -> dict[str, Any]:
        """Representation safe to send beyond the sensor node.

        Strips keyframe references. The two-node topology (ADR-0003) means the
        query surface has no path to frames anyway; this is defence in depth,
        not the primary control.
        """
        d = self.to_dict()
        d.pop("keyframe_refs", None)
        return d


@dataclass
class EthogramWindow:
    window: str
    summary: str
    kineme_ids: list[str]


@dataclass
class Anomaly:
    description: str
    kineme_ids: list[str]


@dataclass
class Ethogram:
    """A catalogue of kinemes over a period -- the system's primary output.

    Anomalies are established by comparison against the subject's own recent
    history, not against absolute rules. That is what separates an observer with
    memory from an alarm with a threshold.
    """

    date: str
    headline: str
    timeline: list[EthogramWindow] = field(default_factory=list)
    anomalies: list[Anomaly] = field(default_factory=list)
    stats: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def load_schema(name: str) -> dict[str, Any]:
    return json.loads((SCHEMA_DIR / f"{name}.schema.json").read_text())


_kineme_validator_cache: Any = None


def _kineme_validator() -> Any:
    """A schema validator that actually enforces `format: date-time`.

    `jsonschema.validate()` silently ignores `format` unless a format checker is
    supplied, and its built-in date-time checker is itself a no-op unless an
    optional RFC-3339 package is installed. So we register a checker backed by
    `datetime.fromisoformat` -- stdlib only, no new dependency, and it genuinely
    rejects malformed timestamps rather than waving them through.
    """
    global _kineme_validator_cache
    if _kineme_validator_cache is None:
        import jsonschema  # imported lazily so core stays dependency-light

        checker = jsonschema.FormatChecker()

        @checker.checks("date-time", raises=ValueError)
        def _is_date_time(value: object) -> bool:
            if isinstance(value, str):
                datetime.fromisoformat(value)
            return True

        _kineme_validator_cache = jsonschema.Draft202012Validator(
            load_schema("kineme"), format_checker=checker
        )
    return _kineme_validator_cache


def validate_kineme_dict(payload: dict[str, Any]) -> None:
    """Validate against the JSON Schema, timestamps included. Requires `jsonschema`."""
    _kineme_validator().validate(payload)
