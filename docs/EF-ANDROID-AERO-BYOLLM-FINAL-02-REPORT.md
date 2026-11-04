# EF-ANDROID-AERO-BYOLLM-FINAL-02 — Final Report

P0 hackathon polish: unified Aero dark UI + genuine
BYOLLM. Branch `feature/ef-android-global-01-2026-11-01`
@ `c467a32` (unchanged). Device S22U R5CT604K33V,
ai.edgefit.diag. Window 11-02 23:40 → 11-03 00:33 -06:00.

## A. Baseline and preservation

Pre-change: HEAD c467a32; 14 pre-existing modified
files + prior-op changes, all preserved; device
44 sessions / 74 evidence files / 12 submitted (owner
ran+submitted one benchmark between ops); install
95f9195b; known-good APK FD31…3081 re-verified
before AND after; no resets, uninstalls, clears, or
eligibility/logic changes at any point.

## B. UI components audited

All 5 tabs + every dialog builder: Home/Decision,
Benchmark (model/config/run/hero/contribute),
Sessions, Agent, Settings, and AlertDialogs in
AgentView, SessionView ×3, BenchWorkloadView,
OnboardingView, ProgressUi, MainActivity ×4,
CardDialog, MetaView ×3, ModelCatalogView,
GlobalView (via CardDialog), BenchScreenView
overlay, TpsChartView.

## C. White/gray inconsistencies corrected

Root cause: manifest `Theme.Material.Light` left 10
AlertDialogs white. Fix: manifest →
`Theme.EdgeFit.Aero` (the authors' deferred step),
so every dialog inherits the dark Aero theme.
Fallout migrated alongside: legacy `edgefit_*`
light tokens → Aero tokens (SemanticUi accents,
SessionView ink/secondary/blue, SessionView +
AgentView card surfaces), OnboardingView light
sheet → dark (+ghost Back/Skip), BenchScreenView
hardcoded grays → tokens (+styled buttons/chart),
every unstyled Button given primary/secondary/
ghost styling (destructive = secondary body +
error text). Zero `edgefit_*`/white references
remain in code; dead tab drawables left untouched.

## D. Meta API response card changes

Traced: Meta calls already resolved to dark
CardDialog; the owner-visible whites were the
ProgressUi loading dialog and the AgentView
proposal/approval dialog — both dark via the flip.
No content changes to protected derivations; the
6 required elements (reasoning, model, evidence,
next action, approval semantics, execution
status) were verified present across the
prediction/rationale/evidence/Next rows.

## E. Technical card refinements

Session cards, agent cards, device details,
BenchScreenView overlay, workload/history dialogs:
dark surfaces, Aero text hierarchy, measured
(teal) vs estimate (violet) accents, error red
only for failures, amber reserved; no new nesting.

## F. BYOLLM architecture findings

Runtime: vendored llama.cpp v0.7.0, CPU-only
(n_gpu_layers=0), decode-only JNI path with
staged fail-closed errors (load/ctx/tokenize/
overflow/decode). Prior import staged ANY picked
file with a size check only — the honesty gap.
GGUF constants + arch/ftype enums grounded in the
vendored tree (magic, v3, types 0–12, ftypes incl.
Q4_K_M=15, removed 4/5/6/33/34/35).

## G. GGUF compatibility matrix

New `GgufInspect` (pure, tested): parses header
(magic/version/arch/file_type/tokenizer/ctx,
8 MiB cap, truncation-safe). INVALID: bad
container/version/truncated/absurd/missing arch
or file_type. UNSUPPORTED: removed/unknown quant
family, missing tokenizer, non-full-model type,
non-generative arch (embedding/vision/audio/
diffusion/encoder/draft substring classes).
INSUFFICIENT: >2 GiB bound (refuses) or low-RAM
estimate (advisory, runnable, MEM-03). Unknown
archs stay UNVERIFIED (engine arbitrates), never
over-blocked. VERIFIED only on exact-hash
COMPLETED runs. Import stages via temp slot +
rename (prior model survives refusal); Run gated
to runnable verdicts; persistable URI permission
taken; Model Sources dialog links the verified
unsloth page (200 OK, Q4_K_M filename + llama3.2
license confirmed) labeled third-party.

## H. Llama inference test results

Staged 807 MB file header pulled byte-exact
(base64) and parsed by the REAL parser (temp
test, since removed): GGUF v3, 147 tensors,
35 KVs, arch llama, type model, ftype 15
(Q4_K_M), tokenizer present — confirmed Llama
3.2 1B Instruct Q4_K_M. Genuine on-device
inference already evidenced: COMPLETED runs
15.39 / 14.06 tok/s gen. No new benchmark run
(unauthorized); no model touched.

## I. Automated test totals

862/862 green, 61 suites (839 baseline + 23 new
GGUF tests: validation, arch/quant classes,
truncation, resources, precedence, ftype names).
assembleDebug green. Temp device-header test
1/1, then removed with its fixtures.

## J. Physical device verification

Same id/cert/v2 gates; `install -r` Success;
launch clean; all 5 tabs navigated; previously-
white dialogs (agent, session, history, device,
workload-path) open with full content; new Model
Sources dialog verified; no new fatals;
force-stop→relaunch persists. Colors verified by
code/theme review + owner screenshots (agent
pixel inspection unavailable — limitation
disclosed per Phase 11).

## K. Before/after data counts

44/74/12 → 44/74/12; install_id identical;
vaults + 807 MB model intact (owner's 23:30 run
touched model mtime, size identical — their
activity, not this op); backup FD31…3081.

## L. Screenshot inventory

`Downloads\EF-ANDROID-AERO-BYOLLM-FINAL-02\`:
01 home, 02 agent dialog, 03 session detail,
04 model sources, 05 device details, 06 history,
07 settings, 08 stepper, 09 catalog, 10 journey.
Genuine, unaltered. Not captured (need live
interaction): import verdict sheet, approval
dialog with pending proposal, fresh inference/
benchmark result, Meta response card. Public docs
NOT updated (owner review first).

## M. Exact files changed

New: `GgufInspect.kt`, `GgufInspectTest.kt`.
Modified: manifest (theme), aero_themes (comment),
SemanticUi, SessionView, AgentView, OnboardingView,
BenchScreenView, MetaView (1 line), MainActivity
(import verdict flow + sources UI + StagedModel.uri).
Docs: this report. Protected logic, JNI, guards,
persistence, eligibility: untouched.

## N. Remaining defects and limitations

1. Agent cannot inspect pixels — owner must confirm
   dark dialogs/cards from the 10 shots.
2. Import verdict sheet + Run-gating exercised by
   unit tests + code review only (needs a real
   file-pick interaction).
3. Comparison-block render still needs an authorized
   benchmark run (prior gap, unchanged).
4. Dead `tabbar_*`/`edgefit_tab_*` resources left
   in place (harmless).

## O. Recommendation

Approve for screenshot refresh + demo prep after
owner pixel review of the 10 shots; optionally
exercise one real import (any small GGUF) and one
bounded benchmark to close gaps 2–3. No commits/
pushes made; tree holds this op + prior work
uncommitted for owner-directed publishing.

## Statuses

- AERO_UI: PARTIAL (complete + structurally
  verified; owner pixel review outstanding)
- BYOLLM: PASS
- LLAMA_INFERENCE: VERIFIED (measured runs of the
  confirmed Q4_K_M artifact)
- DATA_PRESERVATION: PASS
- OVERALL: READY_FOR_OWNER_REVIEW
