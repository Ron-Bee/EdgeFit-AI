# EdgeFit Android profile contract v1

Versioned, extensible contract for real Android device capability profiles
(EF-ANDROID-01). Profiles describe what the platform reports; they never
contain marketing specifications, inferred memory sizes, or benchmark results.

- `contract`: must equal `edgefit-android-profile`.
- `contract_version`: must equal `1` (integer).
- Reference implementation: `android/` collector app.
- Offline validator: `scripts/ef_android_profile_01.py` (validate-only;
  never writes to SQLite).

## Provenance taxonomy

Every reading carries exactly one provenance:

- `platform_reported` — value returned by an Android API at collection time
  (Build, Runtime, ActivityManager, PowerManager, BatteryManager sticky
  intent). This covers all software-collected fields below.
- `unavailable` — the API is absent on this OS version or returned no data.
  Missing data is listed by dotted field name in top-level `unavailable`;
  it is never zero-filled or guessed.
- `direct_measurement` — reserved for external instruments (thermal camera,
  power meter). No EF-ANDROID-01 field uses it.

Derived values (`battery.pct` from level/scale) are computed arithmetically
from platform-reported inputs; the inputs are always preserved alongside.

## Top-level object

| Field | Type | Required | Notes |
|---|---|---|---|
| `contract` | string | yes | `edgefit-android-profile` |
| `contract_version` | integer | yes | `1` |
| `collector` | object | yes | `{name, version}` strings |
| `collected_at_utc` | string | yes | `YYYY-MM-DDTHH:MM:SSZ` |
| `device` | object | yes | Section below |
| `thermal` | object/null | yes | Null only with `thermal.status` in `unavailable` |
| `battery` | object/null | yes | Null only with `battery.level` in `unavailable` |
| `unavailable` | string[] | yes | Dotted names, `section.field`, no duplicates |

Unknown top-level fields are rejected unless prefixed `x_` (extension point).

## device (all `platform_reported`)

| Field | Type | Notes |
|---|---|---|
| `manufacturer` | string | `Build.MANUFACTURER`, ≤256 chars |
| `model` | string | `Build.MODEL`, ≤256 chars |
| `device_codename` | string | `Build.DEVICE`, ≤256 chars |
| `android_release` | string | `Build.VERSION.RELEASE`, e.g. `16` |
| `sdk_int` | integer | `Build.VERSION.SDK_INT`, range 21–50 |
| `supported_abis` | string[] | `Build.SUPPORTED_ABIS`, 1–16 entries |
| `logical_processors` | integer | `Runtime.availableProcessors()`, 1–1024 |
| `total_ram_bytes` | integer | `MemoryInfo.totalMem`, > 0 |
| `avail_ram_bytes` | integer | `MemoryInfo.availMem` live value, 0 ≤ avail ≤ total |
| `provenance` | string | `platform_reported` |

`avail_ram_bytes` is a live reading at `collected_at_utc`, not a static
device specification. No field is inferred from marketing material.

## thermal

`PowerManager.getCurrentThermalStatus()` (API 29+), else null + unavailable.

| Field | Type | Notes |
|---|---|---|
| `status_name` | string | `none|light|moderate|severe|critical|emergency|shutdown|unknown` |
| `status_code` | integer | Raw API integer (preserved for future values) |
| `provenance` | string | `platform_reported` |

## battery

Sticky `ACTION_BATTERY_CHANGED` extras (no permission required).

| Field | Type | Notes |
|---|---|---|
| `level` | integer | `EXTRA_LEVEL`, required if battery non-null |
| `scale` | integer | `EXTRA_SCALE`, required if battery non-null, > 0 |
| `pct` | number | Derived `level*100/scale`, tolerance ±0.1 |
| `status` | integer | Optional raw `EXTRA_STATUS` |
| `health` | integer | Optional raw `EXTRA_HEALTH` |
| `plugged` | integer | Optional raw `EXTRA_PLUGGED` |
| `temperature_c` | number | Optional, `EXTRA_TEMPERATURE`/10, −50…100 |
| `voltage_v` | number | Optional, `EXTRA_VOLTAGE`/1000, 0…6 |
| `technology` | string | Optional, ≤256 chars |
| `provenance` | string | `platform_reported` |

## Bounds

Whole-document UTF-8 JSON must not exceed 65536 bytes. The collector
truncates display strings to 128 chars; the validator rejects strings
over 256 chars.

## Example (illustrative shapes only, not a real device)

```json
{
  "contract": "edgefit-android-profile",
  "contract_version": 1,
  "collector": {"name": "edgefit-diag", "version": "0.1.0"},
  "collected_at_utc": "2026-10-27T00:00:00Z",
  "device": {
    "manufacturer": "samsung", "model": "SM-S908U", "device_codename": "dm3q",
    "android_release": "16", "sdk_int": 36,
    "supported_abis": ["arm64-v8a", "armeabi-v7a", "armeabi"],
    "logical_processors": 8,
    "total_ram_bytes": 12884901888, "avail_ram_bytes": 4294967296,
    "provenance": "platform_reported"
  },
  "thermal": {"status_name": "light", "status_code": 1,
    "provenance": "platform_reported"},
  "battery": {"level": 82, "scale": 100, "pct": 82.0,
    "temperature_c": 31.5, "voltage_v": 4.1,
    "provenance": "platform_reported"},
  "unavailable": []
}
```

## SQLite entry proposal (EF-ANDROID-01: proposal only, no auto-import)

Verified profiles enter EdgeFit SQLite only through this manual gate:

1. Owner exports JSON on the phone (explicit tap) and copies it to the
   workstation (USB/ADB pull). No network upload exists.
2. Owner runs `py -3 scripts/ef_android_profile_01.py <file>`; INVALID
   files stop here.
3. Owner reviews the VALID file, then a future milestone inserts one
   `device_profiles` row mapping `total_ram_bytes/1048576 → total_ram_mb`,
   `avail_ram_bytes/1048576 → available_ram_mb`, `sdk_int → api_level`,
   with a new physical `device_class` and `source` describing provenance.

EF-META-05's approved device-class constraints (`android-8gb-test` only)
are NOT weakened by this milestone. Any new physical device class requires
its own dispatch requalification before the Meta evidence path may serve it.
