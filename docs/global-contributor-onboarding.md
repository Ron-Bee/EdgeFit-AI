# Contribute to EdgeFit Global

Help build a real-world evidence network for on-device AI. Run benchmarks
on your own Android device and voluntarily contribute verified performance
measurements.

## Request Contributor Access

Contributor access requests are now accepted through the EdgeFit Global
registration form. Requests are manually reviewed. Approval and
credential issuance are not automatic.

**[Request Contributor Access](https://tally.so/r/Ge68P2)**

## What contributors are helping build

EdgeFit Global collects consented, per-device benchmark measurements
(real llama.cpp inference on real phones) into exact-cohort aggregates:
same device class, model, runtime, workload, and configuration — never
pooled across different hardware. Each accepted contribution moves through
validation, qualification, an exact-cohort quorum (**≥5 eligible runs from
≥3 distinct installs**), and Publication Approval B (currently **OFF**).
The [live dashboard](https://edgefit-ai.pages.dev/dashboard) shows the
current state: genuine staging contributions accepted, but no qualified
public cohorts published while the gate is closed.

## Who can request access

Any adult with a compatible physical Android device who agrees to the
contribution terms in this guide. Participation is voluntary: you
benchmark locally, you choose which sessions to submit, and you review
each contribution before explicitly authorizing its upload. No benchmarks
upload automatically, in the background, or without your tap on
**Approve and upload**.

## What you need

- A physical arm64-v8a Android device (minSdk 26; emulators are not
  eligible for publishable contributions).
- The Android app: the
  [v0.2.1-preview.1 pre-release](https://github.com/Ron-Bee/EdgeFit-AI/releases/tag/v0.2.1-preview.1)
  (verify the published SHA-256 before installing).
- A GGUF model file you provide yourself — the app never downloads
  models, and model files are never collected by EdgeFit Global.
- A unique per-install contribution credential, issued only after your
  request is reviewed and approved (one credential per installation; a
  reinstalled app is a new installation and needs a new credential).

You do **not** need a Meta API key to contribute benchmark evidence
(Meta features are optional, owner-keyed, and separate), and you must
never send one with your request.

## The registration form

The [EdgeFit Global registration form](https://tally.so/r/Ge68P2)
collects:

REQUIRED:

- Contact email (used only to review and respond to your request).
- Android device manufacturer/model.
- Android version.
- Agreement to be contacted about your request.

OPTIONAL:

- Contributor display name.
- GitHub username.
- Notes or questions.

The form never asks for: real name, home address, device serial number,
IMEI, an existing bearer [REDACTED] a Meta API key, a Google password, or
benchmark files. It does not approve contributors, generate credentials,
or upload evidence — it only submits your request for manual review.

Contact information submitted through the form is used solely to review
and respond to your access request, and only with your explicit
permission for that limited use. Form contact data is not benchmark
evidence, is never publicly visible, and never enters the benchmark
dataset or the public dashboard. Requests may be approved or denied; a
submitted request is not a credential.

## From request to first contribution

1. Download the compatible EdgeFit AI Android APK from the
   [pre-release](https://github.com/Ron-Bee/EdgeFit-AI/releases/tag/v0.2.1-preview.1).
2. Supply your own supported GGUF model file.
3. Run local benchmark measurements on your device.
4. Request contributor access via the
   [registration form](https://tally.so/r/Ge68P2).
5. Wait for manual owner review and approval.
6. Receive a unique installation credential through an approved
   private channel. It cannot be recovered if lost — only rotated.
7. Enter the credential in the app under Settings >
   **EdgeFit Global Connection** (session-only by default; optional
   Keystore-backed persistence; only a last-4 suffix is ever shown).
8. Select the completed sessions you want to contribute.
9. Review the exact contribution payload on-device.
10. Tap **Approve and upload** — the only action that sends data.
    The backend authenticates your install, validates the envelope,
    and returns a submission receipt.

## How issuance works (existing governed process)

Contribution credentials are minted exclusively by a human operator
through the repository's existing enrollment procedure. There is no
self-registration endpoint, no public token generator, and no automatic
issuance — this guide changes none of that. Each approval mints one
credential (fresh random installation id, 256-bit credential stored
server-side as a hash only, issuance recorded under an approval
reference with physical-device attestation) and delivers it once,
confidentially.

Credentials never appear in GitHub issues, form responses, URLs,
READMEs, dashboards, logs, screenshots, or analytics.

## How consent works

Every contribution is authorized under consent policy **ef-consent-01**,
scope `aggregates`, confirmed per batch at the moment you approve the
upload. There is no standing permission and no background submission.
Withdrawing consent for a submission marks it REVOKED — terminal,
excluded from every public read.

Each contribution carries only measurement evidence: throughput and
timings, token counts, memory signals, model identity (SHA-256, size,
quantization), runtime/build identity, workload and prompt hashes (never
prompt text), inference configuration, and device metadata (model
string, SoC, SDK level, ABIs, CPU count, RAM). No phone number, account
id, contacts, location, model files, or API keys are collected.

## Accepted versus published evidence

- **Accepted** means the backend stored your submission and returned a
  receipt. It is not a quality judgment.
- **Qualified** means the submission passed validation and the anomaly
  suite inside its exact cohort.
- **Published** means the cohort reached quorum (≥5 eligible runs from
  ≥3 distinct installs, exact match on every cohort dimension) with
  Publication Approval B on — and only then does an aggregate appear on
  the dashboard. Individual runs are never published; installs are
  counted as salted hashes, never exposed.

Accepted submissions are not automatically published as global results.
With Approval B OFF, no public cohorts are published.

## Safeguards that stay in force

Per-install authentication (revoked installs are rejected), explicit
upload consent, no background submission, provenance requirements
(server-assigned origin; synthetic rows permanently ineligible),
server-side validation, revocation, rate limits (20 uploads per install
per day; 120 requests per 60 s on the public reader), exact-cohort
qualification, and zero invented results. This onboarding changes no
authorization, publication-policy, or database logic.
