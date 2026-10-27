"""EdgeFit EF-ANDROID-01 contract tests.

Validates scripts/ef_android_profile_01.py against fixtures covering
serialization shape, missing fields, unsupported APIs, and schema rules.
No network, no device, no SQLite writes.

Run: python scripts/test_ef_android_01.py
"""

from __future__ import annotations

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ef_android_profile_01 as checker

VALID = {
    "contract": "edgefit-android-profile",
    "contract_version": 1,
    "collector": {"name": "edgefit-diag", "version": "0.1.0"},
    "collected_at_utc": "2026-10-27T00:00:00Z",
    "device": {
        "manufacturer": "samsung",
        "model": "SM-S908U",
        "device_codename": "dm3q",
        "android_release": "16",
        "sdk_int": 36,
        "supported_abis": ["arm64-v8a", "armeabi-v7a", "armeabi"],
        "logical_processors": 8,
        "total_ram_bytes": 12884901888,
        "avail_ram_bytes": 4294967296,
        "provenance": "platform_reported",
    },
    "thermal": {
        "status_name": "light",
        "status_code": 1,
        "provenance": "platform_reported",
    },
    "battery": {
        "level": 82,
        "scale": 100,
        "pct": 82.0,
        "temperature_c": 31.5,
        "voltage_v": 4.1,
        "provenance": "platform_reported",
    },
    "unavailable": [],
}


def fresh():
    return copy.deepcopy(VALID)


class ContractShapeTest(unittest.TestCase):
    def test_valid_full_profile(self):
        self.assertEqual(checker.validate_profile(fresh()), [])

    def test_valid_old_device_without_thermal(self):
        profile = fresh()
        profile["thermal"] = None
        profile["device"]["sdk_int"] = 28
        profile["unavailable"] = ["thermal.status"]
        self.assertEqual(checker.validate_profile(profile), [])

    def test_valid_extension_key(self):
        profile = fresh()
        profile["x_note"] = "extension keys are permitted"
        self.assertEqual(checker.validate_profile(profile), [])

    def test_non_object_rejected(self):
        self.assertTrue(checker.validate_profile([1, 2, 3]))

    def test_unknown_top_level_key_rejected(self):
        profile = fresh()
        profile["telemetry"] = {}
        errors = checker.validate_profile(profile)
        self.assertTrue(any("telemetry" in e for e in errors))


class MissingFieldsTest(unittest.TestCase):
    def test_missing_required_top_level(self):
        for key in (
            "contract",
            "contract_version",
            "collector",
            "collected_at_utc",
            "device",
            "thermal",
            "battery",
            "unavailable",
        ):
            with self.subTest(key=key):
                profile = fresh()
                del profile[key]
                self.assertTrue(checker.validate_profile(profile))

    def test_missing_device_fields(self):
        for key in (
            "manufacturer",
            "model",
            "device_codename",
            "android_release",
            "sdk_int",
            "supported_abis",
            "logical_processors",
            "total_ram_bytes",
            "avail_ram_bytes",
        ):
            with self.subTest(key=key):
                profile = fresh()
                del profile["device"][key]
                self.assertTrue(checker.validate_profile(profile))

    def test_null_thermal_requires_unavailable_entry(self):
        profile = fresh()
        profile["thermal"] = None
        errors = checker.validate_profile(profile)
        self.assertTrue(any("thermal.status" in e for e in errors))

    def test_null_battery_requires_unavailable_entry(self):
        profile = fresh()
        profile["battery"] = None
        errors = checker.validate_profile(profile)
        self.assertTrue(any("battery.level" in e for e in errors))


class UnsupportedApiTest(unittest.TestCase):
    def test_sdk_below_minimum_rejected(self):
        profile = fresh()
        profile["device"]["sdk_int"] = 19
        errors = checker.validate_profile(profile)
        self.assertTrue(any("API level" in e for e in errors))

    def test_sdk_far_future_rejected(self):
        profile = fresh()
        profile["device"]["sdk_int"] = 99
        errors = checker.validate_profile(profile)
        self.assertTrue(any("API level" in e for e in errors))

    def test_sdk_wrong_type_rejected(self):
        for bad in ("36", 36.0, True, None):
            with self.subTest(bad=bad):
                profile = fresh()
                profile["device"]["sdk_int"] = bad
                self.assertTrue(checker.validate_profile(profile))

    def test_unknown_thermal_status_rejected(self):
        profile = fresh()
        profile["thermal"]["status_name"] = "melting"
        self.assertTrue(checker.validate_profile(profile))

    def test_unsupported_contract_version_rejected(self):
        profile = fresh()
        profile["contract_version"] = 2
        self.assertTrue(checker.validate_profile(profile))


class SchemaValidationTest(unittest.TestCase):
    def test_bad_timestamp_rejected(self):
        profile = fresh()
        profile["collected_at_utc"] = "yesterday"
        self.assertTrue(checker.validate_profile(profile))

    def test_empty_abis_rejected(self):
        profile = fresh()
        profile["device"]["supported_abis"] = []
        self.assertTrue(checker.validate_profile(profile))

    def test_ram_sanity(self):
        bad_totals = {"total_ram_bytes": 0, "avail_ram_bytes": 12884901889}
        for key, value in bad_totals.items():
            with self.subTest(key=key):
                profile = fresh()
                profile["device"][key] = value
                self.assertTrue(checker.validate_profile(profile))

    def test_battery_pct_must_match_level_scale(self):
        profile = fresh()
        profile["battery"]["pct"] = 12.0
        errors = checker.validate_profile(profile)
        self.assertTrue(any("pct" in e for e in errors))

    def test_battery_ranges(self):
        for key, value in (("temperature_c", 500.0), ("voltage_v", 99.0)):
            with self.subTest(key=key):
                profile = fresh()
                profile["battery"][key] = value
                self.assertTrue(checker.validate_profile(profile))

    def test_unavailable_entries_validated(self):
        profile = fresh()
        profile["unavailable"] = ["thermal.status", "thermal.status"]
        self.assertTrue(checker.validate_profile(profile))
        profile = fresh()
        profile["unavailable"] = ["latitude"]
        self.assertTrue(checker.validate_profile(profile))

    def test_oversize_document_rejected(self):
        profile = fresh()
        profile["x_blob"] = "x" * 70000
        errors = checker.validate_profile(profile)
        self.assertTrue(any("bytes" in e for e in errors))

    def test_provenance_must_be_platform_reported(self):
        profile = fresh()
        profile["device"]["provenance"] = "direct_measurement"
        self.assertTrue(checker.validate_profile(profile))


class CheckerCliTest(unittest.TestCase):
    def run_checker(self, payload):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "profile.json"
            target.write_text(payload, encoding="utf-8")
            script = Path(__file__).resolve().parent / "ef_android_profile_01.py"
            return subprocess.run(
                [sys.executable, str(script), str(target)],
                capture_output=True,
                text=True,
                timeout=60,
            )

    def test_cli_valid(self):
        result = self.run_checker(json.dumps(fresh()))
        self.assertEqual(result.returncode, 0)
        self.assertIn("VALID", result.stdout)

    def test_cli_invalid(self):
        profile = fresh()
        del profile["device"]
        result = self.run_checker(json.dumps(profile))
        self.assertEqual(result.returncode, 1)
        self.assertIn("INVALID", result.stdout)

    def test_cli_usage(self):
        script = Path(__file__).resolve().parent / "ef_android_profile_01.py"
        result = subprocess.run(
            [sys.executable, str(script)], capture_output=True, text=True, timeout=60
        )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
