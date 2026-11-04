# EF-GOV-03 governance reconciliation record

Date: 2026-11-01. Milestone type: governance/documentation ONLY.
No source, test, artifact, or database changes. No commits.

Baseline: local `main` @ `49acff6` (verified newest across all
branches) plus uncommitted Aero dialog migration (3 files,
unmodified by this milestone). Session-loss incident response
per owner directive: attribution investigation STOPPED.

## 1. Architecture status matrix

| Workstream | Requirement IDs | Status | Evidence head |
|---|---|---|---|
| Android profiling | EF-ANDROID-01 | PHYSICALLY QUALIFIED (scoped, historical) | S22 export VALID; re-verify on new installs |
| Native benchmark | EF-BENCH-01/02 | PHYSICALLY QUALIFIED (scoped) | Run A/B + 6 study runs COMPLETED |
| Session persistence | EF-SESSION-01, SESSION-SCALE-01 | IMPLEMENTED + HOST; physical scoped (historical) | 478/478 JVM; on-device data lost 11-01 |
| BYOK + authorization | FR-BYOK-01/02/03 | IMPLEMENTED + LIVE + PHYSICAL (scoped, historical) | One S22 live session |
| Agentic loop | FR-AGENT-01/02/03/04, REVIEW-01 | IMPLEMENTED + HOST; physical scoped (exp-4) | 515/515 JVM; termination paths unqualified live |
| Model catalog | FR-MODEL-01 (PARTIAL) | HOST; physical pending | 338/338 JVM; hashes unverified |
| UX + Aero | FR-UX-01 | IMPLEMENTED + HOST; dialogs BLOCKED | 634/634 (branch); aero-qual shots exist; review pending |
| Onboarding | FR-ONBOARD-01 | IMPLEMENTED + HOST; retirement physical pending | 395/395 JVM |
| Report import/attach | FR-RPT-01 | PARTIAL (see gap G4) | Evidence import exists; session attach missing |
| Global client | EF-GLOBAL-CONTRIBUTE-01 | IMPLEMENTED + HOST; no live backend observed | 58 new JVM tests; handoff doc |
| Cloud backend | EF-CLOUD-01 | IMPLEMENTED + HOST; NOT DEPLOYED | 38/38 node; 7 deploy gates closed |
| Stats methodology | EF-STATS-01, FR-STATS-01/02 | PROPOSED design; host code only | stats.js tested; policy design-only |
| Public explorer | FR-WEB-01 | PROPOSED; NOT STARTED | Nothing built |
| Studies | EF-COMP-01A/B | HOST + executed (entry missing, see gap G6) | 6 runs + M0/M1 + score artifacts |
| Science protocol | EF-SCIENCE-01 | Tooling HOST; trials pending | 46/46 PS self-test |
| Fleet | FR-QUAL-01 | DRAFT design; NOT STARTED | ef-fleet-auto-02-arch.md |
| Distribution | FR-DIST-01/02 | PROPOSED; debug/bench builds only | No signed artifact |
| Demo/showcase | FR-DEMO-01 | README published; recording pending | No video URL |
| Install safety | FR-SAFE-01 (new, MANDATORY) | APPROVED; implementation PROPOSED | This milestone |

## 2. Gap analysis (A–O vs master architecture + traceability)

- G1. Traceability rows D/G/H/I/J/L/M/Y/Z/N/O predate their
  evidence (session store, BYOK, live META-06, UX builds,
  cloud foundation all landed later). Corrections proposed
  in §5, existing rows left untouched pending approval.
- G2. Master architecture §1 lacks rows for Aero UI,
  Global contribution client, cloud foundation, and
  install safety; rows 9a/9b read PROPOSED though
  implemented. §19 added (this milestone); table rows
  proposed in §5.
- G3. `output-metadata.json` + Nov 1 incident prove two
  coexisting app identities (base vs `.bench`) with
  isolated storage; no governing doc addresses variant
  identity strategy. Conflict C1/C2 (§4).
- G4. FR-RPT-01 reads NOT STARTED, but evidence import
  (EvidenceStore + BenchResultParse + Import UI) is
  implemented + host-qualified; only session attach/bind
  is missing. Scope clarification proposed, not a rewrite.
- G5. EF-COMP-01B executed 10-31 (6 runs, 2 live calls,
  score) with no qualification entry. Entry proposed in
  §5 from artifacts; owner confirmation required.
- G6. Nov 1 session loss unrecorded in governing docs.
  Incident entry added to qualification status (§5).
- G7. FR-UX-01/Aero dialog migration uncommitted and
  BLOCKED (build + device gates unrunnable here).
- G8. FR-QUAL-01/Fleet: single-device evidence only;
  fleet harness unapproved for build (owner decisions
  1–5 open in fleet arch doc).

## 3. EF-SAFE-INSTALL-01 (OWNER APPROVED — MANDATORY)

Full text recorded as FR-SAFE-01 in
`edgefit-product-requirements.md`. Summary: no
destructive or identity-changing APK operation on a
data-bearing EdgeFit installation without (1) physical
device identity, (2) user/profile, (3) APK
applicationId + variant, (4) signing compatibility,
(5) installed-package identity, (6) pre-install
session/evidence inventory, (7) verified recoverable
backup, (8) explicit owner authorization for any
uninstall/clear/removal/identity change, (9)
post-install preservation checks, (10) fail-closed on
any check failure. `adb install -r` alone never
proves data safety. Secrets/Meta credentials never
exported without separate explicit authorization.
Applies to every fleet device.

Acceptance tests: (a) preflight refuses a
cross-identity install over a data-bearing package
without the explicit flag; (b) preflight refuses on
signing mismatch; (c) backup verifies by
re-hash before any destructive step; (d) post-install
counts match pre-install inventory or the run stops
with a named discrepancy; (e) fresh-install banner
shows on firstInstall==lastUpdate with zero
sessions; (f) no test exports key material.

## 4. Conflicts and required owner decisions

- C1. Release doc pins public-preview `applicationId`
  `ai.edgefit.diag` — identical to debug builds.
  Demonstrated risk: signature collision on upgrade
  paths + identity confusion (Nov 1). DECISION: give
  debug builds a distinct suffix (e.g. `.debug`), or
  keep and rely on procedural controls. Recommends
  suffix (fail-closed by construction).
- C2. All qualified on-device evidence was gathered
  under `.bench`, which can never upgrade to a
  release identity. DECISION: canonical identity for
  re-qualification + which history must be re-earned
  vs carried as historical record.
- C3. Stale traceability rows (§2 G1). DECISION:
  approve the §5 corrections or restate.
- C4. FR-RPT-01 scope vs existing evidence import
  (G4). DECISION: confirm attach/bind remains a
  separate milestone; acknowledge import as partial.
- C5. Study-execution + Aero host records missing
  from qualification status (G5, §5). DECISION:
  confirm artifact-sourced entries.
- C6. Fleet owner decisions 1–5 (devices, small
  models, per-device freeze, backup retention,
  harness publication) still open.

## 5. Proposed document diffs (this milestone)

Applied in worktree, uncommitted, awaiting approval:

1. `edgefit-product-requirements.md`: new FR-SAFE-01
   (EF-SAFE-INSTALL-01, MANDATORY) with acceptance tests.
2. `edgefit-master-architecture.md`: new §19
   installation identity + preservation rule; incident
   facts; pointer to C1/C2. No existing section altered.
3. `ef-arch-02-traceability.md`: appended EF-GOV-03
   reconciliation notes — row-by-row corrections for
   D/G/H/I/J/L/M/N/O/Y/Z as PROPOSED corrections (rows
   themselves unedited) + new rows AB (Global
   contribution client), AC (cloud foundation),
   AD (Aero UI), AE (safe install), AF (comp-01b
   execution, pending owner confirmation).
4. `qualification-status.md`: appended entries —
   Nov 1 session-loss incident record; comp-01b study
   execution (artifact-sourced); Aero + Global
   contribution host qualification (handoff-sourced);
   Aero dialog migration BLOCKED.
5. `ef-fleet-auto-02-arch.md`: appended
   EF-SAFE-INSTALL-01 applicability section (all
   fleet installs gated; no design change).

## 6. Reconciled pre-submission roadmap

Tier 1 — submission blockers: (a) owner repeats 3
physical tests; (b) Aero dialog migration review +
physical qualification; (c) screenshot refresh from
qualified build; (d) FR-DEMO-01 recording + claim
trace. Tier 2 — before production claims:
EF-SAFE-INSTALL-01 implementation (preflight tool,
fresh-install banner, launcher labels); EF-DIST-01
signed public-preview + variant-identity decision
(C1/C2); deployment-choice evaluation (ADR-009).
Tier 3 — post-hackathon: cloud deploy + token ops,
fleet qualification, FR-RPT-01 attach/bind,
FR-MODEL-02/03, FR-EVID-01/02. Tier 4 — optional:
Play track, second theme, multi-model fleet stats.

## 7. Recommended next engineering milestone (exactly one)

**EF-SAFE-INSTALL-01 implementation.** Rationale: the
owner's 3 repeat physical tests all require device
installs — the exact operation that just destroyed
evidence. No Tier 1 step is safe to execute until
installs are gated. Deliverable: install preflight
tool + fresh-install banner + distinct launcher
labels + qualification entry. Exit criteria: the §3
acceptance tests (a)–(f) pass; the 3 repeat tests
then proceed under the rule. All other candidates
(Aero dialogs, demo recording) depend on safe
device access and therefore rank after it.

## 8. Change log and conflict register

- 2026-11-01: EF-GOV-03 created this record; added
  FR-SAFE-01, arch §19, traceability notes + rows
  AB–AF, 4 qualification entries, fleet
  applicability note. No source/artifact/DB touched.
- Conflicts open: C1–C6 (§4). No governing text was
  silently rewritten; all corrections are appended
  as proposed pending owner approval.

## 9. Protected-data preservation statement

Uncommitted Aero dialog files (CardDialog, MetaView,
ModelCatalogView) untouched and uncommitted. No
source compiled, no tests run, no API calls, no
deploys, no database or artifact modifications.
`tools/` left untracked. Git HEAD still `49acff6`.
All status claims above are sourced to listed
evidence or labeled as pending/proposed.
