# EF-ANDROID-AGENT-HOME-POLISH-03 — Report

Task: EF-ANDROID-AGENT-HOME-POLISH-03 (P0 owner UI
revision). Device: Samsung Galaxy S22 Ultra
(SM-S908U), serial R5CT604K33V, Android 16.
Window: 2026-11-03 00:50–01:18 -06:00.
Baseline: branch `feature/ef-android-global-01-2026-11-01`
@ `c467a32` (Phase 0; git unreadable from the
sandbox at report time — dubious-ownership guard —
so no commit/push was attempted or made).

## A. What changed in Agent Sessions

Root cause of the "merged blue stack": the session
switcher built one full-width themed `Button` per
session with zero margins (`AgentView.kt`), and the
action column did the same — adjacent blue pills
collapsed into one mass.

- Switcher rows are now dark cards (`aero_card_bg`;
  the selected row uses `aero_card_glow_bg`):
  14sp bold "Session SES-…" header plus a small
  status chip (new sky `ChipKind.ACTIVE`, neutral
  FINISHED, amber unexpected), with a 12sp
  secondary sub-line carrying the full status and
  budget use ("finished (budget_exhausted) ·
  attempts 5/5"). 8dp gaps between rows; rows are
  focusable with "selected" content descriptions.
- Detail area: 16sp bold header row (id + chip)
  over a real `aero_card_bg` card with the status
  lines (selectable, spaced); pending-proposal and
  terminal-summary panels became matching cards.
- Experiment cards keep their design with 8dp
  rhythm; the in-card run action is full-width
  with a top gap. Action buttons got 8dp gaps and
  the actions column a 12dp section gap; "New
  Agent Session" kept primary with a section gap.
- No session information removed; mapping helpers
  (`statusChipKind`, `statusChipText`,
  `sessionSubLine`) are pure and unit-tested.

## B. What changed in the Home intro tile

- The generic hero ("EdgeFit AI / Real
  performance") is replaced by a live device
  overview glow card ABOVE Decision: overline
  "LIVE DEVICE OVERVIEW", 22sp device title, three
  `metricTile` cells (AVAIL RAM, STORAGE FREE,
  MODEL), and an honest updated cue.
- Feeds reuse existing telemetry only: 1s home
  ticker (title, available RAM, clock + low-memory
  cue) and dashboard refresh (model state from the
  same readiness derivation, `filesDir.usableSpace`
  via `MemDiag.formatMb`). Placeholders until the
  first refresh; no new data sources.
- Home order now: header, live intro tile,
  Decision, then existing sections untouched.
  `HomeDashboard.hero()` / `AeroUi.heroCard` kept
  (still unit-tested) but no longer used on Home.

## C. Files changed

- `android/app/.../diag/EdgeFitCards.kt`: +ACTIVE
  chip kind (sky accent).
- `android/app/.../diag/AgentView.kt`: card
  switcher, detail/proposal/summary cards,
  spacing, 3 pure helpers.
- `android/app/.../diag/MainActivity.kt`:
  `buildIntroTile()` + 5 refs, ticker/dashboard
  refresh, hero swap (1 block).
- `android/app/src/test/.../AgentViewTest.kt`:
  +3 tests (chip kind/text, sub-line).
- `docs/EF-ANDROID-AGENT-HOME-POLISH-03-REPORT.md`:
  this file. No commits, pushes, or merges.

## D. Test/build results

- `:app:testDebugUnitTest :app:assembleDebug`
  BUILD SUCCESSFUL (1m48s, JBR + Gradle 8.9).
- 865/865 JVM tests, 61 suites, 0 failures
  (baseline 862/862; +3 new).
- CRLF endings preserved on all edited CRLF
  files; all splices assertion-checked.

## E. Physical verification results

- Gates: ai.edgefit.diag v2/0.2.0 both sides,
  identical Debug cert `7d5631ea…cc3bac4`;
  known-good backup re-verified `FD31…3081`.
- Install: plain `adb install -r` Success; launch
  pid alive, no new fatals in logcat.
- Data before → after, identical: sessions 44,
  quarantine 0, evidence files 74, SUBMITTED 12,
  install.json hash `518BF9BB…60160`
  (install_id `95f9195b…`), meta_key.bin 76B,
  token 75B, bench-model.gguf 807694464B, all
  mtimes unchanged. Force-stop → relaunch: new
  pid alive, 44 sessions persist.
- Hierarchy-verified on device: intro tile order
  and live values (title samsung SM-S908U,
  2.2 GB RAM, 5630 MB storage, "No model" honest
  empty state, "Updated 1:12 AM"); all 8 agent
  parents render as cards with ACTIVE + FINISHED
  chips; detail header/card, experiments, and
  terminal summary render real Llama data.
- Note: the first dialog dump showed a partial
  accessibility snapshot; a scrolled re-dump
  confirmed the full content — no code defect.

## F. Screenshot folder path

`C:\Users\Ron\Downloads\EF-ANDROID-AGENT-HOME-POLISH-03\`
(valid PNG magic bytes, byte-safe capture):

- 01-home-intro-tile.png (live tile, verified)
- 04-decision-section.png (Decision header+card)
- 02-agent-sessions-list.png (redesigned list)
- 03-agent-session-detail.png (ACTIVE row, New
  action, detail header/card, experiments)

Each capture was hierarchy-verified in its exact
onscreen state. (An earlier set was found
UTF-16-mangled by PowerShell redirect and was
recaptured via cmd byte-safe redirect.)

## G. Remaining visual concerns

- Agent cannot inspect pixels: final aesthetic
  sign-off (card separation, glow restraint,
  contrast) needs owner eyes on the four PNGs.
- MODEL cell reads "No model" until a per-launch
  staging — honest existing behavior, not a gap.
- Comparison-block render still gated on an
  owner-authorized benchmark run (unchanged).

## FINAL STATUS: PASS

Redesign + intro tile implemented, tested
(865/865), installed in place with all data
byte-identical, verified live on device, and
captured for owner review. Nothing committed.
