# EF-GLOBAL-01 — Operator Credential Issuance Procedure

Staging-only, owner-operated. There is deliberately NO API endpoint
for installation enrollment: every upload credential is minted by a
human operator through this procedure, and every mint is a
provenance decision. A mistake here can label synthetic rows as
device contributions permanently (provenance is write-once), so
follow the steps exactly and record each issuance.

## 1. Trust model

- `installs.kind` is the ONLY credential class that matters:
  - `device_contribution` — a genuine physical Android install.
    The only kind whose submissions may publish.
  - `synthetic_test` — operator test harnesses and qualification
    scripts. Never publishable.
  - `legacy_untrusted` — pre-provenance rows. Never publishable.
- `submissions.origin` is copied from `kind` by the INSERT itself;
  no request field can influence it. Both columns freeze at
  creation (migration 003 triggers abort any change).
- Consent allowlist and origin are independent gates: a row
  publishes only when BOTH pass. Staging allowlist stays EMPTY.

## 2. Issuance steps (per credential)

1. Decide the kind FIRST and say it aloud: is this credential for
   a real physical device (`device_contribution`) or for a test
   harness (`synthetic_test`)? When in doubt, `synthetic_test`.
   There is no promotion path; a wrong kind means revoke + reissue.
2. Generate 256-bit entropy and encode within the bearer alphabet
   (`A–Z a–z 0–9 . _ ~ + /`, 8–256 chars, optional `=` padding):
   `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`
   then strip any `+`/`/`/`=` ONLY by regenerating (do not edit the
   string by hand; the alphabet permits them, so keeping them is
   fine — just never truncate).
3. Compute the stored hash exactly as the server looks it up
   (`token_hash = sha256hex(token)`, lowercase hex, no salt):
   `node -e "console.log(require('crypto').createHash('sha256').update(process.argv[1],'utf8').digest('hex'))" "<token>"`
   Run this on the operator machine; the plaintext token must never
   enter chat logs, reports, screenshots, or the repository.
4. Insert (staging D1, explicit `--env staging --remote`):
   `INSERT INTO installs (install_id, token_hash, created_at, revoked_at, kind)`
   `VALUES ('<install-uuid>', '<hash>', '<UTC ISO-8601>', NULL, '<kind>');`
   Use a fresh random `install_id` per credential.
5. Deliver the PLAINTEXT token to its single consumer over an
   existing trusted channel, then delete it from the operator
   machine. The server stores only the hash; a lost token cannot
   be recovered, only rotated.
6. Record the issuance (date, install_id, kind, purpose, operator)
   in the milestone report. Never record the plaintext token.

## 3. Rotation

1. Issue a replacement credential (steps above) with the SAME kind.
2. Deliver it; confirm the consumer uploads with it.
3. Revoke the old one:
   `UPDATE installs SET revoked_at = '<UTC ISO-8601>'`
   `WHERE install_id = '<old-id>';`
4. Rotation never changes kind: flip attempts abort (trigger).

## 4. Revocation

Set `revoked_at` (statement above). Effect is immediate for
authentication: the token 401s on next use. Existing submissions
are NOT cascade-withdrawn (approved semantics): withdraw affected
rows individually through the owner DELETE path when required.

## 5. Forbidden actions

- Issuing `device_contribution` for a test harness, script, or
  imported file. Synthetic evidence uses `synthetic_test`.
- Editing `kind` or `origin` after creation (triggers abort; do
  not work around them).
- Hand-INSERTing submissions with a mismatched origin (aborts).
- Allowlisting `v1` or `ef-synth-01` as real publication versions.
- Storing plaintext tokens anywhere durable or shared.

## 6. Verification queries (read-only)

- Kind distribution:
  `SELECT kind, COUNT(*) FROM installs GROUP BY 1;`
- Origin distribution:
  `SELECT origin, COUNT(*) FROM submissions GROUP BY 1;`
- Mismatch detector (MUST always return zero rows; the INSERT
  trigger makes a nonzero result a database-integrity incident):
  `SELECT s.submission_id FROM submissions s JOIN installs i`
  `ON s.install_id = i.install_id WHERE s.origin IS NOT i.kind;`
- Publishable set preview (MUST be empty on staging while the
  allowlist is empty; replace the version when auditing a
  non-empty allowlist):
  `SELECT COUNT(*) FROM submissions WHERE eligibility = 'PUBLISHED'`
  `AND origin = 'device_contribution'`
  `AND consent_policy_version IN ('<approved-version>');`
