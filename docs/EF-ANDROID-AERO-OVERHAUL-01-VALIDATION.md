# EF-ANDROID-AERO-OVERHAUL-01 — Validation Report

Deliverable D. Branch `feature/ef-android-global-01-2026-11-01`
@ `c467a32`. Toolchain: Gradle 8.9 (cached dist) + Android
Studio JBR Java 21, run unsandboxed with owner approval
(the Muse sandbox denies localhost listen, which Gradle
requires even with `--no-daemon`).

## Results (all executed, none assumed)

- Full JVM unit suite `:app:testDebugUnitTest`: **60 suites,
  839 tests, 0 failures, 0 errors, 0 skipped** — includes
  46 new overhaul tests (20 decision, 1 chip mapping, 8
  comparison, 7 stepper, 10 journey).
- `:app:assembleDebug`: **BUILD SUCCESSFUL**. No new
  warnings attributable to this change (one intermediate
  compile break — a brace splice and a paren splice —
  was caught by the compiler and fixed before this run).
- Diff review vs protected list (§11): zero protected
  files touched by this operation; `MainActivity.kt`
  diff (+175/−19) is view construction, view ordering,
  and read-only refresh wiring only. All existing
  handlers, routing, dialogs, and button behaviors are
  byte-identical except: (a) one new local-visibility
  toggle, (b) one new primary button firing the exact
  existing Next-Up handler, (c) one removed redundant
  Settings button whose handler duplicates Contribution
  History (kept in the Global group and the Benchmark
  tab — no lost control).
- Data-honesty checks (unit-tested): no-model and
  unmeasured states carry no figures; estimates always
  labeled ESTIMATE and never shown as measured; deltas
  render stored host errors verbatim (a test pins a
  stored percentage that differs from recomputation);
  approved never renders as executed; receipts never
  render as published; planned journey stages render as
  planned.
- Accessibility pass (static): every new figure carries
  a text label (never color alone); chips, rows, steps,
  and stages set content descriptions; hero names wrap
  to two lines with end-ellipsis; no numerals below 11sp
  except dot numbers (decorative; rows announce state).
- Dark-theme consistency: all colors from `aero_*`
  tokens (plus one additive `aero_warning`); no
  hardcoded colors; existing dark surfaces unchanged.

## Not run, with reason

- Repo offline suites (`validation.yml` Python/PS/Node):
  no interpreter in the sandbox, and zero file overlap
  with this change (they pin JNI/profile/cloud
  contracts in untouched files). Not applicable to this
  diff; CI still governs them.
- On-device validation on the Galaxy S22 Ultra:
  owner-gated per plan §12.5 (no installs, no benchmark
  reruns, no data clears without authorization).
  Delivered: compile + unit + review. Screenshots and
  the demo re-record happen post-approval.

## Regressions

None observed. Two deliberate presentation supersessions,
both same-data: (1) the result-hero one-line delta is
replaced by the labeled comparison block (same session,
same stored maps; the delta line survives in session
cards via the shared helper); (2) the duplicate Settings
review button is removed (same dialog kept reachable).
