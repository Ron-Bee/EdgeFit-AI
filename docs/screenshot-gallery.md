# Screenshot gallery — provenance and captions

All images under `assets/screenshots/` are genuine captures from a
Galaxy S22 Ultra (SM-S908U, Android 16) running EdgeFit AI 0.2.0.
No mockups, no renders, no edits beyond file copying. The only
identifiers visible are the public device-model string and
timestamps — the same standard as the pre-existing gallery.

Provenance: the seven pre-existing files were curated on the
contribution branch; four more (`agent-view`, `benchmark-execution`,
`device-details`, `settings`) were promoted byte-identical from the
UX-audit capture set on 2026-11-01 after visual verification.
Originals are preserved; SHA-256 recorded below.

| File | SHA-256 (first 16) | Shows | Supports features |
|---|---|---|---|
| home-dashboard.png | `1C2048CF5696765D…` | Home: device card, telemetry, staged model, last measurement | 2, 3, 10, 12 |
| device-details.png | `A1C167CDCC001F06…` | Device Details (live): SM-S908U, Android 16/API 36, ABIs, 8 CPUs, RAM, live battery/thermal | 2, 3 |
| benchmark-setup.png | `BB19706F454A3B71…` | Benchmark setup: model, config, evidence-sharing steps | 5, 8 |
| benchmark-execution.png | `98493291315C3A73…` | Benchmark tab: ctx=512/threads=4/predict=64, memory readiness, evidence sharing, Run Benchmark | 4, 8 |
| benchmark-result.png | `95D86C5C0314CFF76…` | Completed run: 64/64 tokens, 15.3 tok/s, generation chart | 9, 10 |
| sessions.png | `75699E8F7F7319B6…` | Sessions ledger with measured throughput per session | 11, 12 |
| agent-view.png | `3492C16E78B02809…` | Agent tab: optimization sessions, explicit budgets/approvals | 17, 18, 19 |
| meta-byok.png | `67A422C427214859…` | Meta API settings: session-only key custody, Predict with Meta | 14, 15, 27 |
| model-recommendations.png | `921CB93324537781…` | Recommended models: measured values vs unverified estimates | 6, 7 |
| global-contribute.png | `D4A6FCC72BA67F37…` | Contribute review: run provenance, consent scope | 24 |
| settings.png | `9DCE6DC4A085B695…` | Settings: Meta API, workload, export, memory diagnostics, Global connection, 0.2.0/arm64-v8a | 20, 24, 27 |

(Full hashes: see the EF-REPO-JUDGE-01 report; verify with
`Get-FileHash assets/screenshots/*.png -Algorithm SHA256`.)

## Gallery

![Home dashboard]\(../assets/screenshots/home-dashboard.png)
*Home dashboard — device card, live telemetry, staged model, last
measurement.*

![Device details]\(../assets/screenshots/device-details.png)
*Device Details (live) — platform-reported specs plus live
battery, thermal, and memory signals.*

![Benchmark setup]\(../assets/screenshots/benchmark-setup.png)
*Benchmark setup — model, configuration, evidence-sharing steps.*

![Benchmark execution]\(../assets/screenshots/benchmark-execution.png)
*Benchmark tab — config line, memory-readiness check, evidence
sharing, Run Benchmark.*

![Benchmark result]\(../assets/screenshots/benchmark-result.png)
*Completed run — 64/64 tokens at measured 15.3 tok/s with chart.*

![Sessions]\(../assets/screenshots/sessions.png)
*Sessions ledger — durable prediction → benchmark records.*

![Agent]\(../assets/screenshots/agent-view.png)
*Agent tab — bounded optimization with explicit approvals.*

![Meta BYOK]\(../assets/screenshots/meta-byok.png)
*Meta API settings — session-only key custody, explicit
authorization.*

![Model recommendations]\(../assets/screenshots/model-recommendations.png)
*Recommended models — measured values separated from explicitly
unverified estimates.*

![Global contribute]\(../assets/screenshots/global-contribute.png)
*Contribute review — provenance display with consent scope.*

![Settings]\(../assets/screenshots/settings.png)
*Settings — workload, export, diagnostics, Global connection,
build identity.*

## Website visuals (all captures MISSING — do not fabricate)

The Global dashboard is live but this environment has no real
browser, so no genuine website screenshot could be captured here.
Label taxonomy for the gallery:

- **Live dashboard, empty global state** — real browser capture only
- **Public API, zero qualified artifacts** — real JSON response only
- **Android app screenshots** — the 11 files above
- **Architecture diagrams** — mermaid, rendered from source
- **Reference art** (e.g. `edgefit-hackathon-wallpaper-reference.jpg`,
  SHA `B2D8CF12…67CF0BA`) — branding only, NEVER presented as UI

**Owner capture procedure (real browser, 2026-11-01 or later):**

1. Open `https://edgefit-ai.pages.dev/dashboard.html`; confirm the
   address bar URL and today's date are visible; screenshot the
   empty Summary/Charts with the "No published global statistics
   yet" footer → `assets/screenshots/website-dashboard.png`.
2. Open `https://edgefit-evidence-public.ronbaute92.workers.dev/v1/models`;
   screenshot the `artifacts:[]` JSON with URL visible →
   `assets/screenshots/website-api-models.png`.
3. Record SHA-256 of both files; never edit, crop away the URL, or
   fill in data.

## Missing genuine captures (do not fabricate)

| Screen | Capture procedure (owner, on S22) |
|---|---|
| Agent proposal review detail | Agent tab → parent with pending proposal → review card → screenshot |
| Pending-proposal badge | Same state, bottom-nav badge visible |
| Proposal rejection + invalid-proposal strike | Reject a proposal (or trigger invalid), screenshot result state |
| Live benchmark stages (dark run screen) | Start a run, screenshot mid-generation stage rail |
| Repeat/compare series view | Sessions → repeat action → series section |
| Follow-up interpretation card | Child session with follow-up → interpretation card |
| Predicted-vs-measured delta card | Bound session result with comparison |
| Global dashboard / public explorer | DEPLOYED and live; screenshot pending per Website visuals above (was: does not exist) — capture only after deployment |
| Signed release install flow | Only after a public-preview build exists |

Related: [feature matrix](ef-repo-judge-01-feature-matrix.md),
[demo script](demo.md).
