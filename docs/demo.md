# Demo

## Demo videos

**Extended, unedited Android demonstration (10:09, November 3, 2026):** [Watch on YouTube](https://youtu.be/8IhWPMv60Gw). This is an earlier-build continuous technical capture, not the official hackathon short submission.

**Official 2:55 hackathon submission demo:** [Watch on YouTube](https://youtu.be/KyUwmhIL6SM).

## Demonstration flow (live version; official submission video is max THREE minutes)

The official hackathon submission video must be THREE minutes maximum ([published 2:55 video](https://youtu.be/KyUwmhIL6SM)). This script is the longer live walkthrough version for the Galaxy S22 Ultra with the
workstation alongside. Steps 1–2 and 5 are fully offline; steps 3–4
need a Meta API key only if run live (otherwise narrate over the
recorded session evidence).

**0:00–0:45 — Home: the device tells the truth.**
Open EdgeFit AI. Point out the device card (model, Android level,
cores, RAM), live telemetry (battery, temperature, available RAM),
and the staged-model state. Note: zero permissions, zero network.

**0:45–2:00 — Benchmark: measure, don't guess.**
Stage the GGUF, run the memory-readiness check, select a config, and
run. Show the live token chart, then the completed result: measured
prefill/generation tok/s with thermal and memory conditions.
Emphasize the run id — every number is traceable.

**2:00–3:00 — Predict first, then compare.**
Show a session where the Muse prediction was recorded *before* the
benchmark, then the deterministic comparison (signed error and
percentage computed by the host). Open the measured-vs-predicted
detail cards and the UTC evidence block.

**3:00–4:00 — Agent loop with a human gate.**
Request an agent proposal, show the cited baseline evidence, then
approve or reject explicitly. Stress the budget and termination rules:
nothing runs without approval.

**4:00–4:45 — Contribute with consent.**
Open the Global contribution review for a finished run: provenance,
hashes, what is sent, and the aggregates-only scope. (The backend is
deployed, but live submission needs a per-install credential plus
approved consent, neither granted yet -- say so plainly, and show the live empty dashboard.)

**4:45–5:00 — Close on evidence.**
Recap the loop on one slide: predict → measure → compare → contribute.
Cite the comp-01b medians (A 16.52 vs B 10.49 tok/s) and the honest
M0/M1 abstentions as the punchline: the system refuses to claim more
than its evidence supports.
