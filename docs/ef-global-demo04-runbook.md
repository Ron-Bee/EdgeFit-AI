# DEMO post-approval runbook (EXECUTE ONLY AFTER FINAL APPROVAL)

Precondition: owner has approved the exact `ef-consent-01` text
at SHA-256 `B83E78FBF78AD0FBE4E77CFBBCF37709519A4305A45D86FFCCFF6C72E78CDB21`
(recorded in `docs/ef-consent-01-approval.md`). Re-hash before
step 1; any mismatch STOPS everything.

Legend: [B] = MUSE-B backend session, [A] = MUSE-A Android
session, [O] = owner. Tokens never enter Git, reports, chat,
or screenshots.

## Collection phase (needs approval A)

1. [B] Verify approval record fields + policy SHA-256. STOP on
   any mismatch or blank field.
2. [B] Confirm SHA matches this runbook's precondition.
3. [B] Commit the filled approval record (A section) + push.
4. [B] N/A until B granted — skip to 5 (publication stays off).
5. [A+O] Verify the physical installation: `adb devices -l`,
   `ro.product.model`, app version/build. Report to [B] without
   secrets.
6. [A] Confirm the Android build passes its own suite and the
   uploader matches the handoff packet; report commit SHA.
7. [B] Mint ONE credential:
   `node cloud/ops/issue-install.mjs --kind device_contribution
   --device "<model>" --purpose "DEMO P0 <install>" --approval
   "ef-consent-01/A <date>" --device-verified "<model> <UTC>"
   --target staging --execute`
   Record the non-secret line in the enrollment doc + registry.
8. [O] Hand-enter the one-time token into the demo device
   (settings/paste). Confirm receipt to [B] WITHOUT echoing it.
9. [A] Confirm ingest origin reachable:
   `https://edgefit-evidence-ingest-demo.ronbaute92.workers.dev`
   (expect 401 without token, never 5xx).
10. [A+O] Complete a genuine physical benchmark on-device.
11. [A] Show the FULL per-batch review (exact payload fields +
    approved consent text, no paraphrase).
12. [O] Explicit opt-in tap on the device. No tap, no upload.
13. [A] POST; persist HTTP status + receipt body. Report
    `submission_id` + receipt to [B].
14. [B] D1 verify: row exists, `origin = device_contribution`,
    consent version exact, metadata matches the report.
15. [B] Confirm consent version == approved version string.
16. [B] Track eligibility (cron qualifies; PUBLISHED requires
    the B-granted allowlist — until then rows hold at QUALIFIED).
17. [A+B] Withdrawal test on a DESIGNATED submission: [A]
    DELETEs, [B] confirms REVOKED + exclusion from reads.
18. [B] All other rows untouched; reconcile counts + history.

## Publication phase (needs approval B, after A)

19. [B] Allowlist change ONLY when B is recorded: add
    `publishConsentVersions: ["ef-consent-01"]` to `WORKER_DEPS`
    (worker.js) + `PUBLIC_DEPS` (public-reader.js); full suite;
    commit + push; redeploy staging + public workers; record
    versions. Ingest worker needs no change.
20. [B] Verify: eligible cohorts publish; synthetic/legacy rows
    still excluded; revoked excluded; below-quorum withheld;
    no raw IDs/credentials in public bodies; dashboard honest.

## Collection close (demo end, no later than 2026-12-31T23:59:59Z)

- [B] Revoke ALL demo credentials (`revoked_at`): this is the
  collection kill-switch (further uploads 401). No code deploy
  needed to stop collection.
- [B] Optionally delete/disable the ingest worker for a hard
  stop (staging + public reader unaffected).

## Teardown (deadline 2026-12-31T23:59:59Z)

Ordered deletes (history → submissions → installs; demo scope
only — legacy/synthetic fixtures stay unless separately decided):

```sql
DELETE FROM eligibility_history WHERE submission_id IN
  (SELECT submission_id FROM submissions
   WHERE consent_policy_version = 'ef-consent-01');
DELETE FROM submissions WHERE consent_policy_version = 'ef-consent-01';
DELETE FROM installs WHERE kind = 'device_contribution';
```

- [B] Verify zero `ef-consent-01` rows + zero device installs
  remain; reconcile history counts.
- [O] Delete local operator exports/backups containing demo
  rows (milestone report files list them).
- [B] Remove the allowlist entries + redeploy (B reversal);
  verify public reads empty again.
- No judging archive exists to delete (policy: none retained).
