# EF-ANDROID-AERO-INSTALL-01 — Install Report

Task: EF-ANDROID-AERO-INSTALL-01 (P0). Device: Samsung
Galaxy S22 Ultra (SM-S908U), serial R5CT604K33V,
Android 16 (SDK 36). Window: 2026-11-02 23:04–23:28 -06:00.

## A. Device / B. Package / C. Version / D. Signing

- Installed package (only EdgeFit package on device):
  `ai.edgefit.diag`. No `ai.edgefit.diag.bench`
  present — the prior incident's confusion does not
  apply; `ai.edgefit.diag` is the data-bearing
  package (install.json: install_id
  `95f9195b-eecd-455c-a823-ec1a3778551a`).
- Before: vCode 2 / vName 0.2.0, lastUpdate 11-01
  20:56. The installed APK hashes EXACTLY to the
  known-good backup (`FD31…3081`) — the device was
  running the P0-LIVE-PASS build.
- After: vCode 2 / vName 0.2.0 (same; equal accepted
  for `-r`), lastUpdate 11-02 23:20.
- Signing: identical Android Debug cert both sides
  (SHA-256 `7d5631ea…cc3bac4`). Update permitted and
  performed with plain `adb install -r` (no bypass
  flags, no uninstall, no clear).

## E. Build and installation

- Installed APK: fresh `:app:assembleDebug`
  (ai.edgefit.diag, v2) containing all Aero changes
  plus one launch-crash fix found during this task
  (Decision host initialized after use —
  `UninitializedPropertyAccessException` on first
  launch of the initial build; fixed, full suite
  re-greened 839/839, reinstalled).
- Install result: `Success` (streamed, <1s), twice
  (initial + fixed build). No rejections, no
  workarounds.

## F/G/H. Preservation (before → after, identical)

- Sessions: 43 → 43 files (SES-*.json, 1:1).
- Quarantine: 0 → 0. Evidence: 72 → 72 files
  (36 records). Submitted: 11 → 11 (all SUBMITTED).
- install_id byte-identical; meta_key.bin (76B),
  global token (75B), bench-model.gguf (807MB) all
  present with original mtimes.
- Pre-install `adb backup` (owner-confirmed on
  device): `EdgeFit-Backups\EF-AERO-INSTALL-01-20261102\
  edgefit-diag-backup.ab`, 43,452 bytes, valid
  ANDROID BACKUP v5 deflated stream covering the
  607KB files/ tree (cache/model excluded by
  platform design; preserved in place by `-r`).
- Known-good backup re-verified `FD31…3081` after.

## I. Aero UI physical validation (via hierarchy + shots)

- Home + Decision Card: renders (honest no-model
  empty state on fresh launch — staging is
  per-launch by existing design); reorder,
  collapsible tiles ("SHOW DETAILS"), Meta insight,
  dual SELECT MODEL (card + Next Up, same handler).
- Last Measurement shows durable history
  (SES-ef5f…/15.4 tok/s). Agent stepper: 6 steps
  over active SES-6b19… (Approved/Measured honest).
  Journey strip: 4 nodes over latest SUBMITTED
  record + consent/receipt/server/honesty lines.
  Sessions history, Settings (Meta key saved,
  Global configured, single history button), agent
  dialog all render real data; navigation across
  5 tabs clean; force-stop→relaunch persists;
  no new fatals (only the pre-fix crash in log).
- GAP: comparison-block render needs a benchmark
  run this launch (not authorized) — hero shows
  the honest empty state; block covered by 8 unit
  tests. Estimated-state Decision Card likewise
  needs a staging interaction.

## J. Screenshots

`C:\Users\Ron\Downloads\EF-AERO-INSTALL-01-20261102\`:
01-home-decision, 02-agent-stepper,
03-benchmark-top (journey), 04-benchmark-hero,
05-sessions, 06-settings-top, 07-settings-meta,
08-result-hero-empty, 09-agent-dialog. Genuine
pixels, unaltered. (Agent verified via hierarchy
text; PNGs saved for owner visual review.)

## K. Unresolved issues

1. Comparison-block on-device render unverified
   (needs owner-authorized benchmark run).
2. Initial build crashed on launch (init order);
   fixed + re-verified — installed build is the
   fixed one. Recommend folding the fix into the
   overhaul report's validation notes.

## FINAL STATUS: PARTIAL — INSTALLED, VALIDATION INCOMPLETE

Safe in-place update, all data preserved, UI
verified except the comparison render (gated on an
authorized benchmark run, not on any defect).
