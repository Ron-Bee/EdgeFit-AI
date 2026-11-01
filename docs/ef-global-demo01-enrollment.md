# EF-GLOBAL-DEMO-01 — Physical Device Enrollment Procedure

Owner-operated hackathon demo enrollment ONLY. There is deliberately
NO public enrollment: every demo credential is minted by a human
operator through this procedure plus the issuance mechanics in
`docs/ef-global-operator-credentials.md`.

## 0. Preconditions (all must hold before any issuance)

1. Consent text `ef-consent-01` approved by the owner
   (`docs/ef-consent-01-policy.md`). The version stamped at upload
   can never be changed after the fact.
2. Ingestion route approved (section 4 below). No route exists today.
3. Android uploader ready in the separate workstream
   (`docs/ef-global-demo01-android-handoff.md`).
4. Target device physically verified by the owner (section 1).

Do NOT issue credentials to work around a missing route, missing
consent approval, or an unverified device.

## 1. Device verification (owner / Android workstream)

No adb access exists in this backend worktree; the owner verifies
on a machine with the device attached:

```
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.board.platform
adb shell getprop ro.build.version.sdk
```

Record the exact `ro.product.model` per installation. Known prior
devices (RE-VERIFY, do not assume present):

- Samsung Galaxy S22 Ultra (SM-S908U)
- Samsung Galaxy S22+ (SM-S906U)

Different models/configurations are different exact cohorts and
must NEVER be pooled. One installation identity per device; a
reinstalled app is a NEW installation (new credential), never a
reused one.

## 2. Per-installation issuance

Follow `docs/ef-global-operator-credentials.md` sections 1-2 with
these DEMO-01 bindings:

- `kind` = `device_contribution`, ONLY for a physically verified
  device in hand. Emulators, scripts, imported files, and
  uncertain cases get `synthetic_test` or nothing.
- `install_id` = fresh random UUID per credential (e.g.
  `node -e "console.log(require('crypto').randomUUID())"`).
- Token: 256-bit entropy, Bearer alphabet, delivered ONCE to the
  demo device by owner manual entry (app demo settings / paste).
  Never compiled into an APK, never screenshotted, never logged.
- consent version stamped by the app MUST be the approved text
  version (`ef-consent-01` once approved). Do not issue against
  an unapproved version.

Record each issuance in section 3 immediately. Wrong kind means
revoke + reissue; there is no promotion path.

## 3. Issuance record (fill as issued; NEVER record tokens)

| Date (UTC) | install_id | kind | Device model | Purpose | Operator | Status |
|------------|------------|------|--------------|---------|----------|--------|
| _pending_  | _pending_  | _pending_ | _pending_ | P0 first submission | _pending_ | NOT ISSUED |
| _pending_  | _pending_  | _pending_ | _pending_ | P2 second install | _pending_ | NOT ISSUED |
| _pending_  | _pending_  | _pending_ | _pending_ | P2 third install | _pending_ | NOT ISSUED |

Status values: NOT ISSUED / ACTIVE / REVOKED. Revocation follows
the operator doc (revoked_at; submissions NOT cascade-withdrawn —
withdraw affected rows individually when required).

## 4. Ingestion route decision (OWNER APPROVAL REQUIRED)

The staging worker is nonpublic (`workers_dev=false`) and behind
an owner-only Cloudflare Access policy. No device-reachable
authenticated write route exists today.

RECOMMENDED (smallest, reuses qualified code): temporary,
demo-window-only Service Auth on the EXISTING staging worker,
per the reviewed H10-07 design:

1. Owner approves a bounded exposure window (date + duration).
2. Deploy staging config with `workers_dev=true` (previews stay
   `preview_urls=false`); record the workers.dev URL privately.
3. Owner creates ONE dashboard Service Token
   (`EdgeFit-DEMO-Temporary`, shortest workable expiry) and ONE
   Service Auth policy on the existing `EdgeFitAI` application
   covering ONLY that token. Owner-email policy untouched; no
   Bypass, no Everyone.
4. Owner enters the Service Token pair + install token into the
   demo device by hand. Tokens never enter chat, repo, or APKs.
5. Demo uploads proceed (per-install bearer at the app layer is
   still independently enforced).
6. Window ends: delete token + policy, redeploy with
   `workers_dev=false`, verify no public URL, verify D1 + cron.

FALLBACK: a new internet-facing authenticated-ingest worker
(per-install bearer only, no Access). Requires separate code,
review, and an explicit deployment authorization; larger and
slower than the recommendation.

EXPLICIT APPROVAL LANGUAGE (all required before step 2):

> I approve a demo-window-only staging exposure for DEMO-01:
> workers.dev enabled from <UTC start> to <UTC end>, one Service
> Auth token/policy as specified, demo-device uploads only. After
> the window the endpoint is closed and credentials revoked.

Do NOT disable Access globally. Do NOT create the token/policy
before this approval.

## 5. Post-demo teardown checklist

- [ ] Service token + policy deleted (dashboard verified).
- [ ] `workers_dev=false` redeployed; no public URL listed.
- [ ] Demo install credentials revoked (or retained ACTIVE only
      with a recorded reason).
- [ ] D1 counts + audit reconciled; issuance record final.
- [ ] Consent/allowlist state unchanged unless separately approved.
