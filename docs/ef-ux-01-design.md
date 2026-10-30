# EF-UX-01 design blueprint (PROPOSED — design only)

Implementation-ready UX design for the EdgeFit AI Android
application. This document specifies WHAT to build and WHY;
it authorizes no source changes by itself. Status: PROPOSED /
OWNER REVIEW REQUIRED. Implementation: NOT STARTED.

Governing: FR-UX-01, FR-ONBOARD-01, FR-RPT-01 (all PROPOSED);
FR-UI-01/02, FR-AGENT-01/02/03, FR-BYOK-01/02/03, FR-MODEL-*,
FR-EVID-* (unchanged); master architecture §18 user journey;
ADRs 001–019 (no ADR change proposed herein). The committed
EF-AGENT-01/FIX/FIX2 baseline (`bf56878`) is the behavior
reference: every flow below maps to existing handlers (§A),
and nothing fictional is specified.

Non-goals: visual rebrand beyond the token set (§D);
cloud/stats/web/multi-model surfaces (remain long-term);
report import behavior (FR-RPT-01, separate milestone —
only its future slots are reserved here); dark-theme-first
delivery (§D evaluates dark mode).

## 0. Recorded owner decisions (UX-D01…D04, OWNER APPROVED)

- **UX-D01 Onboarding Skip.** Skip dismisses the current
  onboarding presentation only; it is not an opt-out.
  Onboarding may reappear on a subsequent appropriate app
  entry (cold start or foreground return, idle only — see
  §E) until three qualifying completions retire it. It MUST
  NOT interrupt an active benchmark, a paid-authorization
  dialog, or an in-progress Agent review (pending proposal
  awaiting decision). Authoritative text mirrored in
  product requirements §Owner decisions.
- **UX-D02 Standalone benchmarks.** A standalone COMPLETED
  benchmark MAY count toward retirement IFF its success is
  durably recorded and uniquely identifiable; a transient
  result screen is insufficient. The current build persists
  no standalone record, so §F designs the smallest safe
  extension (a `standalone`-mode session record reusing
  `SessionRecord`, run-id dedup, and identity checks — no
  parallel store, no native/contract change). The
  `SessionStore.MODES` addition is an app-local detail for
  the implementation milestone to own and test, not an
  architecture contract change.
- **UX-D03 Replay completions.** A replay-sourced session MAY
  count IFF it satisfies §F; it MUST render labeled REPLAY
  and MUST never read as a newly measured benchmark.
  Repeating one source artifact MUST NOT multi-count:
  counting dedups on a replay-source identity
  (`prediction.replay_source_sha256`, defined in §F) that
  does not exist in the current schema and MUST be
  specified and tested before implementation. No change to
  publication eligibility or verification claims. NOTE: no
  Android UI path creates replay records today, so this
  branch is dormant until FR-RPT-01 lands.
- **UX-D04 Milestone order.** UX foundation + onboarding
  first (UX-01A…D); report import/validation/attachment as
  a separately controlled FR-RPT-01 milestone; integrate
  its actions into the redesigned UI when available. No
  vaporware: unimplemented destinations render explicit
  "not available" states (§J), never disabled-looking-live
  controls.

## A. Existing screen/handler inventory (source-grounded)

Single `MainActivity` + programmatic dialogs today. Every
row below names the exact handler; the redesign (§B–§I)
re-homes these, never re-implements their logic.

| # | Capability | Entry today | Handler | Class |
|---|---|---|---|---|
| 1 | Device capabilities display | Main screen text | `refresh()` → collector | Implemented, physically verified |
| 2 | Profile export (SAF save) | Export Profile | `launchSaver()` picker | Implemented, physically verified |
| 3 | GGUF staging + SHA-256 | Select Model File | `launchPicker()` → `stageModel()` | Implemented, physically verified |
| 4 | Workload transparency | View Workload | `BenchWorkloadView.show()` | Implemented, physically verified |
| 5 | Benchmark run + live stages | Run Benchmark | `startBench()` → `BenchRunner` → `BenchScreenView` | Implemented, physically verified |
| 6 | Cancel run | Cancel | `runner.cancel()` | Implemented, not physically verified |
| 7 | Result save (SAF export) | Save Result / bench Save | `launchSaver()` | Implemented, physically verified |
| 8 | Offline-test session create | Sessions form | `createOfflineSession()` | Implemented, physically verified |
| 9 | Session list / bind / delete | Sessions rows | `bindSession()` / `deleteSession()` | Implemented, physically verified |
| 10 | Child retry Predict / Follow-Up | Sessions row buttons | `startLivePrediction(id)` / `startLiveFollowUp(id)` | Implemented, physically verified (exp-4) |
| 11 | Key entry / Save / Forget | Meta API dialog | `MetaKeyVault` + `saveMetaKey()` | Implemented, physically verified (scoped) |
| 12 | Prediction + authorization | Predict with Meta | `startLivePrediction(null)` + `showAuthorizeDialog` | Implemented, physically verified |
| 13 | Agent create / list / detail | Agent dialog | `createAgentSession()` / `agentDetail()` | Implemented, physically verified |
| 14 | Proposal request | Request Proposal | `startAgentProposal()` + authorize | Implemented, physically verified |
| 15 | Approve & Run / Reject / Finish | Agent actions | `approveAndRunExperiment()` / `reject…` / `finish…` | Approve+Finish physically verified; Reject host-only |
| 16 | Benchmark attach + compare | automatic on completion | `attachSessionToResult()` → bridge → manager | Implemented, physically verified |
| 17 | Failure/cancel/timeout states | bench screen + session states | `BenchResult.status` → session mapping | Partially: thermal refusal + attach-failure observed; cancel/timeout host-only |
| 18 | Report import / re-attach | — none — | — | Missing (FR-RPT-01) |
| 19 | Onboarding | — none — | — | Missing (FR-ONBOARD-01) |
| 20 | Cloud/stats/catalog surfaces | — none — | — | Future/proposed (not this milestone) |

## B. Navigation architecture (proposed)

Five bottom-navigation destinations (Material3
`NavigationBar`, ≥48dp targets, badges for pending review):

`Home · Benchmark · Sessions · Agent · Settings`

- **Home** (§C): readiness + staged model + next action +
  last result + entry points. The default landing tab.
- **Benchmark**: run control, live stages (dark immersive
  variant retained), result details, export. Hosts current
  `BenchScreenView` content as a destination, not an overlay.
- **Sessions**: the ledger — every session record
  (standalone, offline-test, byok, replay, agent parents +
  children with hierarchy affordance), bind/predict/
  follow-up/delete, comparison display. Replaces the
  Sessions dialog with a full destination.
- **Agent**: dedicated destination (assessment below):
  parent list → parent detail (status, budget, evidence
  considered, history) → pending-proposal review →
  approve/reject/finish. Reuses Sessions row components
  for children; deep-links both ways.
- **Settings**: device details, workload view, key custody
  (entry/save/forget/status), authorization history
  surface, Help/onboarding replay entry, about/versions.

Agent-destination assessment: integration-only was
considered (agent as a Sessions filter) and rejected —
the agent's budget/proposal/approval mental model,
pending-review badge, and sequential workflow (§G) merit
a destination, while the underlying records stay unified
in the Sessions ledger. Both destinations share one
`SessionRecord` renderer to prevent drift.

Back-stack: bottom tabs are top-level (Back never exits
mid-benchmark without the cancel confirmation, §J);
detail screens push and pop normally; process death
restores the selected tab + visible record ids only —
never auto-resumes inference.

## C. Main-screen (Home) layout description

Home answers, top to bottom: readiness → model → next
action → last result → shortcuts. All cards use §D
tokens; technical detail hides behind "Details" expansion
preserving exact SHA/config/provenance access.

1. **Readiness card.** Device model + Android/API, RAM
   available/total, thermal state, ABI. One-line verdict:
   Ready / Not ready (reason). Details: full collector
   output, profile hash, Export Profile action.
2. **Model card.** Staged GGUF name + size, compatibility
   line (ABI ok, RAM headroom ok/short, workload fixed),
   Select/Change action. Empty state: illustration-free
   prompt + Select action (§J). Details: full SHA-256
   (selectable), staged bytes, workload id + prompt hash.
3. **Next-action card.** Exactly one primary button derived
   from state, in priority order: Select a model →
   Run benchmark → Review pending proposal (badge count)
   → Request follow-up → View results. Secondary link to
   the full destination. The derivation is a pure
   state→action function, unit-tested in UX-01A.
4. **Last-result card.** Most recent COMPLETED run: config
   chips (ctx/threads/tokens), gen + prefill tok/s,
   measured-vs-predicted delta when a session binds,
   thermal line, View + Export actions. Empty: "No
   benchmark yet" + Run action. Never shows predictions
   as measurements; provisional labels stay provisional.
5. **Shortcuts row.** Sessions, Agent (badge when a
   proposal pends review), Meta connection state chip
   ( Connected / Not connected — no key material),
   Settings.

## D. Visual tokens and component rules

Provisional palette (starting points, not fixed identity):
Primary `#0866FF` · White `#FFFFFF` · Neutral bg `#F2F4F7`
· Subtle blue `#DCE7F7` · Dark text `#172B4D`. All color
tokens live in one `colors.xml` + night-qualified future
file; no hardcoded colors in layout code (lint-enforced
in UX-01A).

- Type: system font, `headlineSmall` card titles,
  `titleMedium` section heads, `bodyMedium/Large` content,
  tabular-nums for all tok/s + ms figures; 1.3x font
  scale must not clip actions (scroll fallback tested).
- Shape: 16dp cards, 12dp dialogs/sheets, full-radius
  chips and primary buttons; 1dp `#DCE7F7`-ish borders,
  elevation ≤1dp (no heavy shadows).
- Spacing: 8dp grid; 16dp screen gutters; 12dp card
  internal padding; 48dp minimum touch targets.
- Components: Material3 `MaterialButton`
  (filled primary / tonal secondary / text tertiary),
  `NavigationBar`, `CardView`→`MaterialCardView`,
  `Chip` for config/state, `LinearProgressIndicator`
  for stages, `AlertDialog`→`MaterialAlertDialogBuilder`
  for authorizations (destructive = Reject/Delete/Forget
  get explicit confirm wording, §J). Disabled states use
  38% opacity + non-clickable (AUD-08 class fix:
  Delete/Forget gain confirms).
- Numbers: tok/s to 1 decimal in cards, full precision
  in Details; ms as integers; deltas signed with +/−
  and % with denominator note ("vs persisted prediction").
- Dark mode evaluation: full dark theme is DEFERRED past
  submission (scope control). The benchmark progress
  destination keeps its qualified dark immersive variant
  as an explicit exception; all new light surfaces use
  theme roles (`?attr/colorSurface` etc.) so a later
  night theme needs no layout rework. Dark-mode delivery
  is NOT in UX-01A…D acceptance.

New-dependency flag for implementation: Material3 Views
(`com.google.android.material`) + Navigation component +
Fragments replace the single-activity programmatic
layout. No Compose rewrite pre-submission (risk control).
The added libraries, versions, and licenses need owner
acknowledgment at UX-01A kickoff (new third-party code
in the APK, pinned + reviewed like llama.cpp).

## E. Onboarding screens and behavioral state model

Three swipeable pages + persistent Skip (top-right, from
page one) + Get Started (page three). Fully offline;
zero network, key, download, benchmark, or paid action.

1. "Understand your device." What EdgeFit reads (model,
   CPU, RAM, thermal — on-device only) + a live
   readiness line. No data leaves the phone.
2. "Measure local AI performance." Stage a GGUF you
   provide → run a real benchmark → read measured
   throughput. Numbers shown are examples labeled
   EXAMPLE, never your hardware's.
3. "Optionally optimize with Meta Muse." Bring your own
   key later; predictions before measurements; you
   approve every experiment. Cloud/stats/catalog are
   NOT mentioned as available.

Presentation state machine (host-owned, derived — no
new volatile flags beyond one dismissal marker):

- `COMPLETIONS` = count of distinct qualifying records
  per §F, recomputed on each evaluation (idempotent).
- `DISMISSED_THIS_LAUNCH` = process-local boolean, set
  by Skip or Get Started, reset on process start.
- SHOW onboarding at cold start or foreground-return
  IFF: `COMPLETIONS < 3` AND NOT `DISMISSED_THIS_LAUNCH`
  AND no benchmark RUNNING AND no authorization/progress
  modal visible AND no agent proposal currently open for
  review (UX-D01 non-interruption, evaluated in that
  order).
- RETIRE: when `COMPLETIONS >= 3`, never auto-show
  again ( survives relaunch/update — derived from
  durable records, reinstall correctly resets).
- REPLAY entry: Settings → Help → "Show introduction"
  reopens onboarding on demand regardless of count
  (does not reset retirement).

Skip (UX-D01): sets `DISMISSED_THIS_LAUNCH` only. Next
cold start re-evaluates SHOW from scratch.

## F. Durable completion/deduplication specification

Predicate `qualifies(record)`, evaluated over
`SessionStore` records using existing semantics
(`SessionState`, benchmark/comparison maps). A record
counts IFF all hold:

1. `benchmark != null` AND `benchmark.status ==
   "COMPLETED"` (a real attached measurement; failures,
   cancels, timeouts, prediction-only, abandoned excluded).
2. `comparison != null` AND `comparison.comparable ==
   true`, EXCEPT the standalone branch below (prediction
   + measured error computed).
3. Distinctness: counted once per `session_id`; the
   count is over distinct records, never file visits.
4. Agent hierarchy: records with `parentSessionId !=
   null` (children) NEVER count directly. A mode-`agent`
   parent counts once IFF `agent.status == "finished"`
   AND ≥1 of its children satisfies rules 1–2
   (looked up by `parentSessionId`, never by name).
5. Source typing: `live_meta` and `offline_test`
   completions count. `historical_replay` counts only
   per the replay rule below, renders REPLAY, and is
   never described as newly measured (UX-D03).
6. Invalid/quarantined/unsupported-version records are
   unreadable by construction and never count; a store
   scan failure shows onboarding (fail-open display,
   never a crash, never a false count).

Standalone branch (UX-D02 — minimal extension): on
standalone COMPLETED, the host persists a
mode-`standalone` `SessionRecord` (existing schema:
`prediction == null`, `comparison == null`) through a
new narrow `attachStandalone`-style path that reuses
RUNNING-state gating, the model/workload/config
identity checks, and the cross-session run-id uniqueness
sweep, but skips prediction/comparison (there is no
prediction to compare). Terminal state stays
`BENCHMARK_COMPLETED` (never `COMPARISON_READY`, which
requires errors). Predicate: mode `standalone` + state
`BENCHMARK_COMPLETED` + attached COMPLETED benchmark.
No parallel store, no native/contract change; the
`SessionStore.MODES` addition + state-path tests belong
to UX-01D. Until implemented, standalone runs do not
count (current behavior, unchanged).

Replay dedup (UX-D03): counting key is
`prediction.replay_source_sha256` (NEW field, absent
today — no Android path creates replays yet, so this
branch is dormant until FR-RPT-01). A replay-sourced
completion counts IFF rules 1–2 hold (replays skip the
time-order check per existing `isReplay` semantics) AND
no other counted completion carries the same source key.
Repeated replays of one artifact contribute exactly one.
The field definition, write path (report-import
milestone), and matrix (same-source-twice, two-sources,
missing-key refusal, label rendering) MUST be specified
and tested before that milestone — UX-01D pins the
predicate; FR-RPT-01 pins the producer.

Process death / relaunch / update: the count derives
from files, so it is inherently crash-safe; partial
writes are atomic-rename safe and unreadable until
complete; updates preserve `files/` (backup-excluded
from cloud, present on device).

## G. Agent workflow screen-by-screen design

One linear flow per experiment, each step its own
screen/section with Back support; every paid or
executing step keeps its existing authorization dialog
wording and disclosure (ADR-015):

1. **Agent home** (Agent tab): parent list (status,
   budget `used/max`, best run) + New Agent Session
   (requires staged model; explains exp-1 shell).
2. **Parent detail**: status card, budget bar, evidence
   considered (comparable table: run/config/tok/s +
   contextual list with reasons — never pooled),
   experiment history timeline, terminal summary when
   finished. Badge dot while a proposal pends.
3. **Proposal review** (from badge or detail): current
   vs proposed config diff (changed keys highlighted,
   cohort note when `n_predict` changes), hypothesis,
   expected outcome + uncertainty, supporting evidence
   ids (tappable → run detail), limitations, benefit.
   Three actions: Approve & Run Once / Reject / Later.
   Approve re-validates freshness (seq + config match)
   before executing — exactly once, exactly one run.
4. **Execution**: hands to the Benchmark destination
   (§H) with the session chip bound ("Exp 2 · ctx
   256/t 4/n 64"); pre-run screen shows the EXECUTED
   config (FIX2-era rule preserved and re-verified).
5. **Measured result**: result card bound to the child:
   measured tok/s (large), expected tok/s (small,
   labeled Meta expectation), host delta +/− and %,
   thermal/memory lines, Details (run id, source hash,
   executed config, evidence ids).
6. **Follow-up**: Request action (authorize dialog) →
   interpretation card: direction, summary, limits,
   `supports_conclusion`, next-experiment suggestion
   labeled PROPOSAL (never auto-queued). Attaches to
   the same child; parent linkage shown.
7. **Decide**: explicit Finish Session / Request Next
   Proposal / Back to Sessions. Budget-exhausted and
   stagnation terminals render their reason + summary;
   no silent continuation.

Visible six-way distinction (labels, never merged):
`Meta prediction` · `Host-approved config` · `Measured
result` · `Host-computed error` · `Meta interpretation`
· `Proposal (not run)`. Colors/typography keep measured
(ink, tabular) vs predicted (blue, italic-ish label
chip) instantly separable.

Failure/recovery states: thermal refusal (config +
retry-later guidance, session stays AUTHORIZED);
cancellation (child CANCELLED, attempt charged per
contract, proposal may re-issue); invalid proposal
(strike count shown, 2nd ends session with reason);
no-key/offline (action disabled with reason, offline
flows unaffected); attach failure (FAILED with cause,
measurement status explicit — FIX2 class, must show
the cause string, never a silent drop).

## H. Benchmark and result presentation

Benchmark destination states: idle (config summary +
preflight checklist: model staged ✓, RAM headroom,
thermal, ABI) → running (dark immersive: stage rail
Loading→Prefill→Generating→Finalizing, live counters,
provisional tok/s + elapsed, Cancel) → result (§G.5
card + Save/Export + View workload + session link).
Preflight refusals render as result-state failures
with cause (never a run). Rotation/backgrounding
during a run keeps state via the existing
cancel/watchdog semantics; the screen re-binds on
return without restarting inference. Standalone vs
session-bound runs carry a persistent chip
("Standalone measurement" vs "SES-… · Exp N") so the
DIAG-class confusion (which run belongs to whom) is
structurally impossible to misread.

## I. Settings, key custody, and authorization presentation

Settings sections: Connection (Meta key status chip:
none / session-only `…1234` / persisted — suffix only,
never the key; Enter/Forget actions; custody explainer
verbatim from ADR-015), Device & Workload (collector
detail + workload view), Sessions data (count, storage
note, per-record delete with AUD-08 confirm; no
bulk-wipe without a typed confirm), Help (onboarding
replay, methodology, version/ABI/build ids), About.
Authorization dialogs keep the approved disclosure
shape (action, model, endpoint, fields, cost note,
Confirm/Cancel) with Material styling; every paid tap
(flow: predict, proposal, follow-up) passes one. No
key entry anywhere except Settings; no background
polling; airplane/offline disables connected actions
with reasons.

## J. Empty, failure, offline, and recovery states

- Empty (no model / no sessions / no runs / no agent):
  one-line explanation + single primary action; no
  dead controls, no fake sample data.
- Failure: cause-first cards (thermal refusal, native
  failure with code, attach failure with cause string,
  provider 401/429/5xx/timeout with retry-later, never
  auto-retry of paid calls). Every failure names the
  session/run it belongs to (or "standalone").
- Offline/no-key: connected actions disabled with
  reason chips ("Requires Meta key", "No network");
  offline benchmark + ledger + onboarding fully usable.
- Recovery: process death mid-run marks the run
  interrupted (never COMPLETED); restart re-binds
  screens to durable state; pending-review proposals
  survive (durable `pending_proposal`); drafts need no
  persistence (no draft UI exists).
- Destructive confirms: Delete session, Forget key,
  Finish agent, bulk-wipe (typed confirm) — all
  explicit; Delete states orphaning rules (AUD-04 fix
  is a UX-01C dependency: refuse parent delete with
  live children, or cascade explicitly — decided at
  implementation with tests).
- Future slots (FR-RPT-01, cloud, catalog): rendered
  ONLY as Settings → Roadmap "Not available in this
  build" rows. No tab, no button, no badge implies
  they exist (UX-D04 anti-vaporware).

## K. Accessibility and Android compatibility

- 48dp targets; contrast ≥4.5:1 text (tokens pair
  `#172B4D` on white/ `#F2F4F7` — verify at UX-01A);
  1.3x font + display-size scaling without clipped
  actions (scroll fallbacks); TalkBack labels on all
  icon-only controls and full-sentence state
  announcements for stage changes ("Generating, 32
  of 64 tokens").
- minSdk 26 through targetSdk 35+ behaviors verified
  on the S22 (edge-to-edge insets, predictive back,
  per-app language agnostic — English only, no
  localization claim).
- No color-only signaling: state always pairs color
  with text/icon (measured vs predicted, pass/fail,
  bound chips).

## L. Requirement-to-screen traceability

| Requirement | Screen(s) | Notes |
|---|---|---|
| FR-UX-01 | Home, tabs, all cards | Journey + disclosure |
| FR-ONBOARD-01 | Onboarding, Help replay | §E + §F |
| FR-RPT-01 | Settings→Roadmap slot only | Behavior separate milestone |
| FR-UI-01 | Benchmark destination | Dark immersive retained |
| FR-UI-02 | Result card, Sessions rows | Delta rendering |
| FR-AGENT-01/02/03 | Agent tab, review, result, follow-up | §G |
| FR-BYOK-01/02/03 | Settings Connection, auth dialogs | Custody verbatim |
| FR-MODEL-*, FR-EVID-* | — none — | No surfaces (long-term) |
| FR-STATS/WEB/DIST/DEMO | — none / Roadmap slot — | Not this milestone |
| Governance rule | all UX-01* milestones | Entrance/exit rites |

## M. Implementation dependency graph

```
UX-01A nav + tokens + Material3/Nav deps
  ├─► UX-01B Home + Benchmark/result (needs A)
  ├─► UX-01C Sessions + Agent (needs A; AUD-04 decision)
  └─► UX-01D onboarding + §F predicate + standalone path
      (needs A; B/C for entry wiring)
FR-RPT-01 ─► needs C (ledger UI) + D (predicate extension
  points); exposes into Settings/Home slots
Device crawl ─► needs B+C+D
Security/distribution ─► needs crawl
EF-DEMO-01 ─► needs all above
```

No implementation step may start before its design
section here is owner-accepted; each obeys the
milestone-governance rule (entrance reads, exit
mapping, one-next-milestone).

## N. Proposed controlled implementation milestones

1. **UX-01A** Navigation foundation + visual system:
   tabs, tokens, Material3/Nav/Fragments, next-action
   pure function + tests, a11y harness. Exit: shell
   app with placeholder destinations (honest empties).
   — DONE host-qualified (zero-dep platform Views;
   Material3 deferred): see qualification-status.
2. **UX-01B** Home + Benchmark/result: readiness/model/
   result cards, benchmark destination (dark reuse),
   export flows, Details expansions. Exit: standalone
   loop fully usable in new UI. — DONE as combined
   **UX-01BC** (owner-accelerated): Home dashboard,
   request lifecycle feedback, structured ESTIMATE/
   MEASURED cards; S22 UI-crawled, no paid calls.
3. **UX-01C** Sessions + Agent: ledger destination,
   agent destination + review flow, badge, AUD-04
   delete-rule decision implemented + tested. Exit:
   full §G flow in new UI, old dialogs removed. —
   PRESENTATION DONE as combined **UX-01BC** (type
   labels, pending actions, detail cards, proposal
   review Details); dialogs retained, AUD-04
   deferred to a separately authorized milestone.
4. **UX-01D** Onboarding + accounting: §E flow, §F
   predicate + tests, standalone persistence path,
   retirement + replay-entry. Exit: 3-completion
   retirement proven across relaunch; replay branch
   specified, dormant, tested-dormant.
   — NOT STARTED. Preceded by review checkpoints:
   UX-01BC-R2 (semantic color + icon nav, S22-crawled),
   UX-01E (live telemetry + timelines, S22-crawled),
   UX-REVIEW-03-R1 (host-only polish: refined icons,
   session/agent cards, failed-run honesty, guard-detail
   strings; owner device review pending),
   EF-FOLLOWUP-02 (host-only repeat workflow: Repeat/
   Compare session actions, series sections, repeat-aware
   proposal review, bounded follow-up priors; physical
   review pending),
   EF-MODEL-01A (host-only offline GGUF catalog:
   Recommended Models via Home/Benchmark, cards +
   Details + explicit View Source, conservative
   suitability; physical review pending),
   EF-MODEL-01B (host-only contextual recommendations:
   current-model overview, size comparison, exact-hash
   local evidence, Evidence + Return actions;
   physical review pending),
   EF-UX-02 (host-only continuity redesign: accordion
   Sessions w/ Latest highlight + filters + fixed
   create action, repeat→prep→run→compare→follow-up
   chain, result/completion dialogs w/ next steps,
   series Reference presentation; physical review
   pending),
   EF-UX-01D (host-only onboarding: 3-screen guide,
   derived 3-completion retirement, standalone
   branch, Help replay, refusal-label + alignment
   corrections; physical review pending),
   EF-MEM-02 (host-only memory diagnostics: opt-in
   checkpoint sampling + x_mem_diag export, shadow
   budget, preflight readiness UX; guard unchanged;
   physical review pending),
   EF-MEM-03 (host-only advisory memory: 15% warn-only
   policy, per-run conditions evidence, typed agent
   recovery proposals, offline recovery assistant;
   blocking 2 GiB rule retired; physical review
   pending),
   EF-SESSION-SCALE-01 (host-only store scaling:
   50-session ceiling removed, windowed list
   rendering, tmp-sweep fix; physical review
   pending).
5. **FR-RPT-01** (separate): import/validate/attach,
   `replay_source_sha256` producer, UI integration.
6. Device crawl + usability acceptance (§O).
7. Security/distribution/demo qualification.

## O. Physical S22 Ultra acceptance checklist

- Cold start → onboarding (skip path) → Home; kill +
  relaunch → onboarding reappears (UX-D01); complete
  3 qualifying sessions → never auto-shows; Help
  replay works.
- Standalone run → standalone record persists (§F) →
  counts once; duplicate run-id re-delivery never
  double-counts (unit + device).
- Offline airplane run: full benchmark + ledger usable;
  connected actions disabled with reasons.
- Agent loop end-to-end in new UI: propose → review →
  approve → bound chip correct → result → follow-up →
  budget exact; thermal-refusal + invalid-proposal +
  no-key paths each rendered per §J.
- Rotation + backgrounding mid-run: no restart, no
  false COMPLETED; process-kill mid-run: interrupted,
  honestly labeled.
- a11y pass: TalkBack stage run, 1.3x fonts, contrast
  spot-checks; 48dp audit on all actions.
- Quarantine/corrupt-record injection: ledger stable,
  onboarding fail-open, no crash.

## P. Known limitations and unresolved architecture decisions

- Dark theme deferred (§D); benchmark dark screen is
  the deliberate exception.
- No new ADR proposed: tokens/nav are design detail
  under FR-UX-01; the `standalone` mode and
  `replay_source_sha256` field are app-local schema
  details owned by UX-01D/FR-RPT-01 with tests —
  flagged here so the owner can demand an ADR if
  desired (recommended only if the field shape
  survives first implementation).
- AUD-04 (delete orphans) decision deferred to UX-01C
  with an explicit test gate (§J).
- Replay counting is specified-but-dormant until the
  report milestone produces replay records.
- Material3/Nav/Fragment dependencies need owner
  acknowledgment at UX-01A (new third-party code).
- This blueprint assumes the `bf56878` behavior
  baseline; any behavior fix during UX-01* must land
  as a separately reviewed defect commit, never
  smuggled inside UI work.
