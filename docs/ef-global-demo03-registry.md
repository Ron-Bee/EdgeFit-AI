# DEMO-03 device enrollment registry (PREPARED, nothing verified)

One row per installation. Every field stays UNKNOWN / NOT VERIFIED
/ NOT ISSUED until the Android workstream (MUSE-A) or the owner
supplies evidence. Bearer secrets are NEVER stored here.

Historical devices (presence NOT assumed):

- Samsung Galaxy S22 Ultra — SM-S908U
- Samsung Galaxy S22+ — SM-S906U

## Installation rows

### INSTALL-A (first P0 candidate)

- Verified device identity: NOT VERIFIED (expected SM-S908U or
  SM-S906U; owner to confirm which device is in hand)
- Local installation identifier: UNKNOWN (MUSE-A to supply the
  app's install record; the server install_id is minted at
  issuance — the two MUST be recorded together then)
- App version/build: UNKNOWN
- Credential issuance status: NOT ISSUED (blocked on consent
  approval + route use authorization)
- Consent-policy version: ef-consent-01 PROPOSED (not approved)
- First submission receipt: NONE
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
