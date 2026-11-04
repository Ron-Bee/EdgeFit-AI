# Google Play distribution note (EF-REPO-JUDGE-01, 2026-11-01)

## Why EdgeFit AI is not on Google Play yet

Two independent reasons, either sufficient:

1. **No signed release candidate exists.** All current APKs are
   debuggable owner-testing builds. See
   [release-readiness](release-readiness.md).
2. **The hackathon window does not fit the store timeline.**
   Even with a signed build, Play requires policy review, a data
   safety form, rollout planning, and — for applicable accounts —
   the closed-testing gate below.

## The closed-testing gate (applies to some accounts, not all)

Google's rule applies to **personal developer accounts created
after November 13, 2023** — not to every developer account:

- Run a **closed test** (the closed track — internal testing does
  not count) with **at least 12 testers opted in continuously for
  at least 14 days**.
- Only then may the developer **apply for production access** from
  the Play Console dashboard; the 14 days enable the application,
  not the outcome.

Whether the owner's account is subject to this requirement is
**not verifiable from this repository** — it depends on the
account type and creation date in Play Console.

- **Owner action:** check Play Console → Dashboard for the
  "closed test" production-access task. If present, the 12-tester /
  14-day clock plus review time exceeds the hackathon window.
- This document must not be edited to assert applicability until
  the owner confirms it.

## Planned path

Direct GitHub Releases distribution is the intended preview
channel (same package `ai.edgefit.diag` and same signing key as
the future Play track, so installs upgrade cleanly), with store
publication after release qualification completes. No Play
availability or approval is claimed before it occurs.
