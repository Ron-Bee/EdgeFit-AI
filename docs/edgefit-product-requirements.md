# EdgeFit product requirements specification (v2, PROPOSED)

Stable requirement IDs for the public developer application. Status
labels match EF-ARCH-02. Every requirement names dependencies, current
status, and objective acceptance criteria. Nothing here is accepted by
writing it; PROPOSED items need owner approval, then implementation,
then the stated qualification.

## Functional requirements

### FR-UI-01 Real benchmark progress

The app SHALL display loading, prefill, generation, and finalization
stages with real token counts, provisional throughput, and elapsed
time from native execution. Dependencies: EF-BENCH-01. Status: HOST
QUALIFIED (implementation complete, physical run pending).
Acceptance: ordered stages observed on S22 Ultra; VALID export; no
number shown that the sampler did not measure.

### FR-UI-02 Prediction-versus-actual display

The app SHALL show the persisted prediction beside measured results
with host-computed absolute and percentage errors. Dependencies:
EF-SESSION-01, FR-AGENT-03. Status: PROPOSED. Acceptance: errors
recomputed by hand from the two artifacts match the display.

### FR-BYOK-01 Secure API-key input

The app SHALL accept a developer Meta Model API key through an
explicit credential screen with masked entry, no logging, and no
inclusion in exports. Dependencies: none. Status: PROPOSED.
Acceptance: key works for one call; grep of app-private files,
logcat capture, exports, and JSON finds no key substring.

### FR-BYOK-02 Direct authorized Meta access

The app SHALL call the official Meta Model API over HTTPS directly
with the user's key, only from explicit user actions, with per-action
field and cost disclosure. Dependencies: FR-BYOK-01. Status:
PROPOSED. Acceptance: airplane-mode refusal; proxy log shows only
`api.meta.ai` with disclosed fields; no EdgeFit backend contact.
Pending interaction (EF-MODEL-ARCH-01, PROPOSED): FR-EVID-01 would
permit explicitly authorized anonymous public-evidence reads
carrying no key and no private measurements; at implementation
time this acceptance line MUST be revised to name that exception
instead of contradicting it. Until then, no backend contact stands.

### FR-BYOK-03 Key removal and optional persistence

Default SHALL be session-only custody; optional persistence SHALL use
Keystore-backed encryption; a Forget Key control SHALL remove every
local copy. Dependencies: FR-BYOK-01. Status: PROPOSED. Acceptance:
restart drops session keys; forensic file check after Forget finds
no recoverable key material.

### FR-AGENT-01 Evidence-grounded prediction

Muse SHALL receive approved device/model evidence plus public stats
and return an evidence-labeled prediction citing only supplied ids.
Dependencies: EF-SESSION-01, EF-STATS-01 (stats optional until
available). Status: PROPOSED (workstation prototype HOST QUALIFIED).
Acceptance: mocked + live schema tests; uncited ids rejected.

### FR-AGENT-02 Bounded experiment recommendation

Muse MAY recommend the next measurable experiment within host bounds
(config ranges, fixed workload, finite budget); the host SHALL refuse
out-of-bounds proposals. Dependencies: FR-AGENT-01. Status:
PROPOSED. Acceptance: adversarial proposal matrix all refused or
clamped; budget exhaustion stops the loop with a report.

### FR-AGENT-03 Prediction error and follow-up

The host SHALL compute errors deterministically and obtain a
grounded follow-up citing evidence, uncertainty, and limits.
Dependencies: FR-AGENT-01. Status: PROPOSED (workstation HOST
QUALIFIED). Acceptance: error math matches artifacts; banned-claim
scan passes on host text.

### FR-STATS-01 Compatible cohort statistics

Public statistics SHALL group only identical model bytes, build
identity, workload, runtime, quantization, and config, with quorum
gating. Dependencies: EF-CLOUD-01, owner quorum approval. Status:
PROPOSED. Acceptance: fixture cohorts verify exact grouping; thin
cohort returns insufficient-evidence, never a mean.

### FR-STATS-02 Multi-device evidence counts

Cohorts SHALL report sample count plus independent-install count
with stated method; hardware-attested device counts are out of
scope until an attestation mechanism exists. Dependencies:
FR-STATS-01. Status: PROPOSED. Acceptance: method documented;
counts reproducible from fixture submissions.

### FR-WEB-01 Public model/device explorer

The website SHALL present cohorts, provenance, methodology, and
disclaimers over the versioned public API only. Dependencies:
FR-STATS-01. Status: PROPOSED. Acceptance: renders fixtures;
ADR-014 notice present; no unpublished data reachable.

### FR-DIST-01 Signed direct-download APK

Releases SHALL be signed, non-debuggable, checksummed release
artifacts published through GitHub Releases with notes and
fingerprints. Dependencies: EF-DIST-01. Status: PROPOSED.
Acceptance: clean install + upgrade on S22 Ultra; checksum and
certificate match the published values.

### FR-DIST-02 Future Play Store upgrade path

Package identity and signing SHALL preserve upgrade compatibility
toward a future Play listing; no Play availability is claimed
before review. Dependencies: FR-DIST-01. Status: PROPOSED.
Acceptance: same package upgrades from direct APK to Play build
in a staged test (when a Play track exists).

### FR-SEC-01 Secret protection

No credential SHALL appear in source, builds, exports, logs,
snapshots, or error text. Dependencies: all networked milestones.
Status: PROPOSED (pattern HOST QUALIFIED on workstation).
Acceptance: automated secret scan of tree + artifacts passes.

### FR-PRIV-01 Consent and withdrawal

Uploads SHALL require per-batch explicit consent; withdrawal SHALL
tombstone rows and exclude them from aggregates. Dependencies:
EF-CLOUD-01. Status: PROPOSED. Acceptance: staged submit → revoke
→ aggregate exclusion with tombstone retained.

### FR-QUAL-01 Physical compatibility matrix

Each release SHALL record tested SoCs, Android versions, and
outcomes; untested combinations SHALL read untested, never
assumed. Dependencies: FR-DIST-01, owner devices. Status:
PROPOSED. Acceptance: matrix row per tested device with VALID
exports attached by hash.

### FR-DEMO-01 Source-backed final presentation

The hackathon recording SHALL show only implemented, qualified
behavior, with every claim traceable to the matrix in
`ef-arch-02-traceability.md`. Dependencies: all physical
qualifications. Status: PROPOSED (EF-DEMO-01 deferred).
Acceptance: claim-by-claim trace review passes.

### FR-MODEL-01 Compatible model discovery (EF-MODEL-ARCH-01, PROPOSED)

The host SHALL assemble a shortlist of up to 3–5 candidate
on-device model artifacts for the current Android device when
credible candidates exist. Candidate screening SHALL use: exact
artifact identity when available (GGUF SHA-256), model
architecture/tokenizer, quantization, minimum supported
runtime/backend, CPU ABI/architecture, device memory with
realistic runtime headroom, storage requirements, context
support, and artifact license/source. Compatibility statuses
SHALL be `verified_compatible`, `likely_compatible_unverified`,
`unsupported`, or `insufficient_evidence`; the UI must explain
the specific basis. A Muse assertion alone is not compatibility
proof. No automatic downloads or execution. Dependencies:
EF-CLOUD-01 model catalog (PROPOSED), ADR-012. Status: PROPOSED;
no multi-model on-device testing exists. Acceptance: unsupported
architecture, incompatible quantization, insufficient RAM,
unknown artifact hash, missing license, duplicate candidates,
stale metadata, and no-candidate cases tested; only
host-approved candidates are actionable.

### FR-MODEL-02 Evidence-grounded comparative prediction (EF-MODEL-ARCH-01, PROPOSED)

Muse MAY provide per-candidate predicted generation and prefill
throughput and estimated RAM/storage only where supportable,
plus confidence/uncertainty, prediction basis, matched/related
cohort IDs, alternative explanations, and explicit data gaps.
The host SHALL independently reject fabricated citation IDs,
invalid model identities, non-finite values, and false
`measured` claims. The UI SHALL distinguish `locally_measured`,
`globally_aggregated`, `extrapolated_prediction`,
`heuristic_prediction`, and `unavailable`. Predictions for
different model SHA artifacts may be compared side by side,
never pooled as one cohort or interpreted as a scientifically
matched comparison. The standard workload text is fixed, but
model tokenizer prompt-token counts may differ. Dependencies:
FR-MODEL-01, FR-AGENT-01, EF-STATS-01. Status: PROPOSED; no
public cohort data exists. Acceptance: valid mixed-evidence
and no-evidence fixtures; provenance mismatch rejection;
thin-cohort exclusion; verified prediction-versus-actual
labels; user-visible confidence and limits; exact
model/config/build identity in comparisons.

### FR-MODEL-03 Explicit model selection and benchmark handoff (EF-MODEL-ARCH-01, PROPOSED)

A developer MAY select a recommended model for a new benchmark
only when the actual staged artifact passes SHA-256 identity,
format/runtime support, local device/resource preflight, and
license/usage requirements. Selection SHALL NOT silently
download a GGUF, switch models, spend API quota, or start
inference. The relevant prediction and its immutable evidence
packet SHALL be durably recorded before an approved run; actual
executed configuration and benchmark identity shall be linked
to the selected artifact. If an artifact is absent or
unverified, offer an informational recommendation without an
executable Run control. Dependencies: FR-MODEL-02,
EF-SESSION-01. Status: PROPOSED. Acceptance: one authorization
→ one run; wrong-artifact and stale-evidence refusal; restart,
cancellation, duplicate action, and mismatch tests; standalone
offline benchmark unaffected.

### FR-EVID-01 Bounded global evidence discovery for Mode A (EF-MODEL-ARCH-01, PROPOSED)

The Android host SHALL be able to retrieve read-only published
model catalog and comparable aggregate data from the EdgeFit
public evidence API without passing a BYOK key or private local
measurements to that API. The host SHALL enforce bounded
query/results, exact source tags, response validation, no
below-quorum leakage, independent opt-in/disclosure, and
offline fallback. Host-to-Meta transmission remains separately
authorized. Dependencies: EF-CLOUD-01 public API (PROPOSED),
FR-BYOK-02 (acceptance interaction noted above). Status:
PROPOSED; the backend is undeployed. Acceptance: fake API
returns unpublished/revoked/thin cohorts, invalid model
hashes, wrong version, oversized payload, redirect to an
unapproved host, and unavailable service; all fail closed or
degrade honestly without blocking offline inference. Network
inspection shows the Meta key sent only to the official Meta
service.

### FR-EVID-02 Host-mediated internal evidence retrieval for Mode B (EF-MODEL-ARCH-01, PROPOSED)

The backend SHALL provide narrow, purpose-scoped, audited
internal retrieval operations for Muse, sharing only approved
eligible aggregate data by default. Any access to nonpublic
individual data requires separate explicit governance,
authorization, and acceptance tests, and is not authorized by
this amendment. No SQL, private database credentials, or
arbitrary network access is exposed to Muse. Dependencies:
EF-CLOUD-01, ADR-008. Status: PROPOSED. Acceptance: schema
allowlists, parameter constraints, deterministic selection,
limits, revoked/private-data exclusion, audit provenance, and
injection tests pass.

### FR-UX-01 Coherent main application experience (EF-UX-REQ-01, PROPOSED)

The app SHALL present a coherent main experience in which
existing functionality is easier to discover, understand, and
use, without changing what that functionality does: current
device capabilities and readiness; current staged model and
compatibility status; the active or most recently relevant
session; the next available workflow action; benchmark results
and prior sessions; optional Meta connection state; Agent
recommendations and approved experiment history; and
report/evidence management once implemented (FR-RPT-01). The
experience SHALL follow master architecture §18 and SHALL use
progressive disclosure — plain workflow language first, with
exact model SHA, benchmark config and workload, predicted-vs-
measured values, measurement provenance, run/session ids,
evidence limits, approval decisions, and truthful errors one
step away, never replaced by paraphrase (FR-UI-01 and FR-UI-02
detail surfaces are preserved, not superseded). Predictions
SHALL never be presented as measured facts; disabled or
unavailable capabilities SHALL NOT appear functional. No
visual framework, toolkit, branding, or layout implementation
is mandated here. The owner's 3/10 rating is a subjective
design baseline, not a measured metric. Dependencies:
FR-UI-01, FR-UI-02, EF-SESSION-01, EF-BYOK-01, EF-AGENT-01.
Status: PROPOSED / OWNER-REQUESTED; NOT STARTED. Acceptance:
scripted reachability walk of every §18 step on device;
provenance spot-checks match artifacts; disabled states
verified non-functional; accessibility spot-checks per
NFR-A11Y-01.

### FR-ONBOARD-01 Skippable first-run onboarding (EF-UX-REQ-01, PROPOSED)

The app SHALL offer a concise, immediately skippable first-run
introduction explaining only: (1) inspect the device and stage
a compatible model; (2) run a real local benchmark and read
its results; (3) optionally connect Meta Muse for predictions
and developer-approved experiments. Onboarding SHALL require
no account, no Meta credential, no downloads, no paid
requests, no benchmarks, and no network. It SHALL retire
automatically after three distinct qualifying completions,
derived from durable session records: a record counts iff it
is top-level (`parentSessionId == null`), has an attached
`COMPLETED` benchmark and a `comparable` comparison (agent
parents additionally require `finished` status with at least
one qualifying child; agent children never count directly;
failures, cancels, abandoned and prediction-only records
never count). Standalone benchmark-only runs leave no session
record and therefore do not count under this rule (see owner
decision below). Relaunch or update SHALL NOT reset the
count. Whether manual Skip permanently disables onboarding or
dismisses only the current introduction is an OPEN OWNER
DECISION — no behavior is specified here. A future Help/replay
entry MAY be proposed separately. Dependencies: EF-SESSION-01
(state semantics), FR-UX-01. Status: PROPOSED /
OWNER-REQUESTED; NOT STARTED. Acceptance: offline first-run
walk; skip path; three-completion retirement verified across
relaunch; failure/abandon/duplicate cases verified
non-counting; cloud/multi-model never described as
operational.

### FR-RPT-01 Report import and attachment (EF-UX-REQ-01, PROPOSED)

Report import SHALL be a separate milestone from the UI
overhaul, exposing: result export (exists), artifact import,
contract validation, explicit attach/bind by model, workload,
and configuration identity, duplicate/contradiction refusal,
imported-vs-locally-measured labeling, and original hash +
provenance preservation. ADR-011 (explicit gates) and ADR-013
(no self-verification) govern: an imported report SHALL never
automatically become physically verified, globally qualified,
or published. The milestone depends on FR-UX-01 surfaces for
exposure but SHALL NOT be absorbed into it. Dependencies:
EF-BENCH-01 (contract v1), EF-SESSION-01, FR-UX-01. Status:
PROPOSED; NOT STARTED. Acceptance: round-trip
export→import→attach with hash equality; mismatch/duplicate
refusal matrix; imported labeling verified in UI and store.

## Nonfunctional requirements

### NFR-PERF-01 Native inference performance

Instrumentation overhead SHALL be measured (3-run A/B per
`bench-ui.md`) and published with variance; material regression
blocks release. Status: PROPOSED (protocol written, runs pending).

### NFR-SAFE-01 JNI and lifecycle safety

No JNI upcalls on the hot path; no retained Java refs; rotation /
backgrounding cancels safely. Status: HOST QUALIFIED (review +
compile; device run pending).

### NFR-THERM-01 Thermal and memory behavior

Guards refuse unsafe starts; thermal/PSS observations use honest
method labels. Status: PHYSICALLY QUALIFIED (guards ran on device).

### NFR-A11Y-01 Accessibility and responsive layouts

Touch targets ≥48dp, contrast ≥4.5:1 for body text, content
descriptions on live regions, small/large screen checks. Status:
PROPOSED (design values set; device check pending).

### NFR-OFF-01 Offline operation

Profiling, staging, benchmarking, export, and session review
SHALL work fully offline. Status: PHYSICALLY QUALIFIED (bench
path ran offline; session store pending).

### NFR-NET-01 Network failure recovery

Meta calls SHALL time out, retry never automatically, and leave
sessions resumable with clear errors. Status: PROPOSED.

### NFR-SEC-01 Credential security

Keystore-backed at rest, masked in UI, excluded from backups and
dumps where platform-supported. Status: PROPOSED.

### NFR-COST-01 Request budgets and cost controls

Per-action token/timeout caps and per-session experiment budgets
with pre-action disclosure. Status: PROPOSED.

### NFR-REPRO-01 Reproducible experiments

Identical inputs SHALL reproduce identical persisted artifacts
(modulo measured timings). Status: HOST QUALIFIED (workstation).

### NFR-EVID-01 Evidence integrity

Hashes, monotonic clocks, and explicit identities end to end; no
filename-only association. Status: HOST QUALIFIED.

### NFR-BUILD-01 Portable builds and documentation

Offline Gradle + NDK builds from a clean checkout with pinned
third-party instructions; no machine-local paths in docs. Status:
HOST QUALIFIED (offline build verified).

### NFR-MODEL-01 Evidence quality and ranking integrity (EF-MODEL-ARCH-01, PROPOSED)

Ranking SHALL expose its decision factors (feasibility, measured
evidence coverage, estimated performance, memory headroom,
license, and uncertainty) and SHALL NOT fabricate a total
ordering when evidence is insufficient. It shall be possible to
return fewer than three candidates or "no justified
recommendation." Measured compatible-cohort evidence ranks above
extrapolations, but published measurements remain
device-reported/rule-qualified, not independent
hardware-attested proof. Dependencies: FR-MODEL-01,
FR-MODEL-02. Status: PROPOSED.

## Milestone governance rule (EF-UX-REQ-01, OWNER-REQUESTED)

This rule covers ALL remaining engineering milestones. At the
beginning of every milestone, the implementing agent SHALL:
(1) read the current local master architecture; (2) read
relevant product requirements; (3) read applicable
architecture decisions; (4) read the traceability matrix and
current qualification status; (5) identify the current
approved implementation baseline; (6) identify the exact
requirement IDs being addressed; (7) state the milestone's
scope and exclusions; (8) identify preservation constraints
and protected evidence. At the end of every milestone, the
agent SHALL: (1) list all changed files; (2) map every
material change to a requirement or approved defect; (3)
verify continued compliance with accepted ADRs; (4) report
tests and their exact outcomes; (5) distinguish host,
emulator, physical, live API, and production qualification;
(6) identify untested behavior and residual risks; (7)
confirm preservation of protected data; (8) identify any new
architecture conflicts or owner decisions; (9) recommend
exactly one next engineering milestone; (10) stop without
beginning that next milestone. No milestone may silently
expand its scope. No code, evidence, or release claim becomes
qualified solely because Muse describes it as complete.

## Open owner decisions (EF-UX-REQ-01)

1. Onboarding Skip semantics: whether manual Skip permanently
   disables onboarding before three completions or dismisses
   only the current introduction. No default is specified.
2. Standalone benchmark-only runs leave no session record;
   whether a future minimal run record should let them count
   toward onboarding retirement, or they remain non-counting
   (recommended for v1: non-counting, no schema change).
3. Whether `historical_replay`-sourced completions qualify
   toward onboarding retirement (recommended: excluded, since
   replays do not demonstrate the live workflow).
4. Relative ordering of UX implementation (roadmap step C) vs
   report import/attachment (roadmap step D); neither may
   absorb the other — see traceability roadmap.
