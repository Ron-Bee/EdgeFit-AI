"""EdgeFit EF-ANDROID-01: offline Android profile contract validator.

Standard library only. Validate-only: checks an exported device profile
against docs/android-profile-contract.md. Never writes to SQLite, never
imports profiles, performs no network I/O.

Usage: python scripts/ef_android_profile_01.py <profile.json>
Exit codes: 0 VALID, 1 INVALID, 2 usage/error.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

CONTRACT = "edgefit-android-profile"
CONTRACT_VERSION = 1
MAX_JSON_BYTES = 65536
MAX_STRING_LEN = 256
MIN_SDK = 21
MAX_SDK = 50
THERMAL_STATUSES = frozenset(
    {
        "none",
        "light",
        "moderate",
        "severe",
        "critical",
        "emergency",
        "shutdown",
        "unknown",
    }
)

_TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
_UNAVAILABLE_RE = re.compile(r"^(device|thermal|battery)\.[a-z_]+$")


def _is_int(value):
    return type(value) is int


def _is_num(value):
    return type(value) in (int, float)


def _check_str(errors, where, value, required=True):
    if value is None:
        if required:
            errors.append("%s: missing" % where)
        return None
    if not isinstance(value, str) or not value:
        errors.append("%s: must be a non-empty string" % where)
        return None
    if len(value) > MAX_STRING_LEN:
        errors.append("%s: exceeds %d chars" % (where, MAX_STRING_LEN))
    return value


def validate_profile(profile):
    """Return a list of contract violations (empty means VALID)."""
    errors = []
    if not isinstance(profile, dict):
        return ["profile: must be a JSON object"]
    for key in profile:
        if key.startswith("x_"):
            continue
        if key not in (
            "contract",
            "contract_version",
            "collector",
            "collected_at_utc",
            "device",
            "thermal",
            "battery",
            "unavailable",
        ):
            errors.append("%s: unknown top-level field" % key)
    if profile.get("contract") != CONTRACT:
        errors.append("contract: must equal %r" % CONTRACT)
    if not _is_int(profile.get("contract_version")):
        errors.append("contract_version: must be an integer")
    elif profile["contract_version"] != CONTRACT_VERSION:
        errors.append(
            "contract_version: unsupported version %r" % profile["contract_version"]
        )
    collector = profile.get("collector")
    if not isinstance(collector, dict):
        errors.append("collector: must be an object")
    else:
        _check_str(errors, "collector.name", collector.get("name"))
        _check_str(errors, "collector.version", collector.get("version"))
    collected = profile.get("collected_at_utc")
    if not isinstance(collected, str) or not _TIMESTAMP_RE.match(collected):
        errors.append("collected_at_utc: must match YYYY-MM-DDTHH:MM:SSZ")
    unavailable = profile.get("unavailable")
    if not isinstance(unavailable, list):
        errors.append("unavailable: must be a list")
        unavailable = []
    else:
        if len(unavailable) > 32:
            errors.append("unavailable: exceeds 32 entries")
        if len(set(unavailable)) != len(unavailable):
            errors.append("unavailable: duplicate entries")
        for name in unavailable:
            if not isinstance(name, str) or not _UNAVAILABLE_RE.match(name):
                errors.append("unavailable: bad entry %r" % (name,))
    _validate_device(errors, profile.get("device"))
    _validate_thermal(errors, profile.get("thermal"), unavailable)
    _validate_battery(errors, profile.get("battery"), unavailable)
    try:
        size = len(json.dumps(profile).encode("utf-8"))
    except (TypeError, ValueError):
        errors.append("profile: not JSON-serializable")
    else:
        if size > MAX_JSON_BYTES:
            errors.append("profile: exceeds %d bytes" % MAX_JSON_BYTES)
    return errors


def _validate_device(errors, device):
    if not isinstance(device, dict):
        errors.append("device: must be an object")
        return
    _check_str(errors, "device.manufacturer", device.get("manufacturer"))
    _check_str(errors, "device.model", device.get("model"))
    _check_str(errors, "device.device_codename", device.get("device_codename"))
    _check_str(errors, "device.android_release", device.get("android_release"))
    sdk = device.get("sdk_int")
    if not _is_int(sdk):
        errors.append("device.sdk_int: must be an integer")
    elif not MIN_SDK <= sdk <= MAX_SDK:
        errors.append("device.sdk_int: unsupported API level %r" % (sdk,))
    abis = device.get("supported_abis")
    if (
        not isinstance(abis, list)
        or not abis
        or len(abis) > 16
        or any(not isinstance(a, str) or not a for a in abis)
    ):
        errors.append("device.supported_abis: must be 1-16 non-empty strings")
    cpu = device.get("logical_processors")
    if not _is_int(cpu) or not 1 <= cpu <= 1024:
        errors.append("device.logical_processors: must be an integer 1-1024")
    total = device.get("total_ram_bytes")
    avail = device.get("avail_ram_bytes")
    if not _is_int(total) or total <= 0:
        errors.append("device.total_ram_bytes: must be a positive integer")
    if not _is_int(avail) or avail < 0:
        errors.append("device.avail_ram_bytes: must be a non-negative integer")
    elif _is_int(total) and avail > total:
        errors.append("device.avail_ram_bytes: exceeds total_ram_bytes")
    if device.get("provenance") != "platform_reported":
        errors.append("device.provenance: must equal 'platform_reported'")


def _validate_thermal(errors, thermal, unavailable):
    if thermal is None:
        if "thermal.status" not in unavailable:
            errors.append("thermal: null without 'thermal.status' in unavailable")
        return
    if not isinstance(thermal, dict):
        errors.append("thermal: must be an object or null")
        return
    if thermal.get("status_name") not in THERMAL_STATUSES:
        errors.append("thermal.status_name: unknown status")
    if not _is_int(thermal.get("status_code")):
        errors.append("thermal.status_code: must be an integer")
    if thermal.get("provenance") != "platform_reported":
        errors.append("thermal.provenance: must equal 'platform_reported'")


def _validate_battery(errors, battery, unavailable):
    if battery is None:
        if "battery.level" not in unavailable:
            errors.append("battery: null without 'battery.level' in unavailable")
        return
    if not isinstance(battery, dict):
        errors.append("battery: must be an object or null")
        return
    level = battery.get("level")
    scale = battery.get("scale")
    if not _is_int(level) or not _is_int(scale) or scale <= 0:
        errors.append("battery: level/scale must be integers with scale > 0")
    else:
        pct = battery.get("pct")
        if not _is_num(pct) or abs(pct - level * 100 / scale) > 0.1:
            errors.append("battery.pct: inconsistent with level/scale")
    temp = battery.get("temperature_c")
    if temp is not None and (not _is_num(temp) or not -50 <= temp <= 100):
        errors.append("battery.temperature_c: out of range")
    volt = battery.get("voltage_v")
    if volt is not None and (not _is_num(volt) or not 0 <= volt <= 6):
        errors.append("battery.voltage_v: out of range")
    tech = battery.get("technology")
    if tech is not None and (not isinstance(tech, str) or len(tech) > MAX_STRING_LEN):
        errors.append("battery.technology: bad string")
    for field in ("status", "health", "plugged"):
        value = battery.get(field)
        if value is not None and not _is_int(value):
            errors.append("battery.%s: must be an integer" % field)
    if battery.get("provenance") != "platform_reported":
        errors.append("battery.provenance: must equal 'platform_reported'")


def main(argv):
    if len(argv) != 2:
        print("usage: ef_android_profile_01.py <profile.json>")
        return 2
    try:
        profile = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print("INVALID: cannot read profile: %s" % exc)
        return 1
    errors = validate_profile(profile)
    if errors:
        print("INVALID:")
        for error in errors:
            print("  - %s" % error)
        return 1
    print("VALID: contract v%d profile" % CONTRACT_VERSION)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
