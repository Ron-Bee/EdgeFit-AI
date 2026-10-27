# EdgeFitDiag Android build and validation

EF-ANDROID-01 collector app: `android/` is the Gradle project root
(application id `ai.edgefit.diag`, zero runtime dependencies, zero
permissions). Collects real device capabilities only; it does not run an
LLM, benchmark anything, use the network, or upload data.

## Build requirements

- Android Studio with bundled JBR (JDK 17) — installed on owner workstation.
- Android SDK with `platforms/android-35` and `build-tools/35.0.0` —
  installed on owner workstation (`%LOCALAPPDATA%/Android/Sdk`).
- First Gradle sync needs network access (downloads AGP 8.7.3,
  Kotlin 2.0.21, JUnit 4.13.2).
- No `gradlew` wrapper jar is shipped; open `android/` in Android Studio
  (it syncs with its bundled Gradle) or generate the wrapper locally.
  CLI equivalent with a Gradle 8.9 install: `gradle :app:assembleDebug`.
- Compile/target SDK 35 with minSdk 26. The app runs on the Android 16
  (API 36) Galaxy S22 Ultra without API-36 symbols; bump to 36 with a
  newer AGP only if API-36-only behavior is ever needed.

## Test status

| Suite | Runner | Status |
|---|---|---|
| `scripts/test_ef_android_01.py` contract tests | agent, CPython | RUN (see handoff) |
| `:app:testDebugUnitTest` JVM serializer tests | needs JDK/Gradle | NOT RUN — no JVM in agent environment |
| Device install / collect / export / pull | physical phone | PHONE REQUIRED, not performed |

## PHONE REQUIRED — install, collect, verify

On the Samsung Galaxy S22 Ultra (Android 16):

1. Enable Developer options and USB debugging; connect via USB.
2. `adb devices` — authorize the RSA prompt on the phone.
3. Install: Android Studio Run on the `app` configuration, or
   `adb install android/app/build/outputs/apk/debug/app-debug.apk`.
4. Open EdgeFitDiag, confirm the on-screen summary shows real values
   (samsung model, Android 16, API 36, arm64 ABIs), then tap
   **Collect & Export Profile** and choose a destination such as
   Downloads in the system file picker (explicit action; nothing is
   written or uploaded without the tap, and cancelling writes nothing).
5. Pull the file from the chosen location, e.g.:
   `adb pull /sdcard/Download/edgefit-profile-<stamp>.json .`
6. Validate on the workstation:
   `py -3 scripts/ef_android_profile_01.py edgefit-profile-<stamp>.json`
   Expected: `VALID: contract v1 profile`.
7. Inspect that `unavailable` is `[]` on the S22 Ultra (thermal API 29+
   present, battery extras present) and record the outcome in
   `docs/qualification-status.md`.

Do not import the profile into SQLite in this milestone (see the manual
gate in `docs/android-profile-contract.md`).
