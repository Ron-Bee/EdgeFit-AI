# Consent approval record — ef-consent-01 (APPROVAL ARTIFACT)

This record is PREPARED, not approved. No field below is filled
on behalf of the owner. The publication allowlist stays EMPTY
until the approval in section 2 is granted AND the section 4
deployment step is separately executed.

## 1. Policy identity (fixed)

- Consent version: `ef-consent-01`
- Policy text: `docs/ef-consent-01-policy.md`
- Policy text SHA-256: `702F63B6375F1C3C22D4AA3ABB3ADE883B89A6D7BEC460737CA8C70087B4EF6E`
- Scope: DEMO physical-device staging + exact-cohort quorum
  publication (5 runs / 3 installations, aggregate-only).
- Environment: `edgefit-evidence-staging` D1 + staging,
  public-reader, and ingest-demo workers (Cloudflare Free).
- Never allowlisted: `v1`, `ef-synth-01` (permanently excluded).

Any edit to the policy text invalidates the SHA above and voids
pending approvals; re-hash and re-approve.

## 2. Owner approval (FILL ONLY BY THE OWNER)

- [ ] A. COLLECTION APPROVED — genuine-device uploads stamping
      `ef-consent-01` may be collected into staging:
  - Owner: _pending_
  - Date (UTC): _pending_
- [ ] B. PUBLICATION APPROVED — rows meeting version + origin +
      quorum + revocation rules may appear in public aggregates:
  - Owner: _pending_
  - Date (UTC): _pending_

A and B are INDEPENDENT. A authorizes collection mechanics
(receipts, qualification up to PUBLISHED-eligibility gating);
it does NOT authorize public visibility. B additionally
authorizes the allowlist deployment that makes eligible rows
visible. Granting A without B yields a fully working private
pipeline with an empty public population.

## 3. What each approval authorizes (and does NOT)

Under A only:

- Issue `device_contribution` credentials to verified physical
  installations (enrollment doc procedure).
- Accept + store uploads stamping `ef-consent-01`.
- Qualify, withdraw, and audit them normally.
- NOT authorized: any public aggregate containing them; any
  allowlist/code change; any new version approval.

Under A + B:

- All of the above, PLUS the reviewed allowlist change
  (`WORKER_DEPS` + `PUBLIC_DEPS` gain
  `publishConsentVersions: ["ef-consent-01"]`) with redeploys of
  the staging and public workers, after which qualifying rows
  publish per existing quorum/cohort/revocation rules.
- NOT authorized: allowlisting any other version; weakening
  quorum; reclassifying synthetic/legacy rows; public enrollment.

## 4. Execution checklist (after approval)

- [ ] Record approval SHAs + timestamps above (commit separately).
- [ ] (A) Issue credentials; run P0 submission + withdrawal.
- [ ] (B) Apply allowlist change; redeploy both workers; verify
      versions, empty-before/quorum-after behavior, and that
      synthetic/legacy rows remain excluded.
- [ ] Update the enrollment issuance record + device registry.

## 5. Standing obligations (both approvals)

- Withdrawal requests revoke server-side (terminal REVOKED) and
  exclude from future aggregates; tombstones retained.
- Consent text changes require a NEW version + new approval;
  never edit `ef-consent-01` meaningfully after approval.
- Teardown follows the retention section of the approved text.
