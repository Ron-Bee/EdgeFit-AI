# APK release readiness (EF-REPO-JUDGE-01, 2026-11-01)

No public release exists. This report states exactly what is missing
so the owner can close it. Nothing here publishes anything.

## Current build inventory (all NOT releasable)

| Build | Identity | Debuggable | Verdict |
|---|---|---|---|
| Dev (`debug`) | `ai.edgefit.diag` | Yes | Never distributed |
| Benchmark (`benchmark`) | `ai.edgefit.diag.bench`, v0.2.0 | Yes | Owner testing only |
| `publicPreview` (2026-11-04) | `ai.edgefit.diag`, v0.2.1-preview.1, code 3 | No | **Signed 2026-11-04 (cert 0199C6DA..E6688); GitHub pre-release pending** |

Reference: [release-and-distribution](edgefit-release-and-distribution.md)
gates 1–7.

## Gate status

| Gate | Status | Evidence / blocker |
|---|---|---|
| 1. Build from pushed source | PASS (signed) | Owner-signed `:app:assemblePublicPreview` (online; offline cache lacks lint-gradle) |
| 2. Full suites green | JVM GREEN 893/893 | Commit `d4530f3`; Python suites run by CI validation.yml on PR |
| 3. Secret scan (tree + APK strings) | PASS (signed APK) | Tree + 123,743 signed-APK strings, 0 hits |
| 4. Non-debuggable, no backdoors/keys | PASS (signed APK) | apksigner v2 VERIFIED; no debuggable flag; BenchConfig bounds-only; zipalign ok |
| 5. Contract suites validate RC exports | N/A | No hardware run of signed bytes (no spare device available) |
| 6. Physical install/launch/profile/benchmark/save + upgrade | INCOMPLETE | Signed install smoke pending (no spare device); S22 debug install protected (60/102 intact); first release has no prior release to upgrade from |
| 7. Release notes (tested vs not) | DRAFTED | Honest notes with tested/untested; URLs filled at publish |

## Minimum remaining path (owner-gated)

1. DONE 2026-11-04: variant `publicPreview` shipped (`d4530f3`); owner key created off-repo; signed APK built (SHA-256 BC32EC0F.., cert 0199C6DA..). Secret scan + badging + zipalign re-verified on signed bytes.`n2. REMAINING: create the GitHub pre-release (tag v0.2.1-preview.1), upload APK + SHA256SUMS + notes, link README + website.
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
