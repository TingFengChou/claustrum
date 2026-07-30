"""Tests that the domain types and the JSON Schema agree, and that the privacy
and anti-hallucination constraints are enforced by the types rather than by
convention.

Schema/dataclass divergence is the most common invisible bug in this kind of
pipeline, which is why CI checks it on every push.
"""

import dataclasses
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.domain import (  # noqa: E402
    Actant,
    ActantType,
    Ethogram,
    EthogramWindow,
    Kineme,
    Risk,
    RiskCategory,
    RiskLevel,
    SpatialAnchor,
    load_schema,
    validate_kineme_dict,
)

T0 = datetime(2026, 7, 30, 14, 20, 13, tzinfo=timezone.utc)


def a_kineme(**over) -> Kineme:
    base = dict(
        id="kin_20260730_142013_a3f",
        ts_start=T0,
        ts_end=T0 + timedelta(minutes=6),
        source_id="living_room",
        actants=[Actant(ActantType.PERSON, "person_1")],
        action="person_1 sits down on the sofa",
        risk=Risk(),
        confidence=0.72,
        model="gemma-4-12b-it@trtllm-0.1",
        prompt_version="caption_v1",
    )
    base.update(over)
    return Kineme(**base)


class TestSchemaAgreement(unittest.TestCase):
    def test_minimal_kineme_validates(self):
        validate_kineme_dict(a_kineme().to_dict())

    def test_full_kineme_validates(self):
        k = a_kineme(
            actants=[Actant(ActantType.PERSON, "person_1"), Actant(ActantType.ANIMAL, "cat", 2)],
            objects=["dining_table", "cup"],
            location_hint="near the dining table",
            risk=Risk(RiskLevel.MEDIUM, RiskCategory.CHILD_HAZARD, "child_1 is reaching toward the stove"),
            novelty=0.9,
            keyframe_refs=["kf_142013.jpg", "kf_142400.jpg"],
            spatial_anchor=SpatialAnchor("map", 1.2, -3.4, 0.0),
        )
        validate_kineme_dict(k.to_dict())

    def test_schema_enums_match_python_enums(self):
        defs = load_schema("kineme")["$defs"]
        self.assertEqual(
            set(defs["actant"]["properties"]["type"]["enum"]),
            {e.value for e in ActantType},
        )
        self.assertEqual(
            set(defs["risk"]["properties"]["level"]["enum"]),
            {e.value for e in RiskLevel},
        )
        self.assertEqual(
            set(defs["risk"]["properties"]["category"]["enum"]),
            {e.value for e in RiskCategory},
        )

    def test_dataclass_fields_match_schema_properties(self):
        """The real drift guard: field *names* must agree, both directions.

        Enum-value and round-trip checks miss a field added to one side only --
        `additionalProperties: false` catches it only if a test happens to
        serialise that field with a non-null value. Comparing the name sets
        directly is what actually holds the dataclass and the schema together.
        """
        schema = load_schema("kineme")
        cases = [
            (Kineme, schema["properties"]),
            (Actant, schema["$defs"]["actant"]["properties"]),
            (Risk, schema["$defs"]["risk"]["properties"]),
            (SpatialAnchor, schema["$defs"]["spatial_anchor"]["properties"]),
        ]
        for cls, props in cases:
            with self.subTest(cls=cls.__name__):
                self.assertEqual(
                    {f.name for f in dataclasses.fields(cls)},
                    set(props.keys()),
                )

    def test_malformed_timestamp_rejected(self):
        import jsonschema

        bad = a_kineme().to_dict()
        bad["ts_start"] = "30-07-2026 14:20"  # not ISO-8601
        with self.assertRaises(jsonschema.ValidationError):
            validate_kineme_dict(bad)


class TestPrivacyConstraints(unittest.TestCase):
    def test_actant_label_rejects_identity(self):
        for bad in ["Terry Kuo", "person1 Chou", "GrandmaLin", "Person_1"]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                Actant(ActantType.PERSON, bad)

    def test_actant_label_accepts_role_slots(self):
        for good in ["person_1", "cat", "robot_2", "unknown"]:
            with self.subTest(good=good):
                Actant(ActantType.PERSON, good)

    def test_schema_has_no_identity_field(self):
        blob = str(load_schema("kineme")).lower()
        for forbidden in ['"name"', '"face"', '"identity"', '"age"', '"gender"']:
            self.assertNotIn(forbidden, blob)

    def test_redacted_strips_keyframes(self):
        k = a_kineme(keyframe_refs=["kf_1.jpg"])
        self.assertIn("keyframe_refs", k.to_dict())
        self.assertNotIn("keyframe_refs", k.redacted())


class TestAntiHallucinationConstraints(unittest.TestCase):
    def test_risk_requires_visible_evidence(self):
        with self.assertRaises(ValueError):
            Risk(level=RiskLevel.HIGH, category=RiskCategory.FALL)

    def test_risk_none_must_pair_with_category_none(self):
        with self.assertRaises(ValueError):
            Risk(level=RiskLevel.NONE, category=RiskCategory.FALL)

    def test_unclear_action_is_uncertain(self):
        self.assertTrue(a_kineme(action="unclear").is_uncertain)

    def test_low_confidence_is_uncertain(self):
        self.assertTrue(a_kineme(confidence=0.4).is_uncertain)
        self.assertFalse(a_kineme(confidence=0.8).is_uncertain)

    def test_action_length_capped(self):
        with self.assertRaises(ValueError):
            a_kineme(action="x" * 121)


class TestInvariants(unittest.TestCase):
    def test_id_format_enforced(self):
        with self.assertRaises(ValueError):
            a_kineme(id="event-123")

    def test_time_order_enforced(self):
        with self.assertRaises(ValueError):
            a_kineme(ts_start=T0, ts_end=T0 - timedelta(seconds=1))

    def test_confidence_range_enforced(self):
        for bad in (-0.1, 1.1):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                a_kineme(confidence=bad)

    def test_ethogram_serialises(self):
        e = Ethogram(
            date="2026-07-30",
            headline="An ordinary day",
            timeline=[EthogramWindow("08:00-09:00", "person_1 left the house", ["kin_20260730_081200_001"])],
            stats={"vlm_calls": 217},
        )
        self.assertEqual(e.to_dict()["stats"]["vlm_calls"], 217)


if __name__ == "__main__":
    unittest.main()
