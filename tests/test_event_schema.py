"""Event schema safety and privacy invariants."""

import copy
import json
import unittest
from pathlib import Path

import jsonschema


SCHEMA_PATH = Path(__file__).resolve().parent.parent / "schemas" / "event.schema.json"
SCHEMA = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
VALIDATOR = jsonschema.Draft202012Validator(SCHEMA)


def a_fall_event():
    return {
        "id": "evt_1723075200600_1",
        "type": "fall",
        "status": "confirmed",
        "ts_start_ms": 1723075200000,
        "ts_end_ms": 1723075200600,
        "detected_at_ms": 1723075200600,
        "source_id": "living_room",
        "actants": [{"type": "person", "label": "person_1"}],
        "evidence": [
            {
                "kind": "rapid_descent",
                "source": "fast_path",
                "observed_at_ms": 1723075200400,
                "detail": "一人由站立快速下降",
            },
            {
                "kind": "horizontal_or_prone_pose",
                "source": "fast_path",
                "observed_at_ms": 1723075200600,
                "detail": "下降後可見一人呈倒臥姿態",
            },
        ],
        "risk": {
            "level": "high",
            "category": "fall",
            "reason": "一人由站立快速下降後呈倒臥姿態",
        },
        "confidence": 0.93,
        "detector": "fall_pose_motion_v1",
        "latency_ms": 600,
    }


class TestEventSchema(unittest.TestCase):
    def test_committed_rust_transport_examples_validate(self):
        examples = SCHEMA_PATH.parent / "examples"
        for path in examples.glob("event-*.json"):
            with self.subTest(path=path.name):
                VALIDATOR.validate(json.loads(path.read_text(encoding="utf-8")))

    def test_confirmed_fall_with_fast_path_evidence_validates(self):
        VALIDATOR.validate(a_fall_event())

    def test_non_none_risk_requires_reason(self):
        event = a_fall_event()
        event["risk"]["reason"] = None
        with self.assertRaises(jsonschema.ValidationError):
            VALIDATOR.validate(event)

    def test_vlm_only_cannot_support_non_none_risk(self):
        event = a_fall_event()
        event["evidence"] = [
            {
                "kind": "vlm_visible_description",
                "source": "vlm",
                "observed_at_ms": 1723075207000,
                "detail": "畫面中可見一人倒臥於地面。",
            }
        ]
        with self.assertRaises(jsonschema.ValidationError):
            VALIDATOR.validate(event)

    def test_fall_requires_fall_specific_fast_path_evidence(self):
        event = a_fall_event()
        event["evidence"] = [
            {
                "kind": "zone_boundary_crossing",
                "source": "fast_path",
                "observed_at_ms": 1723075200600,
                "detail": "一人穿越畫面邊界",
            }
        ]
        with self.assertRaises(jsonschema.ValidationError):
            VALIDATOR.validate(event)

    def test_vlm_source_can_only_carry_vlm_description_kind(self):
        event = a_fall_event()
        event["evidence"][0]["source"] = "vlm"
        with self.assertRaises(jsonschema.ValidationError):
            VALIDATOR.validate(event)

    def test_zone_exit_is_not_automatically_a_hazard(self):
        event = a_fall_event()
        event.update(type="zone_exit", status="confirmed")
        event["evidence"] = [
            {
                "kind": "zone_boundary_crossing",
                "source": "fast_path",
                "observed_at_ms": 1723075200600,
                "detail": "一人可見地穿越已設定的畫面區域邊界",
            }
        ]
        with self.assertRaises(jsonschema.ValidationError):
            VALIDATOR.validate(event)

        event["risk"] = {"level": "none", "category": "none", "reason": None}
        VALIDATOR.validate(event)

    def test_role_slot_rejects_identity_like_label(self):
        event = a_fall_event()
        event["actants"][0]["label"] = "Austin_Chou"
        with self.assertRaises(jsonschema.ValidationError):
            VALIDATOR.validate(event)

    def test_contract_contains_no_person_identity_or_frame_payload_fields(self):
        blob = json.dumps(SCHEMA, ensure_ascii=False).lower()
        for forbidden in ['"face"', '"identity"', '"age"', '"gender"', '"keyframe"', '"image"']:
            self.assertNotIn(forbidden, blob)

    def test_additional_event_fields_are_rejected(self):
        event = copy.deepcopy(a_fall_event())
        event["raw_frame"] = "do-not-store.jpg"
        with self.assertRaises(jsonschema.ValidationError):
            VALIDATOR.validate(event)


if __name__ == "__main__":
    unittest.main()
