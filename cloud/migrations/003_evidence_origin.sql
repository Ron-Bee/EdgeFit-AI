-- EF-GLOBAL-01 migration 003: durable evidence provenance.
--
-- Before this migration the database records NO evidence origin:
-- device-contributed and synthetic/test rows are indistinguishable,
-- and the publication allowlist is the only (config-content) wall
-- between synthetic staging rows and public output. This migration
-- adds server-controlled, immutable provenance:
--
-- * installs.kind: the credential class assigned once, at trusted
--   operator issuance. Never client-chosen, never changed after.
-- * submissions.origin: copied from the install's kind by the
--   submission INSERT itself (pure-SQL subselect, atomic with the
--   row). Never taken from the request envelope.
--
-- Value space (CHECK-enforced on both columns):
-- * 'device_contribution' -- the ONLY origin eligible for genuine
--   public publication (conjoined with the consent allowlist).
-- * 'synthetic_test' -- operator test harnesses. Never publishable.
-- * 'legacy_untrusted' -- everything predating this migration plus
--   the fail-closed default. Never publishable, never reclassified.
--
-- Pre-existing rows backfill to 'legacy_untrusted' via the column
-- DEFAULT: no data-dependent rewrite, no risk of misclassifying
-- history as genuine. The staging synthetic rows stay exactly as
-- they are, permanently ineligible.
--
-- Enforcement is three-deep: application code assigns and filters
-- by origin; CHECK constrains the value space; triggers bind each
-- submission to its install's kind and freeze both columns forever.
-- A failing trigger body aborts its parent statement, so a bug or
-- a rogue operator INSERT fails loudly instead of corrupting
-- provenance. Eligibility-only UPDATEs never touch origin, so the
-- 002 audit trigger is unaffected (qualified by coexistence tests).
--
-- Repeat application: CREATE TRIGGERs below are IF NOT EXISTS and
-- re-apply cleanly. ALTER TABLE has no IF NOT EXISTS in SQLite, so
-- re-running this FILE is guarded by the migration journal
-- (apply-local.mjs records applied files; wrangler tracks remote
-- applications in d1_migrations). Never hand-edit this file after
-- it is applied anywhere: later changes need a new migration.

ALTER TABLE installs ADD COLUMN kind TEXT NOT NULL
  DEFAULT 'legacy_untrusted'
  CHECK (kind IN ('device_contribution', 'synthetic_test',
    'legacy_untrusted'));

ALTER TABLE submissions ADD COLUMN origin TEXT NOT NULL
  DEFAULT 'legacy_untrusted'
  CHECK (origin IN ('device_contribution', 'synthetic_test',
    'legacy_untrusted'));

-- A submission's origin must equal its install's kind. Catches
-- application bugs and hand-written operator INSERTs that stamp
-- the wrong class; the mismatch aborts the whole INSERT.
CREATE TRIGGER IF NOT EXISTS trg_submission_origin_check
AFTER INSERT ON submissions
WHEN (SELECT kind FROM installs
      WHERE install_id = NEW.install_id) IS NOT NEW.origin
BEGIN
  SELECT RAISE(ABORT, 'submission origin must match install kind');
END;

-- Provenance is write-once: no UPDATE may change a submission's
-- origin (or an install's kind) after creation. Eligibility,
-- revocation, and quota UPDATEs never touch these columns, so
-- their WHEN clauses stay false and they add no behavior there.
CREATE TRIGGER IF NOT EXISTS trg_submission_origin_frozen
AFTER UPDATE ON submissions
WHEN OLD.origin IS NOT NEW.origin
BEGIN
  SELECT RAISE(ABORT, 'submission origin is immutable');
END;

CREATE TRIGGER IF NOT EXISTS trg_install_kind_frozen
AFTER UPDATE ON installs
WHEN OLD.kind IS NOT NEW.kind
BEGIN
  SELECT RAISE(ABORT, 'install kind is immutable');
END;
