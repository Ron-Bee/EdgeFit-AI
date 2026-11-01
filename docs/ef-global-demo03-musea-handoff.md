# MUSE-A handoff — DEMO-03 device upload readiness (backend → Android)

MUSE-B (this session) owns server credentials, consent records,
D1 provenance, and publication. MUSE-A owns the Android app and
physical devices. Retrieve this packet via GitHub, never via
shared local paths.

## 0. Retrieval (MUSE-A runs these on its own computer)

- Repo: `https://github.com/Ron-Bee/EdgeFit-AI.git`
- Branch: `implement/ef-global-01-2026-10-30`
- Commit: _see the DEMO-03 report §J for the exact pushed SHA_
  (this file's content is authoritative only at that commit).
- Packet files (all under `docs/` at that commit):
  - `ef-global-demo01-android-handoff.md` (full contract)
  - `ef-consent-01-policy.md` (consent text, PROPOSED)
  - `ef-consent-01-approval.md` (approval record, unapproved)
  - `ef-global-demo01-enrollment.md` (issuance procedure)
  - `ef-global-demo03-registry.md` (per-install rows)
  - `ef-global-app-bridge-01-contract.md` (bridge v1.1 reference)

## 1. Verified HTTPS ingestion origin

`https://edgefit-evidence-ingest-demo.ronbaute92.workers.dev`

- `POST /v1/submissions`, `GET /v1/submissions/{id}`,
  `DELETE /v1/submissions/{id}` only. Live-qualified 14/14.
- No Access/browser token of any kind. Per-install bearer only.

## 2. Per-install token format

`Authorization: Bearer <token>`, 8–256 chars,
`A–Z a–z 0–9 . _ ~ + /` with optional `=` padding. Tokens are
owner-issued per installation; none exist yet. Store in
Keystore/EncryptedSharedPreferences; never log or screenshot.

## 3. Consent status: BLOCKED until approved

`ef-consent-01` is PROPOSED (SHA-256 in the DEMO-03 report §B).
Do NOT ship uploads stamping any version until MUSE-B confirms
owner approval of that exact text. Scopes MUST be
`["aggregates"]`.

## 4. Enrollment prerequisites (in order)

1. MUSE-A reports verified device identity + app version/build
   per installation (model strings, e.g. SM-S908U).
2. Owner approves consent text (collection approval A).
3. MUSE-B mints credentials via the operator issuer; the owner
   hand-delivers one token per device (manual entry).
4. MUSE-A confirms receipt WITHOUT echoing tokens; both sides
   update the registry.

## 5. Submission contract (summary; full text in §0 files)

- Envelope `{ result: <object>, consent: { policy_version,
  accepted_at, scopes } }`; 131072-byte cap; 201 new / 200
  idempotent duplicate (same content hash); 429 honors
  Retry-After; 503 backs off; offline queues locally with NO
  background auto-upload.
- SAVE LOCAL / CONTRIBUTE / WITHDRAW / DELETE LOCAL are four
  distinct actions (handoff §8). Local delete ≠ withdrawal.

## 6. Receipt verification (MUSE-A per upload)

Persist `submission_id` + HTTP status + full receipt body.
Report to MUSE-B: submission_id, receipt, app version, device
model. MUSE-B verifies the D1 row, server-assigned
`device_contribution` origin, and eligibility trail.

## 7. Withdrawal verification

`DELETE` → expect 200 + `eligibility: REVOKED`; repeat DELETE
stays 200. Report both bodies. MUSE-B confirms exclusion from
subsequent reads.

## 8. No-upload-before-approval rule

No genuine upload until ALL of: consent text approved, route
confirmed live by MUSE-B, per-install credential hand-delivered,
and this packet's commit SHA acknowledged by MUSE-A. Test and
emulator traffic uses `synthetic_test` credentials only (request
via MUSE-B; never `device_contribution`).

## 9. Quorum honesty (plan the demo around this)

Publication needs 5 runs / 3 installations in ONE exact cohort.
SM-S908U and SM-S906U are DIFFERENT device classes, hence
different cohorts: they cannot quorum together. A releasable
demo chart needs three installations sharing model, config,
workload, and device class (e.g. reinstalls on one model, or a
third same-model device). Report below-quorum truthfully.
