# P0-AUTH-ROOTCAUSE-02 — conditional MUSE-A handoff (Android-side 401)

**Condition:** this handoff activates ONLY if the owner-private probe
(`C:\Users\ronba\ef-g1-scratch\p0-auth-probe.ps1`) reports HASH: MATCH +
HTTP 404 (AUTHENTICATED SUCCESS) for the token currently pasted in the
app, while the app still receives 401 on upload. That combination proves
the backend accepts the credential and the app is sending a DIFFERENT one.

**Backend facts established (MUSE-B, read-only + verified):**

- Ingest gateway `a23f6c4b` @100% calls `handleRequest` from `api.js`
  (same `findInstall`: `sha256hex(Bearer)` → `installs.token_hash`,
  reject when missing or `revoked_at IS NOT NULL`). No binding drift:
  config + live DB both `2ccce000-c70c-4c5a-9267-d330dedc22aa`.
- Original `e61abf6b…` REVOKED 2026-11-02T03:04:41Z (disclosed).
  Two ACTIVE replacements, same lineage, 0 submissions each:
  `b78bcf3b…` (03:11:25Z), `a55897ca…` (03:14:50Z).
- Either active token MUST authenticate; only the revoked one 401s.

**Leading hypothesis (MUSE-A to prove/disprove in source):**

`GlobalTokenVault.setSessionToken()` updates the in-memory credential
but does not delete/replace the old persisted credential file, and the
connection dialog defaults "Persist token in Keystore" to UNCHECKED.
A replacement saved session-only can therefore lose to the stale
persisted (revoked) token after process restart, so the app sends the
old token → 401.

**MUSE-A actions:**

1. Trace token precedence: session value vs Keystore/persisted file at
   (a) Save, (b) cold start, (c) upload-header construction. State the
   exact winner in each case.
2. Define stored-token replacement rules: saving a new token MUST
   atomically invalidate the old persisted credential (delete or
   overwrite-then-verify), regardless of the persistence checkbox.
3. Verify the `Authorization: Bearer <token>` header is built from the
   same vault read the connection dialog displays (suffix compare).
4. Add a non-secret diagnostic: display token suffix + persisted flag
   (already exists: "Token: …" line) AND the install association
   outcome of the last upload (401 vs 404/201) without logging token
   material anywhere (no logcat, no file, no screenshot).
5. Test: save replacement token (both checkbox states) → force-stop →
   cold relaunch → confirm suffix still the replacement → upload.
   Record PASS/FAIL per checkbox state.
6. Do NOT add telemetry containing token material. Do NOT change
   consent, quorum, or upload gating. No automatic uploads.

**After MUSE-A confirms the fix:** owner re-pastes the surviving
replacement token once, P0 upload retries, MUSE-B revokes the unused
duplicate install and verifies the first genuine submission.
