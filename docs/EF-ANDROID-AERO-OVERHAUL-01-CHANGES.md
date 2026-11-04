# EF-ANDROID-AERO-OVERHAUL-01 — Change Inventory + Component Inventory

Deliverables B + C. Operation: EF-ANDROID-AERO-OVERHAUL-01 (UI-only).
Branch: `feature/ef-android-global-01-2026-11-01` @ `c467a32` (unchanged).
Authored: 2026-11-02, resumed session 01a19f95 (repro EF-09A).

## B. Change inventory (file by file)

NEW — pure derivations (no Android imports, JVM-tested):

- `android/app/src/main/java/ai/edgefit/diag/AeroDecision.kt` —
  Decision Card derivation: hero/state chip, six evidence rows,
  Meta insight excerpt, catalog guidance. Reads staged ref,
  session records, one assessment, one recommendation, one
  memory sample. UI-ONLY — AUTHORIZED.
- `android/app/src/main/java/ai/edgefit/diag/AeroCompare.kt` —
  Predicted-vs-measured derivation over the bound session's
  stored prediction/benchmark/comparison maps. Difference
  renders the stored host-computed error verbatim; never
  recomputes. UI-ONLY — AUTHORIZED.
- `android/app/src/main/java/ai/edgefit/diag/AeroAgentSteps.kt` —
  Six-step agent workflow derivation over one parent plus
  children. Approval is status display; approved never
  renders as executed. UI-ONLY — AUTHORIZED.
- `android/app/src/main/java/ai/edgefit/diag/AeroJourney.kt` —
  Four-stage evidence-journey derivation over one evidence
  record. Each stage lit only from known fields; receipt
  never renders as publication. UI-ONLY — AUTHORIZED.

NEW — view builders (thin glue over the `AeroUi` kit):

- `android/app/src/main/java/ai/edgefit/diag/EdgeFitCards.kt` —
  `statusChip`, `evidenceRow`, `decisionCard`,
  `comparisonBlock`, `stepStrip`, `journeyStrip`, plus the
  pure `chipKindFor` mapping. Builders read derived values
  only; the Decision primary button fires the caller's
  existing handler. UI-ONLY — AUTHORIZED.

NEW — maintained tests (JUnit4, existing layout):

- `android/app/src/test/java/ai/edgefit/diag/AeroDecisionTest.kt`
  (20 tests), `EdgeFitCardsTest.kt` (1),
  `AeroCompareTest.kt` (8), `AeroAgentStepsTest.kt` (7),
  `AeroJourneyTest.kt` (10). UI-ONLY — AUTHORIZED.

MODIFIED — surgical:

- `android/app/src/main/java/ai/edgefit/diag/MainActivity.kt`
  (+175/−19): two view-host fields; tile-collapse toggle
  (local visibility state); Home reorder (hero → Decision →
  Next Up → Last Measurement → Model → This Device →
  Quick Access → Meta); Decision/refresh wiring that calls
  existing pure functions with already-available inputs;
  comparison block replacing the one-line delta (same
  session, same stored maps); agent stepper insert (status
  only); journey strip over the same loaded evidence
  record; removal of one redundant Settings button whose
  handler duplicates Contribution History. No handler,
  routing, logic, or persistence edits. UI-ONLY —
  AUTHORIZED.
- `android/app/src/main/res/values/aero_colors.xml` (+1
  token): `aero_warning` amber for warning/incomplete
  evidence, required by the directive visual language
  (§6) and absent from the kit. Additive only.
  UI-ONLY — AUTHORIZED.

NOT TOUCHED (verified by diff): every §11-protected file —
`BenchRunner`, `cpp/*`, `SessionManager`/`SessionStore`,
`AgentManager`, `MetaApi`/`MetaSessionClient`/`GlobalClient`/
`GlobalHttp`, vaults/seals, `EvidenceStore`, `ModelCatalog`,
`Eval*`, `MemPolicy`/`BenchGuards`, all existing
derivations, `build.gradle`, manifest, native flags,
persistence, telemetry, enrollment. Pre-existing
uncommitted work (Aero Phase-2 dialog edits, consent docs,
device scripts, `build.gradle`/native edits from the
device workstream) is preserved byte-for-byte.

Change classification ledger result: items 1–5 executed as
UI-ONLY; item 6 (INTERNAL — requires approval) had ZERO
instances — every display input existed, so no protected
change was needed, requested, or made.

## C. Visual component inventory

Created (all over `AeroUi`, all `aero_*` tokens):

- Decision glow card (overline, 22sp hero, state chip, six
  evidence rows, guidance, Meta insight, primary button).
- Status chip (pill; measured teal / estimate violet /
  neutral / warning amber / error red).
- Evidence row (caption label + wrapping value, TalkBack
  "label: value").
- Comparison block (violet ESTIMATE row, teal MEASURED row,
  stored-error difference row with "vs persisted
  prediction" note, amber refused-comparison reason).
- Workflow stepper (vertical six-dot timeline with
  connector, state captions, per-step announcements).
- Journey strip (horizontal four-node strip with
  connectors, evidenced captions, consent/receipt/server
  lines, conditional receipt honesty line).

Modified: none (no existing component changed shape).

Reused: `AeroUi.card/glowCard/sectionHeader/bodyText/
captionText`, `stylePrimary/styleGhost`, `originColor`,
`metricTile` patterns, ` insight `tps/signed/signedPct`
formatters, `DeviceTelemetry.formatRam`,
`GlobalContract.displayServerState`, exact GlobalView
honesty copy.
