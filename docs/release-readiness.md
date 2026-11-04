# APK release readiness (EF-REPO-JUDGE-01, 2026-11-01)

No public release exists. This report states exactly what is missing
so the owner can close it. Nothing here publishes anything.

## Current build inventory (all NOT releasable)

| Build | Identity | Debuggable | Verdict |
|---|---|---|---|
| Dev (`debug`) | `ai.edgefit.diag` | Yes | Never distributed |
| Benchmark (`benchmark`) | `ai.edgefit.diag.bench`, v0.2.0 | Yes | Owner testing only |
| `publicPreview` (2026-11-04) | `ai.edgefit.diag`, v0.2.1-preview.1, code 3 | No | **Unsigned inspection build only — SIGNING BLOCKED, not published** |

Reference: [release-and-distribution](edgefit-release-and-distribution.md)
gates 1–7.

## Gate status

| Gate | Status | Evidence / blocker |
|---|---|---|
| 1. Clean-checkout offline build | PARTIAL (unsigned) | Variant builds (online; offline cache lacks lint-gradle); no signed build without owner key |
| 2. Full suites green | JVM GREEN 893/893 | Commit `52cd5b6`; Python contract suites not run (no host runtime) |
| 3. Secret scan (tree + APK strings) | PASS (unsigned APK) | Tree + 123k APK strings, 0 hits; must re-run on signed bytes |
| 4. Non-debuggable, no backdoors/keys | PASS (unsigned APK) | No `application-debuggable` flag; `BenchConfig` is bounds-only; no keys |
| 5. Contract suites validate RC exports | N/A | No signed release candidate exists |
| 6. Physical install/launch/profile/benchmark/save + upgrade | BLOCKED | Unsigned APK cannot install; S22 debug install protected; first public release has no prior release to upgrade from |
| 7. Release notes (tested vs not) | NOT WRITTEN | Depends on gates 1–6 |

## Minimum remaining path (owner-gated)

1. Variant `publicPreview` implemented on
   `feature/ef-android-global-01-2026-11-01` (`52cd5b6`).
   Owner now creates/holds the signing key **off-repo** (never
   generated or stored by any agent) and adds the four
   `releaseKeystore*` props to `~/.gradle/gradle.properties`.
2. Rebuild signed (`:app:assemblePublicPreview`), re-run secret
   scan + badging on the signed bytes, publish SHA-256 +
   signing-certificate fingerprint.
3. Build, run secret scan over tree + APK, publish SHA-256 +
   signing-certificate fingerprint.
4. Owner installs on S22, runs install → profile → benchmark →
   save smoke test; records version/ABI.
5. Write release notes with tested/untested + known limits; create
   GitHub Release with signed APK + checksums + source tag link.
6. Keep direct APK as the preview channel; Play follows separately
   ([Play note](play-distribution-note.md)).

## What judges can install today

Nothing public. The honest statement for the submission: *"Direct
GitHub distribution is the planned preview channel; no signed
public-preview APK exists yet. Current APKs are debuggable
owner-testing builds and are explicitly not release candidates."*

## Safety constraints honored

No debug APK published as a release; no signing key generated or
exposed; registered study app untouched; no sessions cleared;
no separate approval gate bypassed.
