# EF-GLOBAL-DEMO-01 — Android integration handoff packet

For the separate Android workstream. Source-matched against the
backend at branch `implement/ef-global-01-2026-10-30` HEAD `2aeb31a`
and bridge contract v1.1
(`docs/ef-global-app-bridge-01-contract.md`, pinned at bridge commit
`b6b06f7`; v1.1 amends error codes only). Where this packet and the
bridge disagree, STOP and reconcile — do not guess.

## 0. Gate status (backend view)

- Ingestion route: UNDECIDED — see `{INGEST_BASE}` below. No
  device-reachable write route exists until the owner approves the
  enrollment doc's section 4.
- Consent version: `ef-consent-01`, PROPOSED, text in
  `docs/ef-consent-01-policy.md`. Do not ship uploads stamping any
  version until its text is approved.
- Credentials: per-install, owner-issued, NOT YET ISSUED. No
  shared token exists and none will be created.

## 1. Endpoints (base = {INGEST_BASE}, approved route only)

- `POST {INGEST_BASE}/v1/submissions` — contribute one batch.
- `GET {INGEST_BASE}/v1/submissions/{id}` — owner-scoped status.
- `DELETE {INGEST_BASE}/v1/submissions/{id}` — withdraw (revoke).
- Public reads are a DIFFERENT host (dashboard only):
  `https://edgefit-evidence-public.ronbaute92.workers.dev`
  (`/v1/models`, `/v1/benchmarks/aggregates`,
  `/v1/evidence/model-candidates`). Never submit there (405/404).

## 2. Authentication

`Authorization: Bearer <install-token>` (8-256 chars,
`A–Z a–z 0–9 . _ ~ + /`, optional `=` padding). One token per
installation, owner-issued. 401 = missing/invalid/revoked —
surface "contribution not authorized", do NOT retry blindly.

## 3. Request schema (contribute)

```json
{
  "result": { "<edgefit-bench-result v1 object, complete>" },
  "consent": {
    "policy_version": "ef-consent-01",
    "accepted_at": "<UTC ISO-8601 of the user's explicit tap>",
    "scopes": ["aggregates"]
  }
}
```

- `result` MUST be a JSON object (never a string), the exact
  locally validated benchmark result. Unknown top-level result
  fields are stored raw but change the content hash.
- `policy_version`: exact string the user approved. `scopes` MUST
  be exactly `["aggregates"]`; anything else is 422.
- Max body: 131072 UTF-8 bytes (413 beyond).
- No `install_id` in the envelope (server derives it from the
  token). No client provenance claims are honored.

## 4. Receipts and duplicates

- New: HTTP 201 `{ submission_id, eligibility: "SUBMITTED",
  duplicate: false }`. Persist `submission_id` durably.
- Idempotent replay (same content hash): HTTP 200 with the
  ORIGINAL `submission_id`, current `eligibility`,
  `duplicate: true`. Treat as success, never as a new row.
- 201 is a RECEIPT (stored + queued), not qualification or
  publication. Never display it as "published".

## 5. Status and withdrawal

- `GET .../{id}` (same bearer): `{ submission_id, run_id,
  content_hash, eligibility, received_at, published_at,
  eligibility_history }`. 404 for foreign/missing ids (no oracle).
- `DELETE .../{id}`: 200 `{ submission_id, eligibility }`.
  Repeat DELETE is idempotent (still 200, still REVOKED).
- After withdraw, the contribution leaves future aggregates on
  next read. Do not claim success until the 200 arrives.

## 6. Retries and offline behavior

- 429: honor `Retry-After` (quota `quota_exceeded` vs flood
  `rate_limited` — same header discipline).
- 503 (`busy`): exponential backoff, then surface failure.
- Network drop mid-upload: safe to replay the IDENTICAL body
  (content-hash dedup turns it into a 200 duplicate, never a
  double contribution). Never "fix and resend" silently — a
  changed body is a different contribution.
- Offline: queue locally; upload ONLY on the user's explicit
  contribute tap when online. No background auto-upload.

## 7. Token storage (Android)

- Android Keystore / EncryptedSharedPreferences; never
  SharedPreferences-cleartext, never logs, never screenshots,
  never APK resources, never backups. Manual owner entry for the
  demo (settings/paste), per the enrollment doc.
- Service Auth pair (if the recommended route is approved) is
  entered the same way and NEVER shipped in any APK.

## 8. UI action semantics (do not blur these)

- SAVE LOCAL: benchmark persists on-device only. Always works.
- CONTRIBUTE: preview exact payload + approved consent text,
  explicit tap, authenticated POST, persist receipt.
- WITHDRAW CLOUD CONTRIBUTION: authenticated DELETE, show
  server-confirmed REVOKED. Local copy unaffected.
- DELETE LOCAL RESULT: removes the on-device copy ONLY. It does
  NOT withdraw the cloud contribution — say so in UI.

## 9. Cohort honesty

One device + one config = one cohort lane. Never merge runs
across different models, quantizations, runtimes, builds,
workloads, prompt hashes, or inference configs for display or
submission batching. Quorum is 5 runs / 3 installations in ONE
exact cohort; report below-quorum honestly.

## 10. What to return to the backend workstream

Per demo run: `submission_id`, receipt HTTP + body, status-trace
GET body, withdrawal DELETE body (when exercised), exact app
version, and the device `ro.product.model`. No tokens, no
raw device identifiers beyond the model string.
