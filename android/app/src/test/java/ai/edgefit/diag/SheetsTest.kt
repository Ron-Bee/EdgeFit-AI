package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the sheet-restore registry. */
class SheetsTest {

    @Test
    fun everyRegisteredIdResolves() {
        val args = mapOf(
            Sheets.MANUAL_CONFIG to "2",
            Sheets.RESULT_COMPARISON to "S-1",
            Sheets.IMPORT_VERDICT to "abc",
            Sheets.WORKLOAD to "result",
            Sheets.RECOVERY to "S-1",
            Sheets.BOUND_RECOVERY to "S-1",
            Sheets.SESSIONS to "S-1",
            Sheets.REPEAT_PREP to "S-1",
            Sheets.EVIDENCE_SESSION to "S-1",
            Sheets.SERIES_COMPARISON to "S-1",
            Sheets.ONBOARDING to "2",
            Sheets.AGENT to "A-1",
            Sheets.MODIFY_PROPOSAL to "A-1",
            Sheets.META_AUTH_PREDICT to "S-1",
            Sheets.META_AUTH_FOLLOWUP to "S-1",
            Sheets.META_AUTH_PROPOSAL to "A-1",
            Sheets.LIVE_PREDICTION to "ok:S-1",
            Sheets.FOLLOWUP_RESULT to "err:boom",
            Sheets.CONTRIBUTE_REVIEW to "R-1",
            Sheets.SUBMIT_OUTCOME to "{}",
            Sheets.SHARED_RECORD to "R-1",
            Sheets.HISTORY_ACTIONS to "R-1",
            Sheets.WITHDRAWAL to "R-1"
        )
        assertEquals(36, Sheets.ALL.size)
        for (id in Sheets.ALL) {
            val action = SheetRestore.actionFor(id, args[id])
            assertTrue("id $id must resolve", action != null)
        }
    }

    @Test
    fun unknownAndEmptyIdsResolveToNull() {
        assertNull(SheetRestore.actionFor(null, null))
        assertNull(SheetRestore.actionFor("", null))
        assertNull(SheetRestore.actionFor("nope", "x"))
    }

    @Test
    fun missingRequiredArgsResolveToNull() {
        assertNull(SheetRestore.actionFor(Sheets.RESULT_COMPARISON, null))
        assertNull(SheetRestore.actionFor(Sheets.BOUND_RECOVERY, ""))
        assertNull(SheetRestore.actionFor(Sheets.REPEAT_PREP, null))
        assertNull(SheetRestore.actionFor(Sheets.EVIDENCE_SESSION, null))
        assertNull(SheetRestore.actionFor(Sheets.SERIES_COMPARISON, ""))
        assertNull(SheetRestore.actionFor(Sheets.MODIFY_PROPOSAL, null))
        assertNull(SheetRestore.actionFor(Sheets.META_AUTH_FOLLOWUP, null))
        assertNull(SheetRestore.actionFor(Sheets.META_AUTH_PROPOSAL, ""))
        assertNull(SheetRestore.actionFor(Sheets.LIVE_PREDICTION, "bogus"))
        assertNull(SheetRestore.actionFor(Sheets.FOLLOWUP_RESULT, "ok:"))
        assertNull(SheetRestore.actionFor(Sheets.CONTRIBUTE_REVIEW, null))
        assertNull(SheetRestore.actionFor(Sheets.SUBMIT_OUTCOME, ""))
        assertNull(SheetRestore.actionFor(Sheets.SHARED_RECORD, null))
        assertNull(SheetRestore.actionFor(Sheets.HISTORY_ACTIONS, ""))
        assertNull(SheetRestore.actionFor(Sheets.WITHDRAWAL, null))
    }

    @Test
    fun optionalArgsDefaultHonestly() {
        assertEquals(SheetAction.Workload("current"),
            SheetRestore.actionFor(Sheets.WORKLOAD, null))
        assertEquals(SheetAction.Workload("result"),
            SheetRestore.actionFor(Sheets.WORKLOAD, "result"))
        assertEquals(SheetAction.Onboarding(0),
            SheetRestore.actionFor(Sheets.ONBOARDING, null))
        assertEquals(SheetAction.Onboarding(0),
            SheetRestore.actionFor(Sheets.ONBOARDING, "bogus"))
        assertEquals(SheetAction.ManualConfig(null),
            SheetRestore.actionFor(Sheets.MANUAL_CONFIG, null))
        assertEquals(SheetAction.Sessions(null),
            SheetRestore.actionFor(Sheets.SESSIONS, null))
    }

    @Test
    fun submitOutcomeCodecRoundTrip() {
        val outcomes = listOf(
            GlobalClient.SubmitOutcome.Shared("sub-1", "ok", false),
            GlobalClient.SubmitOutcome.Shared("sub-2", "ok", true),
            GlobalClient.SubmitOutcome.Rejected(
                "C", "m", listOf("d1", "d2")),
            GlobalClient.SubmitOutcome.AuthFailed("401", "no"),
            GlobalClient.SubmitOutcome.Quota(30L),
            GlobalClient.SubmitOutcome.Quota(null),
            GlobalClient.SubmitOutcome.Uncertain("net down")
        )
        for (outcome in outcomes) {
            val encoded = SubmitOutcomeArg.encode(outcome)!!
            assertEquals(outcome, SubmitOutcomeArg.decode(encoded))
        }
        // Blocked never opens a sheet: no encoding exists.
        assertNull(SubmitOutcomeArg.encode(
            GlobalClient.SubmitOutcome.Blocked("x")))
        assertNull(SubmitOutcomeArg.decode("{oops"))
        assertNull(SubmitOutcomeArg.decode(null))
    }
}
