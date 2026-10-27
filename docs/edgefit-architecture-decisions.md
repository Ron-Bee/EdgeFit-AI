# EdgeFit architecture decision register

Binding decisions for the evidence network. Status: ACCEPTED
(owner-established, in force), PROPOSED / OWNER REVIEW REQUIRED
(new design awaiting explicit approval), SUPERSEDED. New work must
comply or explicitly amend. Only decisions with explicit owner
approval carry ACCEPTED; all agent-introduced choices below are
marked PROPOSED until the owner approves them.

## ADR-001 Two databases, split by trust (ACCEPTED)

Context: one store cannot be both private-by-default and globally
queryable. Decision: private local SQLite plus a global cloud SQL
database holding only consented, qualified rows. Consequences: sync is
push-only; global schema is a subset projection, never a replica.

## ADR-002 Backend mediates all Muse contact (ACCEPTED)

Context: API keys and raw evidence must not reach devices or browsers.
Decision: only the cloud backend calls Meta Muse Spark, server-side.
Consequences: phones work fully offline except explicit uploads; key
rotation touches one component.

## ADR-003 Content-hash identity (PROPOSED / OWNER REVIEW REQUIRED)

Context: filenames and mutable readings make unstable keys. Decision:
model artifacts key by SHA-256; configs by canonical hash; submissions
by backend ULID; duplicate detection by exact content hash.
Consequences: renames converge; tampering is detectable.

## ADR-004 Explicit per-submission consent (ACCEPTED)

Context: silent telemetry would violate owner control. Decision: every
upload requires an explicit tap recording policy version + timestamp;
withdrawal revokes. Consequences: no background sync anywhere.

## ADR-005 No device PII, install-UUID counting (PROPOSED / OWNER REVIEW REQUIRED)

Context: benchmark data must stay publishable. Decision: contracts
carry no serials, accounts, or ad ids; global device counting uses a
salted hash of a rotatable install UUID. Consequences: fraud signals
are behavioral, never identity-based.

## ADR-006 Eligibility pipeline over direct publish (PROPOSED / OWNER REVIEW REQUIRED)

Context: raw uploads include errors and attacks. Decision:
SUBMITTED → VALIDATED → QUALIFIED → PUBLISHED, rule-driven and
logged, with `revoked` reachable from any state. Consequences: only
PUBLISHED rows feed the website and Muse.

## ADR-007 Aggregates carry quorum (PROPOSED / OWNER REVIEW REQUIRED)

Context: single-device means masquerade as global truth. Decision:
publish mean, median, n, and independent-device count together, with
minimum quorum thresholds set at backend qualification.
Consequences: thin groups stay unpublished but queryable by status.

## ADR-008 EF-META-05 pattern extends to global retrieval (PROPOSED / OWNER REVIEW REQUIRED)

Context: model-facing evidence needs the same discipline already
qualified locally. Decision: server retrieval keeps fixed tool names,
exact fields, pinned values, deterministic order, and hard caps.
Consequences: EF-META-05 tests remain the reference behavior.

## ADR-009 Cloudflare is a candidate, not a commitment (PROPOSED / OWNER REVIEW REQUIRED)

Context: Workers/D1 fit the shape but limits are unverified. Decision:
evaluate against section 9 of the master architecture before any
build; keep VPS + Postgres as fallback. Consequences: no Cloudflare
code, config, or spending until the evaluation is recorded.

## ADR-010 GitHub is not a live database (ACCEPTED)

Context: snapshots aid transparency but invite misuse as storage.
Decision: GitHub holds source plus periodic approved JSON snapshots;
no runtime reads them. Consequences: snapshot format is documentary.

## ADR-011 No automatic import at any boundary (ACCEPTED)

Context: every prior milestone earned trust via explicit gates.
Decision: EF-DATA-02 preview/commit, benchmark upload consent, and
prototype migration all require explicit owner action.
Consequences: automation proposals must name their human gate.

## ADR-012 Fixed greedy workload for v1 benchmarks (ACCEPTED)

Context: comparability needs identical work. Decision: workload
`edgefit-prompt-v1`, greedy sampling, recorded config; v2 workloads
require a new contract version. Consequences: results are comparable
within (model, config, workload) groups only.

## ADR-013 Results never self-verify (ACCEPTED)

Context: app output is a claim, not proof. Decision: benchmark JSON
never sets verified/published state by itself; backend rules and (for
SQLite) owner-approved import gates decide. Consequences: EF-BENCH-01
has no path to `physically_verified`.

## ADR-014 Conditioning is not training (ACCEPTED)

Context: evidence-informed predictions invite overclaiming. Decision:
all publications state that evidence conditions inference
in-context and no training or weight update occurs. Consequences:
website and docs carry this disclaimer wherever predictions appear.
