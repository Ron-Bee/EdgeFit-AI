# Consent continuity requirements — post-hackathon collection (REQUIREMENTS ONLY)

**Status:** REQUIREMENTS DOCUMENT. No successor policy is drafted,
proposed, approved, or activated by this file. No identifier for a
future version is reserved.

**Parent policy:** `ef-consent-01`, revision 2026-11-01, SHA-256
`B83E78FBF78AD0FBE4E77CFBBCF37709519A4305A45D86FFCCFF6C72E78CDB21`.
That policy is UNCHANGED by this document: its meaning, retention
deadline (`2026-12-31T23:59:59Z`), and scientific controls stand as
approved-to-be-reviewed. See [ef-consent-01-policy.md](ef-consent-01-policy.md)
and [ef-consent-01-approval.md](ef-consent-01-approval.md).

## 1. Hackathon obligations preserved

- Collection under `ef-consent-01` ends no later than
  `2026-12-31T23:59:59Z`.
- By that deadline the operator deletes: hackathon submission rows,
  revocation tombstones, demo credentials, and operator
  backups/exports in owner control. No judging archive is retained.
- `ef-consent-01` records are NEVER migrated, copied, or re-stamped
  into any post-hackathon store or successor version. Teardown is
  terminal for this data era.

## 2. Successor policy requirement

Post-hackathon collection of new physical-device benchmarks requires
a NEW, separately drafted and separately owner-approved versioned
consent policy. Requirements for that future artifact:

- Full consent text with its own revision date and SHA-256 recorded
  in a new approval record before any collection.
- Explicit collection window, retention period, and deletion deadline
  (no indefinite retention by default).
- Same or stronger protections: voluntary participation, explicit
  per-batch approval, exact uploaded-data inventory, no prompt
  plaintext, aggregate-only publication, withdrawal with terminal
  revocation, local-delete distinction.
- Independent Collection / Publication approvals mirroring the
  A/B structure of the `ef-consent-01` approval record.
- This document does NOT draft that text and does NOT approve any
  version string. The successor identifier will be assigned when its
  draft is written; until then it is "the successor version (none
  assigned)".

## 3. Safeguards carried forward

The successor era MUST preserve: canonical exact-cohort identity and
quorum (5 runs / 3 installs, same cohort); server-assigned provenance
(`origin` from authenticated `installs.kind`, immutable); idempotent
deduplication; consent-version stamping and allowlist gating;
owner-authorized withdrawal with terminal REVOKED semantics; atomic
eligibility+audit history; query-time publication guards on every
public surface. No safeguard may be weakened as part of the era
transition.

## 4. Data-era boundary

Eras are distinguished by BOTH consent policy version AND actual
collection date:

- Era `ef-consent-01`: rows stamped `ef-consent-01` collected on or
  before `2026-12-31T23:59:59Z`. Deleted at teardown.
- Successor era: rows stamped with the successor version collected
  after its Collection approval date. No row may carry both stamps;
  re-stamping is forbidden.
- Cohorts, quorum counts, and aggregates NEVER span eras. A
  successor-era cohort reaches quorum only from successor-era rows.
- Server enforcement (future implementation, NOT built here):
  publication queries predicate on the active era's allowlisted
  version; pre-teardown queries must still exclude deleted-era rows
  by version even if a row physically lingers.

## 5. Dashboard disclosure

The public dashboard MUST disclose, once the successor era exists:

- The hackathon collection cutoff (`2026-12-31T23:59:59Z`) and that
  hackathon-era records were deleted at teardown.
- Which consent version(s) back the currently displayed aggregates.
- That displayed statistics cover only the active era (no cross-era
  pooling).

Until a successor policy is approved and eligible rows exist, the
dashboard keeps its honest insufficient-evidence presentation. This
document does not change website source; the disclosure is a
requirement on future website work.

## 6. Explicit non-authorizations

This document authorizes: NOTHING operational. In particular it does
not: approve any successor text; activate any publication allowlist;
issue or authorize device credentials; permit collection before
`ef-consent-01` Collection Approval A is recorded; extend
`ef-consent-01` past its deadline; or approve any migration of
hackathon rows.

## 7. Approval A readiness (ef-consent-01, current era)

The existing approval record is PREPARED and ready for the owner to
authorize Collection Approval A: policy hash verified, scope and
retention fixed, A/B independence defined, execution checklist in
place. After the owner records A (owner identity + UTC date in
[ef-consent-01-approval.md](ef-consent-01-approval.md), committed
separately): verify hash, keep B OFF, enroll one verified S22 Ultra
installation, coordinate a NEW genuine benchmark with MUSE-A
(historical sessions are 0), then run P0 submission + D1 provenance
verification. No live collection before that approval is recorded.
