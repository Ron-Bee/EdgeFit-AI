# Scientific evidence showcase

Every number below links to the artifact it came from. The governing
rule: **measured beats predicted beats estimated**, and synthetic
data is never presented as device data. Full record:
[qualification-status](qualification-status.md);
methodology: [evidence-methodology](evidence-methodology.md).

## Claim taxonomy

| Label | Meaning | Example |
|---|---|---|
| MEASURED | On-device benchmark output: run id, config, model SHA-256, thermal/memory conditions, timestamp | 16.52 tok/s (study median) |
| PREDICTED | Muse estimate persisted *before* its benchmark, with cited evidence + uncertainty | exp-4 prediction, +14.8% error |
| ESTIMATED / UNVERIFIED | Catalog compatibility guesses, no measurement behind them; UI-labeled | Model catalog suitability |
| SYNTHETIC | Offline fixtures for development; always labeled | Cloud/test databases |
| HOST-TESTED | JVM/Python/Node suites green; no device observed | 634/634 JVM, 38/38 cloud |
| PHYSICALLY VERIFIED | Observed on the Galaxy S22 Ultra | 6-run study |
| DEPLOYED | Serving real traffic | Global backend + dashboard live (empty); no signed APK release |

## Headline finding: the six-run S22 Ultra study (MEASURED)

Frozen protocol, fixed workload (`edgefit-prompt-v1`), manual A/B
order A-B-B-A-A-B, ROUTE APK `2813339C…5B46DE`, owner-executed
2026-10-30/31:

- **Candidate A** (ctx=512, threads=4, predict=64): median **16.52
  tok/s** (range 15.28–16.66, n=3).
- **Candidate B** (threads=8): median **10.49 tok/s** (range
  6.82–13.38, n=3).
- More threads measured *slower* on this device/workload — an
  exploratory observation, not a population claim (n≤3, one device).

Artifacts: [`eval/comp-01b/runs/`](../eval/comp-01b/runs/) (6 bench
JSONs + `ledger.jsonl`), [`eval/comp-01b/study-score.json`](../eval/comp-01b/study-score.json),
[`study-manifest.json`](../eval/comp-01b/study-manifest.json),
[`study-verdict.json`](../eval/comp-01b/study-verdict.json).
Runbook: [ef-comp-01b-owner-run](ef-comp-01b-owner-run.md).

## Honest abstention (PREDICTED, correctly withheld)

Both live Muse arms **abstained** instead of guessing: M0 (no
measurements by design) and M1 (all manifested evidence used
threads=4; zero B runs). Fabricated-citation rate: **0.0**.
Decisions: [`study-decision-M0/M1.json`](../eval/comp-01b/study-decision-M0.json);
raw responses: [`study-response-M0/M1.json`](../eval/comp-01b/study-response-M0.json).

## Bounded agent loop (MEASURED + PREDICTED, scoped)

One physical agent experiment (exp-1 shell → exp-4): proposal,
approval, bound run, measured result, follow-up with
`supports_conclusion: false`, prediction error **+14.8%**
host-computed. Approve + Finish physically verified; Reject and
developer-Modify host-only. See qualification-status EF-AGENT-01.

## Methodology controls (why the numbers are trustworthy)

- **Prediction-before-measurement ordering** enforced by timestamp:
  no prediction can be written after its run starts.
- **Frozen evidence manifests** with SHA-256 per artifact; scoring
  ingests from source bytes and re-verifies identity, completion,
  and unique run ids.
- **Thermal + memory conditions** recorded per run (`x_mem_conditions`,
  `x_failure`); advisory policy replaced the retired 2 GiB block.
- **Citation validation**: follow-ups cite run ids only; fabricated
  citations score 0.0; host computes all errors.
- **Single-device honesty**: all comparisons labeled exploratory;
  no population claims; quorum (5 runs / 3 installs) gates any
  future public aggregate.

## Test engineering (HOST-TESTED)

- Android JVM: **634/634 PASS** on the contribution branch
  (incl. 58 Aero/contribution tests, 37 AgentReview, 28 MemDiag).
- This branch's cloud foundation: **38/38 PASS**
  (`npm test --prefix cloud`, isolated in-memory DBs, synthetic
  fixtures only).
- Deployed Global backend (Muse B branch @ `cba27c3`):
  **297/297 PASS**, independently re-run from a pinned-commit
  archive in this workstream (isolated DBs, zero dependencies).
- Python offline suites (stdlib only, isolated temp DBs):
  `ef_data_01`, META-05/06, Android/UI, DATA-02/03, BENCH-01,
  LOOP-01, session parity — all PASS per validation record.
- PowerShell validators: EF-META-04 12/12; science + safe-install
  suites green.
- Study manifest regression: `node
  scripts/ef-comp-01a-manifest.test.mjs` green.

Re-run commands: [README](../README.md#o-local-development-and-reproducible-tests).
No suite needs credentials, network, or a device — except the
owner-executed live Meta round trips, which never run in tests.

## Global Evidence System (DEPLOYED, honestly empty)

Live since 2026-11-01 per Muse B handoff `cba27c3`
(`implement/ef-global-01-2026-10-30`):

- Dashboard: <https://edgefit-ai.pages.dev/dashboard.html>
- Public reader: `https://edgefit-evidence-public.ronbaute92.workers.dev`
- Ingest gateway: `https://edgefit-evidence-ingest-demo.ronbaute92.workers.dev`
- Consent `ef-consent-01`: PROPOSED, NOT approved; publication
  allowlist EMPTY; 0 genuine rows; P0–P3 NOT RUN.

Independently verified here: dashboard 200 (honest empty copy,
"No published global statistics yet"); `/v1/models` →
`{"api_version":"1","artifacts":[],"truncated":false}`; ingest
`GET /v1/models` → 404; backend 297/297 re-run from archive;
rate-limit 120 and scheduler ceiling 10 confirmed in source.
D1 census (19 legacy/synthetic, 70 history rows) and 401 behavior
attributed to the dated handoff — operator-CLI-only data.
