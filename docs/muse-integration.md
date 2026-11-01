# Muse integration

What Meta Muse Spark actually does in EdgeFit AI, how its access is
constrained, and what has been live-qualified.

## Role

Muse Spark (`muse-spark-1.3` at `https://api.meta.ai/v1/responses`) is
the prediction and analysis engine — never the store of record:

1. **Predict** — throughput estimate (gen/prefill tok/s) with
   uncertainty, persisted *before* the benchmark it predicts.
2. **Follow up** — post-measurement analysis citing only supplied run
   ids; states single-observation limits explicitly.
3. **Propose** — bounded agent-loop config suggestions; each needs
   explicit owner approval and none executes autonomously.

Evidence conditions inference in-context. Weights are never updated, no
fine-tuning occurs, and no EdgeFit data trains Meta models.

## Controlled tool calling (workstation)

- `scripts/ef_dispatch_05.py` / `scripts/ef_dispatch_06.py` enforce the
  EF-META-04 validation rules on tool arguments **before** any database
  access: fixed tool names (`retrieve_comparable_benchmarks`,
  `retrieve_physical_benchmarks`), exact fields, pinned value sets.
- `scripts/ef_retrieval_05.py` runs one fixed parameterized read-only
  query: deterministic order, bounded limit, provenance preserved.
  Model input never becomes SQL; no model-generated SQL exists anywhere.
- The physical path pins a COMPLETED filter, a narrow model-visible
  projection, and host-computed comparisons (the 37.57x figure is
  recomputed by the host, never trusted from the model).
- Live modules (`ef_meta_05_live.py`, `ef_meta_06_live.py`) allow at
  most one tool-call round trip and run only with `MODEL_API_KEY`
  present. Tests cover a 27-subcase invalid-argument matrix, injection
  rejection, prompt-injection isolation, and secret non-disclosure.

## On-device BYOK mode (Mode A)

- The app calls `api.meta.ai` directly with the developer's own key.
  No EdgeFit backend is contacted in this mode.
- Key custody: session-only by default (dies with the app); optional
  Keystore AES/GCM persistence; explicit Forget Key. The key never
  enters exports, logs, or session records.
- Transport: allowlisted HTTPS to the Meta endpoint only, with
  timeouts and size caps, no retries. Requests carry no tools.
- Every prediction, follow-up, and proposal answers one explicit
  authorization dialog. See `android/.../MetaApi.kt`,
  `MetaKeyVault.kt`, `MetaSessionClient.kt`.

Managed service mode (server-side key) is proposed and **not built**.

## Validated evidence retrieval

Retrieval responses cite aggregate/run ids the analysis must reference.
Raw submissions, install identities, and revoked rows are never visible
to the model. Study arms prove the discipline: in `eval/comp-01b`, the
M1 arm cited only manifested run ids (fabricated-citation rate 0.0),
and both model arms abstained rather than invent a comparison the
evidence could not support.

## Live qualifications (owner-executed)

| Check | Result |
|---|---|
| EF-META-05 synthetic round trip | PASS — model flagged evidence as synthetic |
| EF-META-06 physical round trip (Run A/B) | PASS with response-completeness caveat |
| BYOK full loop (predict → measure → follow-up) | PASS, one session |
| Agent proposal + authorized follow-up | PASS, one experiment |
| comp-01b M0/M1 selections | Correct abstentions with stated reasons |

Full record: [qualification-status.md](qualification-status.md).
Measured numbers: [validation-results.md](validation-results.md).
