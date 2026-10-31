-- EF-GLOBAL-01 migration 002: atomic eligibility-audit trigger.
--
-- FREE-04 closes two confirmed audit defects in qualification
-- transitions (FREE-03): phantom history rows under overlapping
-- passes, and missing history rows when a crash lands between a
-- separate eligibility UPDATE and history INSERT. FREE-05 extends
-- the same atomicity to owner withdrawal, whose to-state-guarded
-- explicit INSERT could phantom under concurrent DELETEs. Every
-- guarded eligibility UPDATE is now a single batch; THIS trigger
-- appends its history row in the same statement, so a committed
-- transition can neither phantom (a CAS loser's 0-row UPDATE
-- fires nothing) nor lose its audit row (no inter-step window).
-- A failing trigger INSERT aborts the parent UPDATE itself:
-- eligibility and history stay atomic.
--
-- SCOPE: every eligibility UPDATE (qualification transitions and
-- owner revocation alike). The revoke path in api.js issues its
-- guarded UPDATE with NO explicit history INSERT; the trigger
-- writes the withdrawal row with reason 'consent withdrawn by
-- owner'. Submission intake uses INSERT, never UPDATE, and does
-- not fire this trigger. These are the only three eligibility
-- writers; verified by census in FREE-04 Phase B and FREE-05
-- Phase C.
--
-- REASON COUPLING: the CASE below is 1:1 with the target state
-- and must stay byte-identical to the documented reasons
-- ('schema recheck passed' / 'anomaly suite passed' /
-- 'consent current; published' / 'consent withdrawn by owner').
-- cloud/test/g2b-trigger.test.js asserts the exact strings;
-- changing a reason requires a new migration plus the test
-- update together.
--
-- The `at` stamp is commit time (strftime, ISO-8601 UTC millis,
-- same shape as the application Date.now() stamp it replaces).
--
-- D1 MIGRATION-SPLITTER RULES (honored here): uppercase block
-- keywords, a single INSERT body, and no inner block terminator.
-- The CREATE TRIGGER below is one statement: the body INSERT
-- carries the only inner semicolon, then the block closes. (These
-- comments deliberately never spell the block terminator, so a
-- naive splitter scanning comments cannot misfire on this file.)
CREATE TRIGGER IF NOT EXISTS trg_eligibility_audit
AFTER UPDATE OF eligibility ON submissions
WHEN OLD.eligibility <> NEW.eligibility
BEGIN
  INSERT INTO eligibility_history
    (submission_id, from_state, to_state, reason, at)
  VALUES (NEW.submission_id, OLD.eligibility, NEW.eligibility,
    CASE NEW.eligibility
      WHEN 'VALIDATED' THEN 'schema recheck passed'
      WHEN 'QUALIFIED' THEN 'anomaly suite passed'
      WHEN 'PUBLISHED' THEN 'consent current; published'
      WHEN 'REVOKED' THEN 'consent withdrawn by owner'
      ELSE 'eligibility transition'
    END,
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;
