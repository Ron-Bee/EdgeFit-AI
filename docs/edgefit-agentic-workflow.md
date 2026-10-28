# EdgeFit bounded agentic workflow (PROPOSED)

How Muse participates in EdgeFit optimization without becoming an
unbounded agent. This design extends workstation-qualified patterns
(EF-META-05/06 dispatch discipline, EF-LOOP-01 persistence and
deterministic errors) to Android BYOK operation. Conditioning Muse
with evidence is not training; ADR-014 applies to every publication.

Scope: the loop below governs **within-model configuration
optimization** — one staged GGUF, varying host-approved config
values (EF-AGENT-01 implements this on Android).
**Alternative-model discovery and selection** is a separate
PROPOSED scope (EF-MODEL-ARCH-01, FR-MODEL-01/02/03): same
host-authority rules, but cross-model comparison is a
recommendation view, never a pooled cohort — see
"Alternative-model selection" below.

## Roles

Muse MAY: inspect approved device/model evidence; retrieve
compatible measurements and public statistics through allowlisted
host tools; generate evidence-labeled predictions; recommend an
initial in-bounds configuration; evaluate measured results against
the persisted prediction using host-computed errors; state
uncertainty and unknown factors; recommend the next measurable
experiment; judge whether further testing is useful.

The Android host MUST: validate every model-proposed argument
against pinned schemas; enforce configuration bounds (contract v1
ranges, fixed greedy workload); preserve the original prediction
immutably; compute all comparison numbers deterministically;
restrict network access to the Meta endpoint — plus, only under
EF-MODEL-ARCH-01 (PROPOSED, ADR-019), explicitly authorized
anonymous public-evidence reads carrying no key and no private
measurements — and restrict local tool access to the allowlist;
require explicit user approval before each benchmark execution;
enforce a finite per-session experiment budget; label measured
improvements vs speculative recommendations; never execute
model-supplied code, shell, or SQL.

## Session loop (one approval per experiment)

1. User stages a model and requests a prediction (explicit tap).
2. Host assembles the evidence pack: session profile, model
   identity, config, compatible local measurements, public stats
   if reachable (labeled by tier; offline continues without them).
3. Muse returns a schema-validated prediction citing supplied ids.
4. Host persists the prediction + provenance BEFORE benchmarking.
5. User reviews and explicitly approves the benchmark run.
6. Phone executes the bounded native benchmark with live stages.
7. Host associates run↔prediction by explicit ids + hashes and
   computes deterministic errors.
8. User requests follow-up (explicit tap); Muse returns grounded
   analysis, recommendation, uncertainty, and next experiment.
9. Host decrements the experiment budget. If budget remains and
   the user approves, go to 5 with the recommended config;
   otherwise close the session with a report.

Budget exhaustion, user finish, or three consecutive non-improving
runs closes the loop. Owner-approved provisional budget: max 5
experiments per session, one benchmark per approval, no
auto-approval mode.

## Tool surface (host-enforced)

- `predict_performance(profile_ref, model_sha, config)` — returns
  a validated prediction; no execution side effects.
- `retrieve_compatible_measurements(filters)` — local session
  store + public stats, allowlisted fields, hard caps.
- `evaluate_run(run_id)` — returns host-computed errors; the
  model never computes comparison math.
- `recommend_experiment()` — returns a proposal the host
  validates; approval stays with the user.

No shell, no file writes outside the session store, no settings
changes, no silent uploads. Every tool call and its validation
result is recorded in the session log.

## Failure and recovery

Process death mid-run: the session store keeps prediction +
partial state; on relaunch the host offers resume (re-validate,
never silently continue a benchmark) or clean abandonment with
the reason recorded. Network failure: the call fails loud, the
budget is not charged, the session stays resumable. Invalid model
output: rejected with schema errors, one re-ask allowed per
stage, then the stage fails with user-visible cause.

## Alternative-model selection (EF-MODEL-ARCH-01, PROPOSED)

Within-model optimization varies config for one staged artifact;
alternative-model selection compares different artifacts and
therefore needs catalog identity, per-candidate cohorts, and a
model handoff the loop above never performs. The host's
compatibility and approval authority extends unchanged: only
host-screened candidates are actionable (FR-MODEL-01); Muse
citations are validated against supplied ids and false
`measured` claims are rejected (FR-MODEL-02); the developer
selects, stages, and authorizes exactly one benchmark per
approval with the prediction and its immutable evidence packet
committed first (FR-MODEL-03). The host never automatically
downloads, switches models, or runs an experiment. Cross-model
evidence packs carry source-labeled local measurements plus
PUBLISHED aggregates (or explicit insufficient-evidence); the
bounded pack and 5-experiment budget rules apply per
optimization session, and each newly staged model starts its
own evidence chain — measurements never migrate across model
SHAs. Nothing in this section is implemented.

## What this is not

Not autonomous optimization, not background experimentation, not
training or fine-tuning, not proof of honest execution. Every
recommendation is conditioned text; every number the user acts on
is host-measured or host-computed.
