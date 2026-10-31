# EF-COMP-01B AgentReview Demo: Physical Acceptance Protocol
(PREPARED, NOT EXECUTED — owner-operated, PHONE REQUIRED)

Judge-facing product demonstration of the EXISTING
production Agent workflow on the Galaxy S22 Ultra:
proposal validation, host review, developer Modify,
explicit Approve & Run, physical benchmark, measured
outcome, authorized Muse follow-up, preserved history.

This is SEPARATE from the R/M0/M1 technical study
(`ef-comp-01b-stage-b.md`): the demo shows the product;
the study evidences the AI-value claim. Do not conflate
their artifacts.

## Prerequisites

- Benchmark APK installed (record version + build time).
- Same GGUF staged; model SHA verified on the run screen.
- Owner Meta API key entered (session-only OK); cost
  disclosure acknowledged per call.
- No EF-COMP-01B study runs in progress (separate days
  preferred; never interleave study + demo runs).

## Script (one Agent parent, ≤5 attempts)

1. VALID PROPOSAL + CAUTION. From an Agent parent with
   ≥1 completed measurement, Request Proposal. Expect:
   proposal validates; AgentReview renders SUPPORTED or
   CAUTION with explicit checks; Approve/Reject/Finish
   all reachable. Screenshot the review.
2. MODIFY + RE-REVIEW. Tap Modify, change one bounded
   value (e.g. threads 4→8 within device max). Expect:
   host re-validates, expected values flagged stale,
   review re-renders. Screenshot before/after.
3. INVALID BLOCKED (no execution). This step needs no
   live call: describe — or, if a live reply ever
   violates schema/bounds/identity, show — that approval
   is blocked until revised or rejected. Never force an
   invalid reply by editing records.
4. APPROVE & RUN. Approve exactly one experiment.
   Expect: one budget charge, child AUTHORIZED, explicit
   Run starts native inference with live progress.
5. MEASURED OUTCOME. On completion record run id,
   config executed (= approved), gen/prefill tok/s,
   thermal, memory %, host-computed comparison vs the
   carried prediction. Screenshot the result.
6. FOLLOW-UP. Request Meta Follow-Up (explicit
   authorization). Expect: validated assessment citing
   host errors, stored in the child; history preserved.
7. HISTORY. Reopen the parent: experiments, proposal
   hashes, rejections, budget, and terminal state all
   durable across app relaunch.

## Capture list

Run id(s), session/parent ids, before/after review
screenshots, result screenshot, follow-up screenshot,
any CAUTION/INVALID text verbatim. Failures are
evidence: record them as-is, never retry silently.

## Go / no-go

GO iff every step above completes with screenshots and
no step required record editing, budget reset, or an
unauthorized call. NO-GO on any crash, lost history,
phantom measurement, or silent state change — file as
a defect with the capture list.
