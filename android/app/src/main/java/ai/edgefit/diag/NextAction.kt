package ai.edgefit.diag

/**
 * UX-01A next-action derivation (ef-ux-01-design.md §C.3):
 * exactly one suggested action from shell state. Pure for
 * unit tests; the activity fills the state from staged
 * model, last result, and session records.
 */
enum class NextAction {
    SELECT_MODEL,
    RUN_BENCHMARK,
    REVIEW_PROPOSAL,
    REQUEST_FOLLOW_UP,
    VIEW_RESULTS;

    companion object {
        fun label(action: NextAction): String = when (action) {
            SELECT_MODEL -> "Next: select a model file"
            RUN_BENCHMARK -> "Next: run benchmark"
            REVIEW_PROPOSAL -> "Next: review pending proposal"
            REQUEST_FOLLOW_UP -> "Next: request follow-up"
            VIEW_RESULTS -> "Next: view session results"
        }
    }
}

data class NextActionState(
    val modelStaged: Boolean,
    val hasCompletedResult: Boolean,
    val pendingProposalReview: Boolean,
    val comparisonAwaitingFollowUp: Boolean
)

/**
 * Priority: stage a model first; a waiting proposal or
 * follow-up outranks a fresh run; otherwise run, then
 * review results.
 */
fun nextAction(state: NextActionState): NextAction = when {
    !state.modelStaged -> NextAction.SELECT_MODEL
    state.pendingProposalReview -> NextAction.REVIEW_PROPOSAL
    state.comparisonAwaitingFollowUp -> NextAction.REQUEST_FOLLOW_UP
    !state.hasCompletedResult -> NextAction.RUN_BENCHMARK
    else -> NextAction.VIEW_RESULTS
}
