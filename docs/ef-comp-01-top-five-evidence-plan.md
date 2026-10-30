# EF-COMP-01 — EdgeFit AI competition evidence and demonstration plan

**Date:** 2026-10-30  
**Status:** PROPOSED / OWNER REVIEW REQUIRED. Planning only; this document does not authorize implementation, deployment, data publication, or alteration of accepted decisions.  
**Track:** Meta Global AI Developer Hackathon — Indie Developer; submission due 2026-11-04 20:00 America/Chicago (21:00 ET).  
**Objective:** Make the existing EdgeFit AI system demonstrably useful and technically distinctive without misrepresenting the maturity of its global evidence network.

## 1. Positioning and falsifiable claim

**Short positioning:** EdgeFit AI is an evidence-grounded, human-approved Android inference optimization assistant. Meta Muse Spark proposes bounded configurations and follow-up experiments; Android measures actual inference; the host independently verifies, compares and preserves the evidence. A privacy-preserving, opt-in global evidence network is the planned extension, not an existing population result.

**Core testable claim:** For a fixed on-device model, workload and hardware configuration envelope, the Muse-assisted, evidence-grounded flow can generate actionable recommendations that are correctly bounded, transparently sourced, and evaluated against subsequently measured results. Whether recommendations outperform a deterministic baseline is a question to **measure**, not a fact to assert.

**Not claims:** general performance improvement; hardware attestation; trained/fine-tuned models; universal mobile compatibility; deployed global community statistics; verified prediction superiority; a 37.57x causal optimization gain. The existing 37.57x pair is a particular build/runtime comparison with limitations, not a treatment effect.

## 2. Governing sources (never supersede implicitly)

- \`docs/edgefit-master-architecture.md\` — two-tier evidence system, Meta communication, BYOK Mode A / future managed Mode B, explicit consent, local/cloud custody, qualified publication, future public explorer.
- \`docs/edgefit-architecture-decisions.md\` — accepted ADRs and design-only proposed decisions; ADR-001, ADR-004, ADR-010–015 and provisional policies must remain in force.
- \`docs/edgefit-product-requirements.md\` — FR-AGENT-01/02/03, FR-STATS-01/02, FR-PRIV-01, FR-QUAL-01, FR-DEMO-01; NFR-EVID-01, NFR-PERF-01, NFR-OFF-01.
- \`docs/ef-arch-02-traceability.md\`, \`docs/qualification-status.md\` — claim-to-artifact trace and strong distinction among host, physical, live API, staging and production qualification.
- \`docs/ef-cloud-01-api-contract.md\`, \`docs/ef-stats-01-contract.md\` — cloud API and statistics methods; quorum minimum 5 eligible measurements from 3 independent installs is provisional, not production activated.
- \`docs/edgefit-agentic-workflow.md\` — explicit approval, per-action bounds, maximum five user-approved experiments per session, no automatic execution.
- EF-MODEL-ARCH-01 consolidated amendment (local owner-proposed material, not verified in published \`main\` on 2026-10-30): bounded cross-model shortlist and anonymous read-only public aggregates for Mode A; do not silently treat as accepted/implemented.

Any contradiction must be surfaced for owner decision before code changes. Historic documents may lag behind newer implementation; establish a current source-matched inventory instead of assuming all statuses are up to date.

## 3. Evidence baseline and freeze

**Published \`main\` observed 2026-10-30:** \`6f4644734004ecbf36b84afb069d9182f3a36f68\`. It includes physically scoped EF-SESSION-01 and EF-BYOK-01, real Android benchmark execution, and live Muse prediction/authorized follow-up. This repository's qualification history is the basis for published assertions.

**Newer work reported locally 2026-10-30, NOT independently inspected in this documentation commit:** EF-MEM-03 physically exercised near 1,783 MB platform-reported available memory and 11.9 generation tokens/s with a scoped 64/64 run; EF-CLOUD-01A / agent-review work reportedly passed 515 Android and 38 cloud host tests. These require source, artifact and physical-log reconciliation before publication as committed milestones. No live global backend is established by these host tests.

**Preservation gate EF-COMP-01-P0:**
1. Inspect actual Windows worktree branch/HEAD, tracked and untracked changes, staged changes and Git remotes. Never assume the GitHub branch contains newer local work.
2. Capture safe diff and a non-secret file inventory; redact keys and sensitive output. Preserve ignored private SQLite, profiles, benchmarks and personal credentials locally. Do not copy those to GitHub.
3. Run the existing known suites and record exact results and known deferred cases. Do not infer PASS for suites not run.
4. Commit verified local code/tests/docs on an isolated work branch with an accurate milestone message; push that branch, then verify its remote SHA. Do not force push, reset, or overwrite another branch. Merge or update \`main\` only after source reconciliation and owner review.
5. Establish a frozen evidence manifest mapping each candidate demo claim to commit SHA, build identity, device identity class, model SHA, exported result hashes, and human/device test protocol.

## 4. Competitive differentiator: CLOSED-LOOP PHYSICAL DECISION ENGINE

Current flow (partially physically demonstrated):
\`device profile -> staged GGUF SHA -> Muse bounded prediction -> durable session -> explicit Approve & Run -> on-device benchmark -> host-calculated prediction error -> Muse evidence-grounded follow-up -> owner chooses next action\`.

The **next deliverable is not another general chatbot**. It is an auditable decision artifact explaining:
- What was known before the run (device signals, memory budget, model/build/config, comparable evidence tier).
- Why Muse suggested that specific bounded experiment and what alternatives/unknowns it recognized.
- What the owner actually approved (no hidden, implied or stale approval).
- What native benchmarking measured, with methodology and artifacts.
- Whether Muse's prior prediction was too high/low, and by how much (deterministically recomputed by host).
- What recommendation followed, which evidence IDs justify it, and whether it was approved, rejected or left unexecuted.
- Why lack of compatible evidence yields \`insufficient-evidence\` or \`no justified recommendation\`, not fabricated confidence.

**Do not let Muse directly execute shell, SQL, arbitrary code or silent benchmarks.** The approval gate must challenge each proposal/follow-up synchronously in an acceptably responsive flow, with bounded timeouts and visible fail-closed behavior. No asynchronous automatic experiment continuation. More than five approved experiments in a session must be refused.

## 5. Milestone EF-COMP-01A — Quantify AI's added value

**Question:** Is Muse Spark doing something useful beyond deterministic rules?

Build a repeatable, read-only evaluation harness over *identical* frozen evidence for:
- Baseline R: deterministic compatibility and safety rules, ranked by documented heuristics. Never imply R is optimal.
- Variant M: Meta Muse Spark with the approved evidence pack and validated bounded tool output.
- Optional control M0: Muse without prior physical comparable evidence, clearly labeled and used only where cost/privacy permits.

Test outputs: proposed model/config, bounds/safety validity, citations that exist in input, predicted throughput, confidence/uncertainty, refusal when evidence is insufficient, user actions, and native physical follow-through when authorized.

Record at least:
1. \`valid_proposal_rate\` and \`unsafe_proposal_rate\` for R/M.
2. \`grounded_citation_rate\` and \`unsupported_claim_count\`.
3. \`prediction_absolute_error_tps\` and signed error for matched physical runs (prediction fixed before observation).
4. \`realized_generation_tps\`, latency and platform-reported memory/thermal context, preserving the build and full cohort key.
5. Total prompt + tool costs / tokens and wall-clock recommendation latency.
6. The percentage of cases that appropriately abstain, with zero-evidence and incompatible-device adversarial cases.

**Comparability guard:** same model SHA, runtime/backend/build, prompt SHA, context, sampler and device within each paired experimental group; thermal state and execution order recorded. Three runs per candidate configuration are an *aspirational* repeatability target if the experiment budget and available time permit, never forced by bypassing the five-per-session approval limit. Do not use historical pre-measurement leaks. Benchmarks must be actually user approved.

**Pass condition:** The harness produces transparent A/B data; no assumption that M wins. If M does not outperform R on speed/quality of recommendations, showcase grounded interpretation, appropriate abstention and transparent error accounting, not a false victory. Judge-ready claims include numerator/denominator and sample size.

## 6. Milestone EF-COMP-01B — Thin but real global evidence vertical slice

Keep the master architecture and previously planned EF-CLOUD-01B work. Prioritize:
1. An isolated staging-capable versioned API/SQL adapter using EF-CLOUD-01 HTTP contracts, consent validation, authorization and exact source/build/cohort identities. Use Cloudflare Workers/D1 only after the preconditions and limits of ADR-009 have been assessed; no automatic provisioning.
2. A single real owner-approved \`SUBMITTED -> VALIDATED -> QUALIFIED\` transaction via a controlled test path, with provenance, duplicate suppression and rejection behavior. \`PUBLISHED\` is **not** forced; quorum remains mandatory.
3. A negative case: a known two-run single-install S22 Ultra cohort MUST produce \`insufficient-evidence\`; never publish an average or cross-build mixture.
4. Consent withdrawal: explicit \`DELETE /v1/submissions/{id}\` revokes eligibility, blocks subsequent aggregate and Muse retrieval, and records an auditable tombstone.
5. An end-to-end evidence retrieval/host-tool test using only genuinely published quorum-passing aggregates when such a cohort exists. Until then, isolated labeled synthetic multi-install fixtures may demonstrate functional gating, but must never be displayed as community data.
6. Optional authorized Mode A read-only public aggregate retrieval must carry no Meta API key, private session data or persistent installation identity, and must be separated from any upload consent. ADR-019 and cross-model candidate endpoints still require approval before implementation.
7. A user-visible evidence tier: \`local_physical\`, \`synthetic_example\`, \`global_eligible/published\` (when available). Public only after publish rules, never implicit promotion from host validation.

**Pass condition:** contract/integration tests with source-linked negative cases, data isolation, revocation, exact cohort identity, no leakage of subquorum stats, and an honest staging/live deployment label. Global deployment is optional for this milestone if not qualified by submission; do not trade source integrity for an unsupported "worldwide" claim.

## 7. Milestone EF-COMP-01C — Second physical device

Add a genuinely different Android device if available: ideally lower RAM and/or a different SoC class. Run the same signed/controlled APK, known GGUF hash and benchmark methodology, record capability profile and VALID benchmark artifact hashes, then present cross-device measured differences. If a second device is not available, label that limitation prominently and deliver the negative-compatibility simulation only as host testing.

Do not represent two installs of the same phone as two physical devices. The accepted global reporting method is \`independent_install_count\`, not an attested physical-device count.

## 8. Milestone EF-COMP-01D — Demo & technical story

**Two-minute story (target, exact video limit to verify in submission portal):**
- 0:00–0:15: The pain: "Will this model actually run well on this Android phone?"
- 0:15–0:30: Physical device profile + model hash + constrained prediction; not RAM-size guessing.
- 0:30–1:05: Muse recommends a bounded experiment; Approve & Run executes genuine llama.cpp on-device.
- 1:05–1:30: Measured throughput/memory vs the immutable prediction; host recomputed error; Muse revises recommendation with cited evidence and uncertainty.
- 1:30–1:45: Baseline R vs Muse comparison with sample sizes and disclosed limitations.
- 1:45–2:00: Global network architecture / qualified staging evidence, and truthful "insufficient-evidence" gate; broad-scale network remains the roadmap until deployed.

**Theme framing:** "Build for Your World" is backed by firsthand Android offline AI implementation; "Push the Frontier" is an aspiration for an AI-to-physical-measurement feedback loop, not a claim of scientific discovery.

**Submission package:**
- Actual recording and artifact-linked claims; no fabricated screen or edited performance.
- Updated \`README.md\` with one-sentence value, requirements, exact local setup, architecture diagram, qualified vs roadmap matrix, reproducible tests, privacy/cost boundaries.
- 4–6 screenshots only if real; a straightforward clear demo is better than polish that delays qualification.
- \`docs/hackathon-evidence-manifest.md\`: claim, exact artifact/build/test/commit reference, tier, N, limitations, replay steps.
- Known limitations and independent-judge verification procedure.
- Verify competition rules, intellectual-property boundaries, code creation dates, eligibility, submission fields and final timestamp.

## 9. Priority and timebox: 2026-10-30 through 2026-11-04

- **P0 now:** Safely capture/push newer verified Windows implementation; source-match frozen baseline; correct stale status docs without re-labeling deferred tests as PASS.
- **P1 Oct 30–31:** Demonstrate complete approved local phone loop and record stable screenshots/video; repair only showstopper issues. Hard-stop on unsafe recommendation/execution paths.
- **P2 Oct 31–Nov 1:** Run simple deterministic-baseline vs Muse evaluation harness; publish the measured findings even if Muse loses.
- **P3 Nov 1–2:** EF-CLOUD-01B minimal consent/qualification/revocation vertical slice with strong negative tests; second device if one is available.
- **P4 Nov 2–3:** Quality gate, source-backed README/manifest, replayable demo, candidate submission video. Freeze nonessential features.
- **P5 Nov 4:** Final rehearsal, submission upload and independent link/test verification before 20:00 America/Chicago.

**Scope cuts if delayed:** managed service Mode B, full public explorer, general-purpose model catalog, general cross-model scoring, signed mass distribution, arbitrary remote users, production scale, OAuth and any new training or weight changes. Preserve these in the master plan instead of pretending they are finished. If cloud build competes with a reliable fully measured phone loop, prioritize the phone loop + rigorous evaluation and present cloud as a validated staged prototype / future architecture.

## 10. Go/no-go and source-of-truth rules

Release/demo blockers:
- Experiment runs without per-run human authorization, or stale approval reused.
- Muse-proposed values bypass bounds or cause undeclared data/secret upload.
- Measured values reported as predictions, predictions labeled measured, or synthetic numbers presented as physical/community evidence.
- Invalid or unverifiable model/build/workload identities; mismatched hashes; unqualified artifacts.
- A public cohort mean from fewer than five eligible runs or fewer than three independent installs, absent owner-approved implementation-qualification and activation.
- Key leakage, background upload, unconsented transmission, or unauthorized direct/managed key custody.
- No reproducible physical workflow, or a demo claim without traceable evidence.

**Commit/deployment rule:** A documentation proposal is never implementation authorization. Preserve the existing app, contracts, tests, physical evidence and repository history. An implementation agent may inspect and challenge; changes outside an approved narrow slice must be returned as proposals, not silently executed.

**Success definition:** A skeptical judge can inspect the Android result, verify source identities and bounded Meta calls, repeat the local benchmark, understand what Muse contributed, and see that an evidence-poor outcome fails honestly. Top-five placement itself cannot be guaranteed or technically qualified.