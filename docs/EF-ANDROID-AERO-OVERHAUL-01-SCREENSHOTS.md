# EF-ANDROID-AERO-OVERHAUL-01 — Screenshot Capture Guide

Deliverable E. Real-device captures only (Galaxy S22
Ultra, portrait), after owner approval. No synthetic
data, no cleared app state, no staged fixtures: every
screen below is captured as the app honestly renders
it. If a state cannot be reached without fabricating
data, skip that capture and note why.

## Capture list (in priority order)

1. **Redesigned home (Decision Card hero).** Home tab,
   scrolled top: hero glow card + Decision Card with
   state chip. Requires: any staged model (measured
   preferred — shows the teal MEASURED chip and full
   evidence rows).
2. **Decision Card, estimated state.** Same framing on
   a staged-but-unmeasured model: violet "Estimated
   fit" chip, ESTIMATE-labeled expected row, honest
   "No completed run" measured row.
3. **Benchmark result hero + comparison block.**
   Benchmark tab after a completed bound run: large
   measured figure, "PREDICTED VS MEASURED" block with
   violet estimate / teal measured / stored-error
   difference rows.
4. **Predicted-vs-measured, refused case.** A run
   whose stored comparison is noncomparable: amber
   "Not comparable (reason)" difference row. Requires
   a real refused-comparison record; do not force one.
5. **Meta AI proposal review stepper.** Agent tab with
   a pending proposal: six-dot workflow strip with
   "Awaiting your review" current step above the
   existing cards.
6. **Human authorization (unchanged behavior).**
   The existing proposal dialog with its exact
   Approve/Reject/Later controls — proves the safety
   boundary survived the redesign.
7. **Global Evidence journey strip.** Benchmark tab
   "Results and Contribute" section: four-node strip
   with lit stages, consent/receipt/server lines, and
   (when a receipt exists) the NOT-publication honesty
   line.
8. **Contribution receipt (existing dialog).**
   Submission receipt dialog — unchanged copy, now
   reachable from a clearer entry point.

## General rules

- Portrait phone capture composed for a landscape
  video frame; keep status bar clock unobtrusive.
- Capture default states first (tiles collapsed on
  Home); take one expanded-tiles variant if time
  allows.
- Never uninstall, clear data, rerun benchmarks, or
  switch variants for a shot without explicit owner
  authorization per capture.
