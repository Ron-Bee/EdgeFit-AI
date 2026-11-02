# DEMO-03 device enrollment registry (PREPARED, nothing verified)

One row per installation. Every field stays UNKNOWN / NOT VERIFIED
/ NOT ISSUED until the Android workstream (MUSE-A) or the owner
supplies evidence. Bearer secrets are NEVER stored here.

Historical devices (presence NOT assumed):

- Samsung Galaxy S22 Ultra — SM-S908U
- Samsung Galaxy S22+ — SM-S906U

## Installation rows

### INSTALL-A (first P0 candidate)

- Verified device identity: SM-S908U (Samsung Galaxy S22 Ultra),
  ADB serial R5CT604K33V, Android user 0, package ai.edgefit.diag
  — owner-verified per EF-GLOBAL-P0-TOKEN-01 directive; app
  INSTALL-07 PASS reported at Android commit 29e5939
- Local installation identifier: UNKNOWN (MUSE-A to supply the
  app's install record; the server install_id is minted at
  issuance — the two MUST be recorded together then)
- App version/build: Android branch at 29e5939; rebuilt APK hash
  UNVERIFIED (MUSE-A to confirm)
- Credential issuance status: ROTATION IN PROGRESS (AUTH-RECOVERY-01)
  - First credential: install_id
    `e61abf6b-2ebb-46c5-b0bb-b1014d53fa1d`, issued 2026-11-01T23:03:31Z,
    REVOKED 2026-11-02T03:04:41Z (token disclosed in conversation;
    treated as compromised). 0 submissions were accepted under it.
  - Replacement: SAME kind (`device_contribution`), SAME physical
    installation. Owner issued TWICE (both ACTIVE, 0 submissions
    each — unintended duplicate, same lineage):
    `b78bcf3b-6823-48dc-898d-a455815c4411` (2026-11-02T03:11:25Z)
    and `a55897ca-2d91-4e59-8f5a-c3aa26a3106a`
    (2026-11-02T03:14:50Z). After the AUTH-ROOTCAUSE-02 probe
    identifies which token the app actually holds, revoke the
    UNUSED one (separate action; do NOT revoke blindly now).
    Token itself NEVER recorded.
  - CONTINUITY: rotation does NOT create another independent
    physical device for quorum purposes. INSTALL-A remains ONE
    installation lineage (old id revoked → new id active).
- Consent-policy version: ef-consent-01, Collection A RECORDED
  2026-11-01, Publication B OFF
- First submission receipt: NONE (P0 NOT RUN — new benchmark
  required, historical sessions are 0)
- Cloud eligibility state: NONE
- Withdrawal test status: NOT RUN

### INSTALL-B (P2 second installation)

All fields: UNKNOWN / NOT VERIFIED / NOT ISSUED / NONE / NOT RUN.
Must differ in installation identity from INSTALL-A; cohort
consequences of its device model must be recorded at enrollment
(different model = different exact cohort, never pooled).

### INSTALL-C (P2 third installation, quorum-enabling)

All fields: UNKNOWN / NOT VERIFIED / NOT ISSUED / NONE / NOT RUN.
See the quorum note in the MUSE-A handoff: two different models
cannot satisfy one cohort's 3-installation quorum alone.

## Update protocol

- MUSE-A supplies device/app evidence → owner verifies → MUSE-B
  mints via `cloud/ops/issue-install.mjs` after approval → both
  sessions record receipt/eligibility/withdrawal outcomes here.
- Every mutation of this file is a committed, pushed change on
  the backend feature branch (audit trail for who enrolled what).
