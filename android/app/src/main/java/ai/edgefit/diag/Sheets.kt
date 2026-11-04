package ai.edgefit.diag

/**
 * Restorable dialog-sheet registry
 * (EF-ANDROID-LIFECYCLE-PERSISTENCE-P0-01). Pure JVM: the
 * activity tracks the open sheet as an id plus one opaque
 * string arg, and [SheetRestore.actionFor] validates that
 * pair back into a typed restore action. Unknown ids and
 * missing required args resolve to null (the screen is
 * skipped, never invented). The activity executes the
 * returned action by reopening the same sheet constructor
 * with the same arguments.
 */
object Sheets {
    const val DETAILS = "details"
    const val MANUAL_CONFIG = "manual_config"
    const val RESULT_DETAILS = "result_details"
    const val RESULT_COMPARISON = "result_comparison"
    const val IMPORT_VERDICT = "import_verdict"
    const val MODEL_SOURCES = "model_sources"
    const val WORKLOAD = "workload"
    const val FAILURE_DETAILS = "failure_details"
    const val RECOVERY = "recovery"
    const val BOUND_RECOVERY = "bound_recovery"
    const val UNBOUND_RECOVERY = "unbound_recovery"
    const val SESSIONS = "sessions"
    const val REPEAT_PREP = "repeat_prep"
    const val MODEL_CATALOG = "model_catalog"
    const val EVIDENCE_SESSION = "evidence_session"
    const val SERIES_COMPARISON = "series_comparison"
    const val MEM_READINESS = "mem_readiness"
    const val MEM_DIAG = "mem_diag"
    const val MEM_SAMPLES = "mem_samples"
    const val HELP = "help"
    const val ONBOARDING = "onboarding"
    const val AGENT = "agent"
    const val MODIFY_PROPOSAL = "modify_proposal"
    const val META_SETTINGS = "meta_settings"
    const val META_AUTH_PREDICT = "meta_auth_predict"
    const val META_AUTH_FOLLOWUP = "meta_auth_followup"
    const val META_AUTH_PROPOSAL = "meta_auth_proposal"
    const val LIVE_PREDICTION = "live_prediction"
    const val FOLLOWUP_RESULT = "followup_result"
    const val GLOBAL_CONNECTION = "global_connection"
    const val CONTRIBUTE_REVIEW = "contribute_review"
    const val SUBMIT_OUTCOME = "submit_outcome"
    const val SHARED_RECORD = "shared_record"
    const val HISTORY = "history"
    const val HISTORY_ACTIONS = "history_actions"
    const val WITHDRAWAL = "withdrawal"

    val ALL: Set<String> = setOf(
        DETAILS, MANUAL_CONFIG, RESULT_DETAILS,
        RESULT_COMPARISON, IMPORT_VERDICT, MODEL_SOURCES,
        WORKLOAD, FAILURE_DETAILS, RECOVERY,
        BOUND_RECOVERY, UNBOUND_RECOVERY, SESSIONS,
        REPEAT_PREP, MODEL_CATALOG, EVIDENCE_SESSION,
        SERIES_COMPARISON, MEM_READINESS, MEM_DIAG,
        MEM_SAMPLES, HELP, ONBOARDING, AGENT,
        MODIFY_PROPOSAL, META_SETTINGS, META_AUTH_PREDICT,
        META_AUTH_FOLLOWUP, META_AUTH_PROPOSAL,
        LIVE_PREDICTION, FOLLOWUP_RESULT,
        GLOBAL_CONNECTION, CONTRIBUTE_REVIEW,
        SUBMIT_OUTCOME, SHARED_RECORD, HISTORY,
        HISTORY_ACTIONS, WITHDRAWAL
    )
}

/** Validated restore actions, one per [Sheets] id. */
sealed interface SheetAction {
    data object Details : SheetAction
    data class ManualConfig(val checked: Int?) : SheetAction
    data object ResultDetails : SheetAction
    data class ResultComparison(val sessionId: String) : SheetAction
    data class ImportVerdict(val sha256: String?) : SheetAction
    data object ModelSources : SheetAction
    /** [flavor] is "result" or "current". */
    data class Workload(val flavor: String) : SheetAction
    data object FailureDetails : SheetAction
    data class Recovery(val sourceId: String?) : SheetAction
    data class BoundRecovery(val sessionId: String) : SheetAction
    data object UnboundRecovery : SheetAction
    data class Sessions(val focusId: String?) : SheetAction
    data class RepeatPrep(val id: String) : SheetAction
    data object ModelCatalog : SheetAction
    data class EvidenceSession(val id: String) : SheetAction
    data class SeriesComparison(val id: String) : SheetAction
    data object MemReadiness : SheetAction
    data object MemDiag : SheetAction
    data object MemSamples : SheetAction
    data object Help : SheetAction
    data class Onboarding(val page: Int) : SheetAction
    data class Agent(val selectedId: String?) : SheetAction
    data class ModifyProposal(val parentId: String) : SheetAction
    data object MetaSettings : SheetAction
    data class MetaAuthPredict(val existingId: String?) : SheetAction
    data class MetaAuthFollowup(val sessionId: String) : SheetAction
    data class MetaAuthProposal(val parentId: String) : SheetAction
    /**
     * Recorded Meta outcomes. [mode] is "ok" (recompute
     * from the durable session [ref]) or "err" ([ref] is
     * the recorded failure text, shown verbatim).
     */
    data class LivePrediction(val mode: String, val ref: String) :
        SheetAction
    data class FollowupResult(val mode: String, val ref: String) :
        SheetAction
    data object GlobalConnection : SheetAction
    data class ContributeReview(val runId: String) : SheetAction
    data class SubmitOutcome(val encoded: String) : SheetAction
    data class SharedRecord(val runId: String) : SheetAction
    data object History : SheetAction
    data class HistoryActions(val id: String) : SheetAction
    data class Withdrawal(val id: String) : SheetAction
}

object SheetRestore {
    /**
     * Validates an (id, arg) pair. Null id, unknown id, or a
     * missing required arg resolves to null: the caller
     * skips restoration instead of opening the wrong
     * screen.
     */
    fun actionFor(id: String?, arg: String?): SheetAction? {
        return when (id) {
            null -> null
            Sheets.DETAILS -> SheetAction.Details
            Sheets.MANUAL_CONFIG ->
                SheetAction.ManualConfig(arg?.toIntOrNull())
            Sheets.RESULT_DETAILS -> SheetAction.ResultDetails
            Sheets.RESULT_COMPARISON ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.ResultComparison(arg)
            Sheets.IMPORT_VERDICT -> SheetAction.ImportVerdict(arg)
            Sheets.MODEL_SOURCES -> SheetAction.ModelSources
            Sheets.WORKLOAD ->
                SheetAction.Workload(
                    if (arg == "result") "result" else "current")
            Sheets.FAILURE_DETAILS -> SheetAction.FailureDetails
            Sheets.RECOVERY -> SheetAction.Recovery(arg)
            Sheets.BOUND_RECOVERY ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.BoundRecovery(arg)
            Sheets.UNBOUND_RECOVERY -> SheetAction.UnboundRecovery
            Sheets.SESSIONS -> SheetAction.Sessions(arg)
            Sheets.REPEAT_PREP ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.RepeatPrep(arg)
            Sheets.MODEL_CATALOG -> SheetAction.ModelCatalog
            Sheets.EVIDENCE_SESSION ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.EvidenceSession(arg)
            Sheets.SERIES_COMPARISON ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.SeriesComparison(arg)
            Sheets.MEM_READINESS -> SheetAction.MemReadiness
            Sheets.MEM_DIAG -> SheetAction.MemDiag
            Sheets.MEM_SAMPLES -> SheetAction.MemSamples
            Sheets.HELP -> SheetAction.Help
            Sheets.ONBOARDING ->
                SheetAction.Onboarding(arg?.toIntOrNull() ?: 0)
            Sheets.AGENT -> SheetAction.Agent(arg)
            Sheets.MODIFY_PROPOSAL ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.ModifyProposal(arg)
            Sheets.META_SETTINGS -> SheetAction.MetaSettings
            Sheets.META_AUTH_PREDICT ->
                SheetAction.MetaAuthPredict(arg)
            Sheets.META_AUTH_FOLLOWUP ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.MetaAuthFollowup(arg)
            Sheets.META_AUTH_PROPOSAL ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.MetaAuthProposal(arg)
            Sheets.LIVE_PREDICTION -> splitMode(arg)?.let {
                SheetAction.LivePrediction(it.first, it.second)
            }
            Sheets.FOLLOWUP_RESULT -> splitMode(arg)?.let {
                SheetAction.FollowupResult(it.first, it.second)
            }
            Sheets.GLOBAL_CONNECTION -> SheetAction.GlobalConnection
            Sheets.CONTRIBUTE_REVIEW ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.ContributeReview(arg)
            Sheets.SUBMIT_OUTCOME ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.SubmitOutcome(arg)
            Sheets.SHARED_RECORD ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.SharedRecord(arg)
            Sheets.HISTORY -> SheetAction.History
            Sheets.HISTORY_ACTIONS ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.HistoryActions(arg)
            Sheets.WITHDRAWAL ->
                if (arg.isNullOrEmpty()) null
                else SheetAction.Withdrawal(arg)
            else -> null
        }
    }

    private fun splitMode(arg: String?): Pair<String, String>? {
        if (arg == null) return null
        val mode = arg.substringBefore(":", "")
        val ref = arg.substringAfter(":", "")
        if ((mode != "ok" && mode != "err") || ref.isEmpty()) {
            return null
        }
        return mode to ref
    }
}

/**
 * Sheet-arg codec for [GlobalClient.SubmitOutcome]: the
 * outcome sheet restores the same receipt/rejection text.
 * Blocked outcomes never open a sheet (status line only),
 * so they have no encoding.
 */
object SubmitOutcomeArg {
    fun encode(outcome: GlobalClient.SubmitOutcome): String? {
        val map: Map<String, Any?> = when (outcome) {
            is GlobalClient.SubmitOutcome.Shared -> linkedMapOf(
                "kind" to "shared",
                "submission_id" to outcome.submissionId,
                "eligibility" to outcome.eligibility,
                "duplicate" to outcome.duplicate
            )
            is GlobalClient.SubmitOutcome.Rejected -> linkedMapOf(
                "kind" to "rejected",
                "code" to outcome.code,
                "message" to outcome.message,
                "details" to outcome.details
            )
            is GlobalClient.SubmitOutcome.AuthFailed -> linkedMapOf(
                "kind" to "auth",
                "code" to outcome.code,
                "message" to outcome.message
            )
            is GlobalClient.SubmitOutcome.Quota -> linkedMapOf(
                "kind" to "quota",
                "retry_after" to outcome.retryAfterSec
            )
            is GlobalClient.SubmitOutcome.Uncertain -> linkedMapOf(
                "kind" to "uncertain",
                "reason" to outcome.reason
            )
            is GlobalClient.SubmitOutcome.Blocked -> return null
        }
        return SessionJson.stringify(map)
    }

    fun decode(arg: String?): GlobalClient.SubmitOutcome? {
        if (arg.isNullOrEmpty()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val root = SessionJson.parse(arg) as? Map<String, Any?>
                ?: return null
            when (root["kind"]) {
                "shared" -> GlobalClient.SubmitOutcome.Shared(
                    root["submission_id"] as? String ?: return null,
                    root["eligibility"] as? String ?: return null,
                    root["duplicate"] as? Boolean ?: return null
                )
                "rejected" -> GlobalClient.SubmitOutcome.Rejected(
                    root["code"] as? String ?: return null,
                    root["message"] as? String ?: return null,
                    (root["details"] as? List<*>)
                        ?.mapNotNull { it as? String } ?: return null
                )
                "auth" -> GlobalClient.SubmitOutcome.AuthFailed(
                    root["code"] as? String ?: return null,
                    root["message"] as? String ?: return null
                )
                "quota" -> GlobalClient.SubmitOutcome.Quota(
                    (root["retry_after"] as? Number)?.toLong()
                )
                "uncertain" -> GlobalClient.SubmitOutcome.Uncertain(
                    root["reason"] as? String ?: return null
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
