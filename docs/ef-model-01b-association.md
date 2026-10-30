# EF-MODEL-01B: staged-artifact ↔ catalog association (DESIGN NOTE, owner approval required)

Status: PROPOSED. No code implements this note. The v1
catalog carries null SHA-256 for every entry, so no
catalog entry can match local evidence today — by design.

## Problem

A staged GGUF has a verified SHA-256 (computed at staging)
but no catalog identity. A catalog entry has a verified
source page but no verified hash. Joining them by model
name, filename, or approximate size would forge an
identity and MUST never happen: each of those collides
in practice (re-uploads, renames, same-size rebuilds).

## Rejected approaches

1. Filename match — rejected: filenames are user-
   controlled and collide across quantizers.
2. Display-size match — rejected: sizes collide and
   listings are approximate.
3. Model-name match — rejected: names are not identity
   (NFR-EVID-01: no filename-only association).
4. Silently persisting a "best guess" link — rejected:
   an unverified identity match must never be stored
   as fact.

## Proposed safe path (no new trust needed)

Owner-verified SHA backfill: the owner obtains each
catalog GGUF from its verified source page, hashes it
locally with an audited command, and the resulting
SHA-256 values ship in catalog v2 with
`verified_utc` + method recorded per entry. Existing
exact-match logic (`localEvidenceFor`,
`measuredLocally`) then activates with zero code
changes. This is curation work, not a contract change.

## Alternative requiring a new contract (STOPPED)

If runtime linking is wanted before backfill, the
minimal contract would be a user-asserted link record:

- `link_id`, `catalog_entry_id`, `artifact_sha256`,
  `asserted_by: "developer"`, `asserted_at_utc`,
  `method: "manual_user_confirmation"`.
- Displayed everywhere as USER-ASSERTED, never as
  verified; excluded from `verified_compatible`
  (which keeps requiring catalog-verified hashes).
- Explicit delete control; never synced or uploaded.

This needs owner approval for the new persistence
contract, UI wording, and qualification tests. NOT
IMPLEMENTED in EF-MODEL-01B. The recommended next
step remains SHA backfill, not this contract.
