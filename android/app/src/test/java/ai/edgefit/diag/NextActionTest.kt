package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests for UX-01A next-action priority. */
class NextActionTest {

    private fun state(
        modelStaged: Boolean = false,
        hasCompletedResult: Boolean = false,
        pendingProposalReview: Boolean = false,
        comparisonAwaitingFollowUp: Boolean = false
    ) = NextActionState(
        modelStaged, hasCompletedResult,
        pendingProposalReview, comparisonAwaitingFollowUp
    )

    @Test
    fun freshStateSelectsModel() {
        assertEquals(NextAction.SELECT_MODEL, nextAction(state()))
    }

    @Test
    fun stagedModelWithoutResultRuns() {
        assertEquals(NextAction.RUN_BENCHMARK,
            nextAction(state(modelStaged = true)))
    }

    @Test
    fun pendingProposalOutranksFreshRun() {
        assertEquals(NextAction.REVIEW_PROPOSAL,
            nextAction(state(
                modelStaged = true, pendingProposalReview = true)))
    }

    @Test
    fun followUpOutranksFreshRun() {
        assertEquals(NextAction.REQUEST_FOLLOW_UP,
            nextAction(state(
                modelStaged = true,
                comparisonAwaitingFollowUp = true)))
    }

    @Test
    fun completedResultViewsResults() {
        assertEquals(NextAction.VIEW_RESULTS,
            nextAction(state(
                modelStaged = true, hasCompletedResult = true)))
    }

    @Test
    fun labelsAreNonBlank() {
        for (action in NextAction.values()) {
            assertEquals(false, NextAction.label(action).isBlank())
        }
    }
}
