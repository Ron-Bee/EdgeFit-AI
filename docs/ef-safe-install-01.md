# EF-SAFE-INSTALL-01 operating procedure (preflight slice)

Governs: FR-SAFE-01 (MANDATORY), master architecture §19.
Scope of this slice: fail-closed read-only preflight +
isolated acceptance tests. No APK identity changes, no
launcher changes, no app changes, no backup creation.

## Tool

`scripts/Confirm-EFSafeInstall01.ps1` — portable
PowerShell 5.1, zero dependencies beyond adb/aapt/
apksigner (paths overridable for hermetic testing).

Read-only invocations only: `adb devices`, `getprop
ro.product.model`, `am get-current-user`, `pm list
packages`, `dumpsys package`, `run-as … ls`,
`aapt dump badging`, `apksigner verify
--print-certs`, plus local backup re-hashing. The
tool contains no install/uninstall/clear/rm code
path; the self-test asserts zero mutating
invocations across every case.

Exit codes: 0 PASS (prerequisite only — never
permission to auto-install), 1 BLOCKED (named
reasons), 2 ERROR (unevaluable inputs/tools).

## Gates (all fail-closed)

1. Exact serial in `adb devices` with state `device`;
   `ro.product.model` equals `-ExpectedModel`.
2. `am get-current-user` equals `-User`.
3. `aapt dump badging` package equals
   `-ExpectedPackage` (base/benchmark substitution
   refused); unparseable output blocks.
4. `pm list packages --user` confirms installed
   identity; `dumpsys package` yields installed
   versionCode. Sibling EdgeFit identities are
   inventoried, never assumed from launcher names.
5. `apksigner` SHA-256 equals `-ExpectedCertSha256`.
6. `run-as` session/evidence `*.json` counts for
   the expected package; run-as failure blocks
   (never proceed blind).
7. Data-bearing installs (or destructive intents)
   require `-BackupDir` with `MANIFEST.sha256`
   that re-verifies file by file.
8. `Uninstall`/`ClearData` intents require
   `-AuthorizeDestructive` (+ verified backup when
   data-bearing). `NewIdentityInstall` over a
   data-bearing sibling requires `-AllowNewIdentity`
   acknowledgment. `InstallUpdate` against a
   missing package is refused (wrong intent).
9. Every run prints defined post-install checks
   (identity, counts, version, launch review).
10. Missing tools, params, devices, or parseable
    output blocks or errors; nothing defaults
    to pass.

## Acceptance evidence

`scripts/Test-EFSafeInstall01.ps1`: 17/17 PASS
(mocked utilities, synthetic fixtures, real
hash verification of fixture backups):
happy-path PASS, base/benchmark mismatch,
missing/unauthorized device, wrong user,
signing mismatch, missing/tampered backup,
unreadable inventory, corrupt aapt output,
data-bearing sibling (blocked, then
acknowledged new-identity PASS), destructive
intent with/without authorization, missing
tool ERROR, and whole-run zero-mutation.

## Explicitly unimplemented (future slices)

- Backup CREATION (only verification exists);
  backups are produced today by manual export.
- Fresh-install banner and launcher-label
  distinction (app changes, out of slice scope).
- Physical dry-run against a real device
  (read-only rehearsal; owner-authorized).
- Signed-release variant handling (needs C1/C2).

## Privacy boundaries

Inventory counts files; it never prints session
or evidence contents, keys, or personal data.
Backup verification re-hashes bytes without
displaying them. No network, no uploads.
