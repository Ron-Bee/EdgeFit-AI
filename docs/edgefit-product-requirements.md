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
