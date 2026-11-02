# FLEET-DASHBOARD-01 audit — why the dashboard is empty (2026-11-02Z)

Live D1 + deployment evidence. Three coexisting causes, no single fix.

## 1. Data present and valid (NOT the cause)

- 3 active genuine installs: `a55897ca` SM-S908U (8 subs), `62e0b493`
  SM-S906U (10 subs), `a9410397` Pixel XL (10 subs). 2 revoked
  rotations hold 0 rows each — not counted as devices.
- 28/28 genuine rows: `origin=device_contribution`, consent
  `ef-consent-01`/`[aggregates]`, status COMPLETED, 28 distinct
  run_ids, 28 distinct payloads (zero duplicates).
- Eligibility: 10 QUALIFIED, 18 SUBMITTED, 0 PUBLISHED, 0 REVOKED.

## 2. No cohort meets 5-run / 3-install quorum (cause B)

Same model/workload/prompt/runtime/build everywhere; cohorts split
by device_class x config_hash. Best cohorts: SM-S908U/64e875bc73 =
7 runs / 1 install; SM-S906U/64e875bc73 = 5 runs / 1 install;
Pixel XL best = 4 runs / 1 install. Max installs in ANY cohort: 1.
Cross-device pooling is forbidden (device_class is a cohort member),
so the three phones' 28 runs cannot combine.

## 3. Scheduler starvation defect (cause C — PROVEN backend defect)

`runQualification` takes the 10 oldest rows in
(SUBMITTED, VALIDATED, QUALIFIED) per tick. The 10 QUALIFIED rows
hold forever on the empty publication allowlist (correct hold) but
still consume all 10 slots: 0 SUBMITTED rows predate the newest
QUALIFIED (04:41:15Z), so all 18 SUBMITTED rows starve. Zero
promotions since 04:50:09Z (~6+ empty ticks). Fail-safe (no wrong
promotion) but unfair. Fix (NOT yet authorized): prioritize
SUBMITTED/VALIDATED ahead of held QUALIFIED, host-test, redeploy.

## 4. Publication B OFF (cause A) + deployment drift (cause E)

- Approval: A recorded, B `[ ]` NOT APPROVED. Nothing can publish
  regardless of quorum. B activation needs a separate explicit
  owner approval + allowlist deploy (packet to be prepared).
- Deployed versions differ from MUSE-B records: staging
  `f5aabd93` (was `049c82b7`), public `30fbed8a` (was `34e66243`);
  ingest `a23f6c4b` matches. Behavior spot-checks pass (empty
  aggregates envelope, POST 405, unknown-route 404, dashboard 200),
  but staging/public provenance is unverified — re-establish by
  redeploying from a verified HEAD together with the scheduler fix.

## 5. Dashboard (not the cause)

Dashboard 200, public API valid-and-empty. Empty display is the
CORRECT output of gates A+B+C, not a rendering defect.

## Gate sequence to first public aggregate

1. Authorize + deploy scheduler fairness fix (unblocks 18 rows).
2. Run same-config benchmarks to reach 5 runs on ONE model
   (closest: SM-S908U/64e875bc73 already has 7 — needs 2 more
   SM-S908U installs; or Pixel XL/64e875bc73 needs 1 more run +
   2 more Pixel installs), then qualify to QUALIFIED.
3. Owner grants Approval B + allowlist deploy → PUBLISHED →
   dashboard populates.
