# Consent approval record — ef-consent-01 (APPROVAL ARTIFACT)

This record had PREPARED, unapproved fields until Collection
Approval A was recorded below from the owner's explicit
confirmation in the EF-GLOBAL-P0-TOKEN-01 directive. Publication
Approval B remains NOT APPROVED. The publication allowlist stays
EMPTY until the section 3.B approval is granted AND the section 5
deployment step is separately executed.

## 1. Policy identity (fixed)

- Consent version: `ef-consent-01`
- Policy text: `docs/ef-consent-01-policy.md`
- Revision: 2026-11-01 (bounded retention, no judging archive,
  independence rule, terminology fix)
- Policy text SHA-256: `B83E78FBF78AD0FBE4E77CFBBCF37709519A4305A45D86FFCCFF6C72E78CDB21`
- Prior SHA (superseded draft): `702F63B6375F1C3C22D4AA3ABB3ADE883B89A6D7BEC460737CA8C70087B4EF6E`
- Scope: DEMO physical-device staging + exact-cohort quorum
  publication (5 runs / 3 installations, aggregate-only).
- Environment: `edgefit-evidence-staging` D1 + staging,
  public-reader, and ingest-demo workers (Cloudflare Free).
- Never allowlisted: `v1`, `ef-synth-01` (permanently excluded).

Any edit to the policy text invalidates the SHA above and voids
pending approvals; re-hash and re-approve.

## 2. Retention cutoff (fixed)

- New collection under this version ends at owner-declared demo
  close, no later than 2026-12-31T23:59:59Z.
- Final deletion deadline (rows, tombstones, demo credentials,
  operator backups/exports in owner control):
  2026-12-31T23:59:59Z.
- Judging archive: NONE retained.
- Future production rollout: EXCLUDED (needs a new version).

## 3. Owner approval (FILL ONLY BY THE OWNER)

- [x] A. COLLECTION APPROVED — genuine-device uploads stamping
      `ef-consent-01` may be collected into staging:
  - Owner: Owner — explicit confirmation in EF-GLOBAL-P0-TOKEN-01
    directive (recorded by MUSE-B, nothing backdated; no prior
    historical A record existed)
  - Date (UTC): 2026-11-01 (recorded 2026-11-01T23:00:56Z)
- [ ] B. PUBLICATION APPROVED — rows meeting version + origin +
      quorum + revocation rules may appear in public aggregates:
  - Owner: _NOT APPROVED_
  - Date (UTC): _BLANK_

A and B are INDEPENDENT. A authorizes collection mechanics
(receipts, qualification up to PUBLISHED-eligibility gating);
it does NOT authorize public visibility. B additionally
authorizes the allowlist deployment that makes eligible rows
visible. Granting A without B yields a fully working private
pipeline with an empty public population.

## 4. What each approval authorizes (and does NOT)

Under A only:

- Issue `device_contribution` credentials to verified physical
  installations (enrollment doc procedure + independence rule).
- Accept + store uploads stamping `ef-consent-01`.
- Qualify, withdraw, and audit them normally.
- NOT authorized: any public aggregate containing them; any
  allowlist/code change; any new version approval; any
  reinstall orchestration to inflate quorum diversity.

Under A + B:

- All of the above, PLUS the reviewed allowlist change
  (`WORKER_DEPS` + `PUBLIC_DEPS` gain
  `publishConsentVersions: ["ef-consent-01"]`) with redeploys of
  the staging and public workers, after which qualifying rows
  publish per existing quorum/cohort/revocation rules.
- NOT authorized: allowlisting any other version; weakening
  quorum; reclassifying synthetic/legacy rows; public enrollment;
  migrating rows to any future store.

Device enrollment boundary (both): owner-verified physical
installations only, one credential each, hand-delivered tokens,
registry-recorded. No shared tokens, no APK-embedded secrets.

Audit/withdrawal obligations (both): withdrawal requests revoke
server-side (terminal REVOKED) and exclude from future
aggregates; tombstones retained until the section 2 deadline,
then deleted with the rest. Consent text changes require a NEW
version + new approval; never edit `ef-consent-01` meaningfully
after approval.

## 5. Execution checklist (after approval)

- [ ] Record approval SHAs + timestamps above (commit separately).
- [ ] (A) Issue credentials; run P0 submission + withdrawal.
- [ ] (B) Apply allowlist change; redeploy both workers; verify
      versions, empty-before/quorum-after behavior, and that
      synthetic/legacy rows remain excluded.
- [ ] Update the enrollment issuance record + device registry.
- [ ] Teardown by 2026-12-31T23:59:59Z per section 2.
