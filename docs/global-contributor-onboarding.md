# Contribute to EdgeFit Global

Help build a real-world evidence network for on-device AI. Run benchmarks
on your own Android device and voluntarily contribute verified performance
measurements.

## Request status (read first)

> **2026-11-04: the public request channel is pending owner setup.**
> This guide documents the complete, genuine procedure so an outside user
> can see exactly how contributor access works. The request endpoint below
> is a **draft** until the owner approves a contact destination — no access
> requests are being accepted yet, and nothing here issues a credential.
> Approval is manual and never automatic.

## What contributors are helping build

EdgeFit Global collects consented, per-device benchmark measurements
(real llama.cpp inference on real phones) into exact-cohort aggregates:
same device class, model, runtime, workload, and configuration — never
pooled across different hardware. Each accepted contribution moves through
validation, qualification, an exact-cohort quorum (**≥5 eligible runs from
≥3 distinct installs**), and Publication Approval B (currently **OFF**).
The [live dashboard](https://edgefit-ai.pages.dev/dashboard) shows the
current state: staging contributions received, zero public cohorts
published — the empty state is the correct output until a cohort clears
every gate.

## Who can request access

Any adult with a compatible physical Android device who agrees to the
contribution terms below. Participation is voluntary: you benchmark
locally, you choose which sessions to submit, and you review each
contribution before explicitly authorizing its upload. No benchmarks
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

## How to request a credential

**Request Contributor Access** — *draft endpoint, pending owner
approval of a contact destination.*

When the channel opens, a request contains:

REQUIRED:

- Contact email (used only to review and respond to your request).
- Android device manufacturer/model.
- Android version.
- Request for one device installation.
- Agreement to the contribution terms in this guide.

OPTIONAL:

- Contributor display name.
- GitHub username.
- Notes or questions.

Never required: real name, home address, device serial number, IMEI,
an existing bearer [REDACTED] a Meta API key, a Google password, or benchmark
files. Do not send credentials of any kind with your request.

### Draft email request (do not send yet)

```text
To: [OWNER-APPROVED ADDRESS — TO BE PUBLISHED; NO REQUESTS ACCEPTED YET]
Subject: EdgeFit Global — Contributor Access Request

Hello EdgeFit AI team,

I would like to request access to contribute
benchmark evidence to EdgeFit Global.

Android device model:
Android version:
GitHub username (optional):

I understand that:
- Access requires approval.
- A unique per-install credential is required.
- Benchmark contributions require my consent.
- Accepted submissions are not guaranteed
  public publication.

Thank you.
```

Contact information submitted with a request is used only to review and
respond to that request, and only with your explicit permission for that
limited use. It never enters the benchmark dataset or the public
dashboard. Approval is manual; requests may be approved or denied, and a
submitted request is not a credential.

## How issuance works (existing governed process)

Contribution credentials are minted exclusively by a human operator
through the repository's existing enrollment procedure. There is no
self-registration endpoint, no public token generator, and no automatic
issuance — this guide changes none of that.

1. The owner reviews your contributor request.
2. The owner approves or denies enrollment.
3. For approvals, the owner mints one credential with the existing
   operator issuance tool: a fresh random installation id, a 256-bit
   credential stored server-side as a hash only, the issuance recorded
   under an approval reference with physical-device attestation.
4. The credential is delivered to you once, confidentially. It cannot
   be recovered if lost — only rotated.
5. You paste it into the app under Settings >
   **EdgeFit Global Connection** (session-only by default; optional
   Keystore-backed persistence; only a last-4 suffix is ever shown).
6. You run benchmarks locally and choose which sessions to contribute.
7. You review the exact contribution payload on-device.
8. You tap **Approve and upload** — the only action that sends data.
9. The backend authenticates your install, validates the envelope, and
   returns a submission receipt.
10. Accepted evidence proceeds through qualification toward (not onto)
    the public dashboard, per the gates below.

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
With Approval B OFF, the dashboard's zero-publication state is correct.

## Safeguards that stay in force

Per-install authentication (revoked installs are rejected), explicit
upload consent, no background submission, provenance requirements
(server-assigned origin; synthetic rows permanently ineligible),
server-side validation, revocation, rate limits (20 uploads per install
per day; 120 requests per 60 s on the public reader), exact-cohort
qualification, and zero invented results. This onboarding changes no
authorization, publication-policy, or database logic.
