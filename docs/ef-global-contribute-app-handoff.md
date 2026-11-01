# EF-GLOBAL-CONTRIBUTE-01 app/cloud integration handoff

App branch: `feature/ef-global-contribute-01-2026-10-30`
Base: `7a30853` (EF-COMP-01B-GATE-1A). Status at handoff: UNCOMMITTED
(all feature source + tests in worktree, nothing pushed).
Backend source reviewed: `cloud/api.js` + `cloud/validate.js` at
EF-CLOUD-01A (`a6bd12e`, undeployed). No staging deployment exists.

## Client request/response schema (APP-CLOUD-CONTRACT v1)

- POST `{origin}/v1/submissions`, `Authorization: Bearer <token>`,
  body `{"result": {<edgefit-bench-result v1 verbatim>},
  "consent": {"policy_version": "ef-consent-01",
  "accepted_at": "<utc>", "scopes": ["aggregates"]}}`, cap 128 KiB.
- 201 new / 200 accepted-duplicate:
  `{"submission_id", "eligibility", "duplicate"}`. Both persist the
  receipt; duplicates create no second row.
- GET/DELETE `{origin}/v1/submissions/{id}`: 200 receipt/state,
  401 unauthorized, 404 not_found (foreign and missing share one 404).
- Error envelope is `{error: {code, message, details?}}` — the client
  follows live source, NOT the prose doc's api_version wrapper.
- Statuses: 400 malformed_json, 413 body_too_large, 422
  invalid_submission (itemized), 429 quota_exceeded (Retry-After
  honored when present; backend does not emit it today), 5xx treated
  as receipt-unknown (SUBMISSION_UNCERTAIN, never auto-retried).

## Authentication flow

Owner-issued per-install upload token, pasted in
Settings > EdgeFit Global Connection, session-only by default,
optional Keystore persistence (alias `edgefit_global_upload_v1`,
file `global_upload_token.bin`). No self-registration endpoint
exists — token issuance is an OPEN backend dependency. Meta BYOK
keys and upload tokens never share storage, aliases, or requests.

## Consent version and scope

`ef-consent-01`, scopes `["aggregates"]` only. Per-submission
explicit approval; no background sends; consent event
(policy_version + accepted_at_utc) stored per record. "runs"
scope disabled backend-side and never requested.

## Build configuration rules

- Origin is owner-configured free-form https (persisted
  `global_origin.txt`); unset shows "Cloud connection not configured".
- No production default; the client never assumes
  api.edgefit.ronbeesoftware.com exists.
- Dev APK (feature build): SHA-256
  `270EAFAA…CAC8E` (9,096,267 bytes), package ai.edgefit.diag.bench.
  Registered S22 study APK `2813339C…5B46DE` is backed up at
  `EdgeFit-Evidence/backup-ef-comp-01b-20261031/` and was NOT
  reinstalled on the study phone.

## Error/retry/idempotency behavior

- Uncertain sends (transport failure, unreadable receipt, 5xx) mark
  SUBMISSION_UNCERTAIN; owner-authorized resubmission re-sends the
  SAME bytes and the server dedups by content hash.
- No disguised retry ids; no fabricated submission ids; no automatic
  retry; double-tap refused via in-flight guard.

## Server receipt verification

Receipt requires `submission_id` (run-id charset) + known
eligibility; status refresh verifies the echoed id matches the
saved receipt before persisting. Revocation requires an explicit
DELETE 200 with eligibility REVOKED.

## Website deep-link destination

PROPOSED (no live site): evidence explorer cohort view addressed
by model_sha + config triple + build key. No deep link implemented
until the global workstream confirms a deployed route.

## Open backend dependencies

1. Staging deployment + exact origin URL.
2. Owner/staging token issuance procedure (no self-register API).
3. Judge invitation/redeem enrollment (separate milestone; no
   endpoint invented).
4. Confirm consent policy_version registry accepts `ef-consent-01`.
5. Confirm Retry-After emission plan (client already honors it).

## Test matrix and qualification limits

- 58 new JVM tests (contract/parse/store/client/vault), fake
  transport only; full suite 634/634 PASS; dev APK builds.
- HOST QUALIFIED ONLY. No live backend observed: no real upload,
  receipt, revocation, or Keystore behavior verified. Physical +
  staging integration remain NOT RUN.
