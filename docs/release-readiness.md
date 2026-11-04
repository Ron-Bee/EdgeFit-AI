# APK release readiness (EF-REPO-JUDGE-01, 2026-11-01)

No public release exists. This report states exactly what is missing
so the owner can close it. Nothing here publishes anything.

## Current build inventory (all NOT releasable)

| Build | Identity | Debuggable | Verdict |
|---|---|---|---|
| Dev (`debug`) | `ai.edgefit.diag` | Yes | Never distributed |
| Benchmark (`benchmark`) | `ai.edgefit.diag.bench`, v0.2.0 | Yes | Owner testing only |
| `public-preview` | `ai.edgefit.diag`, non-debuggable, release-signed | n/a | **Does not exist** — architecture PROPOSED only |

Reference: [release-and-distribution](edgefit-release-and-distribution.md)
gates 1–7.

## Gate status

| Gate | Status | Evidence / blocker |
|---|---|---|
| 1. Clean-checkout offline build | UNRUN for release | No `public-preview` variant exists to build |
| 2. Full suites green | HOST GREEN (debug/benchmark) | 634 JVM + Python + 38 cloud; must re-run against the release candidate |
| 3. Secret scan (tree + APK strings) | UNRUN for release | No candidate APK; tree scan clean per this task's check (see report) |
| 4. Non-debuggable, no backdoors/keys | BLOCKED | No candidate; `BenchConfig` review + manifest flags pending |
| 5. Contract suites validate RC exports | UNRUN | Needs release-candidate exports from S22 |
| 6. Physical install/launch/profile/benchmark/save + upgrade | UNRUN | Needs owner install of signed APK on S22 |
| 7. Release notes (tested vs not) | NOT WRITTEN | Depends on gates 1–6 |

## Minimum remaining path (owner-gated)

1. Owner approves `public-preview` variant + holds signing key
   **off-repo** (never generated or stored by any agent).
2. Implement variant per release-and-distribution §Build variants
   (separately authorized code change).
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
