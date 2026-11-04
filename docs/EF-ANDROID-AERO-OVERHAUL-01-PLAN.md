# EF-ANDROID-AERO-OVERHAUL-01 — UI-Only Implementation Plan

- Operation: EF-ANDROID-AERO-OVERHAUL-01 (UI/UX changes ONLY)
- Branch: `feature/ef-android-global-01-2026-11-01` @ `c467a32` (matches known-good APK)
- APK backup: verified `FD31…3081`, untouched
- Authored: 2026-11-02, before any implementation edit

## 0. Binding corrections to the brief's assumptions

1. **The app is NOT Compose.** All UI is programmatic Android Views
   (`LinearLayout`/`TextView`/`Button` built in Kotlin) plus `AlertDialog`
   shells. "Compose layout changes" in §14 is therefore read as
   "programmatic-View layout changes". No Compose migration (that would be
   a rewrite, not a restyle).
2. **No Material3 / Navigation-component migration.** `ef-ux-01-design.md`
   §D flags that migration as new third-party code needing owner
   acknowledgment; this operation introduces no new dependencies. All work
   extends the existing Aero Views kit.
3. **Dark-first already.** The shipped Aero token set is a dark surfaces
   system; the design doc's light-theme proposal was superseded in
   implementation. This plan follows the existing dark tokens.
4. **Navigation stays 5-tab** (`NavTab`: Home/Benchmark/Sessions/Agent/
   Settings) with identical routing; only in-screen hierarchy changes.

## 1. Current Android UI structure

- Single `MainActivity` (~190 KB) owns tab state, view swapping, dialogs,
  and all behavior wiring. Destinations are built by local helpers
  (`showMain`, `selectTab`, `refreshDashboard`, `refreshResultHero`,
  `refreshSessionsList`, `refreshAgentWorkspace`, `refreshSettingsLines`).
- Pure derivation objects (never touch views/storage): `HomeDashboard`,
  `InsightCards` (Section/Row/Accent + `tps`/`signedPct` formatters),
  `AeroResult` (Origin: MEASURED/PREDICTED/ESTIMATED/UNAVAILABLE/
  NOT_COMPARABLE), `AeroAgent` (CardKind), `AeroSessions`,
  `AeroContribute`, `AeroSettings`, `ModelRecommend` (7 Kinds),
  `ModelSuitability` (4 statuses + reasons), `SessionComparison`.
- Rendering kit: `AeroUi` (`AeroShell.kt`) — colors, `card`/`glowCard`,
  `heroCard`, `metricTile`/`tileRow`, `sectionHeader`, primary/secondary/
  ghost button styles; `AeroTopBar`, `AeroDockView` (5 destinations).
- Dialogs: `CardDialog` (InsightCards sections), `MetaView` (BYOK +
  prediction), `ModelCatalogView`, `BenchWorkloadView`, agent/bench/
  contribution dialogs. Uncommitted Aero Phase-2 edits already migrate
  `CardDialog`/`MetaView`/`ModelCatalogView` to `Theme_EdgeFit_Aero_Dialog`
  + `AeroUi` styling — preserved and built upon, not redone.
- Resources: `aero_*` colors/dimens/strings/themes/drawables are additive
  and complete for cards, buttons, dock, chips.

## 2. Existing Aero visual components (reuse, don't duplicate)

`AeroUi.card/glowCard/heroCard/metricTile/tileRow/sectionHeader/bodyText/
captionText/cardTitle`, button stylers, `originColor` (measured cyan /
predicted-estimated violet / unavailable secondary),
`agentKindColor` (+ sky recommendation, green approved), `AeroTopBar`,
`AeroDockView`. New components compose these; new tokens are additive
`aero_*` resources only.

## 3. Screens requiring modification

1. **Home** — insert Decision Card; reorder to Hero → Decision → Next
   action → Last result → Metrics → Quick links; demote raw diagnostics.
2. **Benchmark result hero** — predicted-vs-measured comparison polish
   (existing `refreshResultHero` + `showResultComparison` data).
3. **Agent workspace** — connected workflow stepper over existing
   `AeroAgent` cards + `AgentReview` proposal data; handlers untouched.
4. **Contribution UI** (`GlobalView`, contribution history/receipt dialogs)
   — 4-state journey visualization from actually-known record states.
5. **Settings** — light declutter only (grouping/labels), no behavior.
6. Dialogs already migrated — no further change unless a Priority needs it.

## 4. Proposed new screen hierarchy (same 5 tabs, same routing)

- Home/Decision: hero → **Decision Card** → primary action → last measured
  result → device metric tiles (collapsible) → quick links.
- Device/Model: existing Home metric tiles + Model catalog dialog,
  surfaced via Decision Card "Details" (no new destination).
- Benchmark/Results: unchanged flow; result hero gains comparison block.
- Meta AI/Agent: workspace gains workflow stepper header; proposal review
  keeps exact buttons/wording.
- Global Evidence: contribution entry gains journey strip; receipt keeps
  SUBMITTED-vs-published honesty.

## 5. Decision Card component specification

New pure derivation `AeroDecision` + view builder in `AeroShell`-style kit:

- **Header:** "EdgeFit AI" / "Your device, your model, your evidence."
  (static brand copy — UI-ONLY)
- **Hero result:** staged model display name (existing staged-model state)
  + state chip mapped ONLY from real states: No model (nothing staged) /
  Staged — not yet measured / Measured on this device (exact-hash
  COMPLETED run) / suitability verdict overlay (verified_compatible →
  Measured; likely_compatible_unverified → Estimated fit;
  unsupported/insufficient_evidence → constraint text).
- **Evidence summary rows:** model identity (name + SHA-12 + MB),
  hardware compatibility (suitability status + first reason), available
  memory (live snapshot or "unavailable"), expected performance (latest
  stored prediction, labeled ESTIMATE, violet), measured performance
  (latest exact-hash COMPLETED run, teal), primary constraint (first
  suitability reason / memory-limited flag). Every absent value renders an
  honest "unavailable"/empty state — never estimated-as-measured.
- **Meta AI insight:** latest stored prediction rationale/explanation
  excerpt when present; otherwise "No Meta explanation recorded yet."
  No new API request, no invented summary.
- **Primary action:** the existing `NextAction` label + `actionTab`
  destination (same handler as today's Home primary button).

## 6. Benchmark comparison specification

New view builder `EdgeFitEvidenceComparison` reusing `AeroResult.Origin`
colors + labels (never color alone):

- Predicted row (violet, "Meta estimate"): stored `predicted_gen_tps` /
  `predicted_prefill_tps` from the bound session prediction.
- Measured row (teal, "Measured on this device"): COMPLETED run rates.
- Difference row: ONLY the already-stored host-computed error
  (`SessionComparison` values) with "vs persisted prediction" note;
  absent/uncomparable → neutral "Not comparable (reason)".
- Amber reserved for incomplete/refused evidence with reason text.
- No baseline-selection logic; no cross-config comparisons; empty state
  when no bound prediction exists.

## 7. Meta AI workflow visualization

Connected step strip + existing cards, all from current record state:

1. Meta AI Recommendation (stored prediction/expected outcome).
2. Why Meta suggested it (stored rationale/uncertainty text).
3. Evidence considered (stored cited run ids → existing run detail).
4. Proposed action (actual proposed config diff from `AgentReview` data).
5. Approval control (EXISTING buttons/handlers/wording, incl. Reject/Later;
   approve still re-validates and runs exactly once via existing code).
6. Execution result (true state: Proposed / Approved — awaiting run /
   Running / Measured / Finished; approved NEVER renders as executed).

## 8. Global Evidence UI visualization

Journey strip with four states, each lit ONLY from known record fields:

1. Local measurement (COMPLETED run exists).
2. Submitted/Accepted (server receipt id stored).
3. Qualifying (only if a qualification marker exists — else shown as a
   clearly-labeled conceptual "planned" stage, unlit).
4. Published aggregate (only if a publication marker exists — else
   conceptual/planned, unlit).
Plus consent state, receipt id, "NOT publication" honesty line (existing
copy), and existing history/withdraw actions unchanged. No new API calls,
no invented server status.

## 9. Exact files proposed for modification

NEW (UI-only, additive — AUTHORIZED):

- `android/app/src/main/java/ai/edgefit/diag/AeroDecision.kt` — pure
  Decision Card derivation + view builder.
- `android/app/src/main/java/ai/edgefit/diag/EdgeFitCards.kt` — shared
  builders: status chip, empty state, evidence-comparison block, step
  strip, journey strip (all over `AeroUi` kit).
- `android/app/src/test/java/ai/edgefit/diag/AeroDecisionTest.kt` — JVM
  tests for derivation states/empty states/labels.
- additive entries in `res/values/aero_*` + 1–2 `res/drawable/aero_*`
  (chip/step backgrounds) ONLY if the kit cannot express them.

MODIFIED (surgical — AUTHORIZED as UI-only):

- `MainActivity.kt` — Home destination wiring ONLY: insert Decision Card
  view + reorder existing Home sections; comparison block insertion in
  result hero; stepper header in agent workspace; journey strip in
  contribution entry. No handler/logic edits; no new fields except
  view references where unavoidable (documented in diff review).
- `AeroResult.kt` — ADDITIVE pure helpers only (if needed for comparison
  rows); no signature/behavior changes to existing functions.

READ-ONLY (never modified): everything in §11.

## 10. Existing state sources each component will read

| Component | Sources (already in activity/derivation scope) |
|---|---|
| Decision hero/state | staged display/sha/size; `HomeDashboard.modelReadiness/stagedMeasured`; `ModelSuitability.assess` over existing profile+telemetry+measured shas; `ModelRecommend.recommend` over existing catalog entry+assessment+evidence |
| Evidence rows | `HomeDashboard.lastMeasured`, suitability reasons, `DeviceTelemetry` snapshot, latest stored prediction map |
| Meta insight | latest stored prediction `rationale`/explanation fields (already loaded for Sessions/Agent views) |
| Primary action | existing `NextAction` derivation + `HomeDashboard.actionTab/primaryAction` |
| Comparison | bound session prediction + `SessionComparison` stored errors via existing `showResultComparison` inputs |
| Agent steps | `AeroAgent` cards + `AgentReview` proposal data + session/budget records already rendered |
| Journey | `EvidenceStore`/contribution record fields + receipt ids already rendered |

Calling existing pure derivations (`assess`, `recommend`, `lastMeasured`)
with already-available inputs, and adding new pure read-only derivations,
is presentation logic — UI-ONLY. Any source found missing at implementation
time becomes an honest empty state + a documented INTERNAL dependency (not
a silent logic addition).

## 11. Protected internal files and interfaces (DO NOT TOUCH)

`BenchRunner`, JNI/`cpp/*`, `SessionManager`/`SessionStore` (schema/state
paths), `AgentManager` (budgets/gates/execution), `MetaApi`/`MetaSessionClient`/
`GlobalClient`/`GlobalHttp` (endpoints/schemas/payloads), `MetaKeyVault`/
`GlobalTokenVault`/`*KeySeal` (custody), `EvidenceStore` (records),
`ModelCatalog` (data), `EvalStudy` et al, `MemPolicy`/`BenchGuards`
(rules), `ModelSuitability`/`ModelRecommend`/`HomeDashboard`/
`InsightCards`/`SessionComparison` (existing derivation behavior —
call, don't change), `build.gradle`/manifest/IDs, native flags,
persistence, telemetry scheduling, enrollment scripts.

## 12. Risks and regression considerations

1. **MainActivity wiring slip** (biggest risk): mitigate with minimal,
   reviewable diffs; full JVM suite + `assembleDebug` before/after.
2. **Missing display input** misread as a logic task: rule = empty state +
   document; never grow behavior to feed pixels.
3. **Accessibility**: 1.3x font scale, long model names, TalkBack labels
   (follow `tileDescription` pattern); no tiny numerals; meaning never by
   color alone.
4. **Dark contrast**: reuse `aero_*` tokens only; no hardcoded colors.
5. **No-device validation**: owner-gated; this op delivers compile + unit
   tests + review; screenshot capture happens post-approval on the S22U
   (emulator is not evidence).
6. **Scope discipline**: each Priority ships independently; a blocked item
   is deferred with its dependency, never worked around via logic edits.
7. **Pre-existing uncommitted work** (Aero Phase-2 dialog edits, consent
   docs, device-enroll scripts): untouched and uncommitted; this op adds
   new files + a small MainActivity diff on top.

## 13. Mockup / layout descriptions

- **Home**: AeroTopBar(Home) → hero glow card → Decision glow card
  (overline "RECOMMENDED MODEL", 22sp model name, state chip, 6 evidence
  rows, Meta insight sub-card, primary button) → Last-result card →
  "Device" section (metric tiles, 2-col) → Quick links row.
- **Comparison block** (result hero): two labeled rows (violet ESTIMATE /
  teal MEASURED, 18sp figures) + delta line or neutral reason line.
- **Agent stepper**: 6 numbered dots + connector line + current-state
  caption; existing cards below; approval buttons byte-identical behavior.
- **Journey strip**: 4 nodes (filled = evidenced, hollow + "planned" =
  conceptual) + receipt/consent rows beneath.
- All motion: none beyond existing kit (no animation loops); expansion via
  existing details pattern.

## 14. Implementation sequence

1. Additive resources (only if kit-insufficient).
2. `AeroDecision` derivation + `AeroDecisionTest` (TDD lane: tests first).
3. `EdgeFitCards` builders.
4. Home wiring (Decision + reorder).
5. Comparison block.
6. Agent stepper.
7. Journey strip + Settings declutter.
8. `./gradlew testDebugUnitTest` + `assembleDebug` + diff review vs §11.
9. Deliverables B–F.

## 15. Expected impact on the final video

Re-recordable judge-ready screens: Decision Card home (value prop in
seconds), predicted-vs-measured comparison (purple/teal honesty),
proposal-review stepper (agentic narrative), contribution journey
(receipt ≠ publication). No functional change means the existing
evidence story stays valid; new screens only make it legible.

## 16. Change classification ledger (seeded; enforced during review)

| # | Change | Class |
|---|---|---|
| 1 | New `AeroDecision` pure derivation + tests | UI-ONLY — AUTHORIZED |
| 2 | New `EdgeFitCards` view builders over `AeroUi` | UI-ONLY — AUTHORIZED |
| 3 | Additive `aero_*` resources | UI-ONLY — AUTHORIZED |
| 4 | Home section reorder + Decision insert (same handlers) | UI-ONLY — AUTHORIZED |
| 5 | Comparison/agent/journey presentation over stored data | UI-ONLY — AUTHORIZED |
| 6 | Any new repo/query/load, derivation-behavior edit, API/schema/auth/persist/native change | INTERNAL CHANGE — REQUIRES OWNER APPROVAL (stop item, document, continue rest) |
