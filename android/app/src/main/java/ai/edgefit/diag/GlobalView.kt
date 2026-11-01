package ai.edgefit.diag

import android.app.Activity

/**
 * "Contribute to EdgeFit Global" presentation (thin Android layer).
 * All contribution logic lives in [GlobalClient]; this file only
 * renders review/consent/history dialogs from validated models.
 * No network calls originate here; every action callback runs the
 * caller's explicit handler.
 */
object GlobalView {

    /** Local record line for history lists (pure, host-testable). */
    fun historyLine(record: EvidenceStore.Record): String {
        val s = record.summary
        val server = record.serverEligibility?.let {
            GlobalContract.displayServerState(it)
        } ?: when (record.contributionState) {
            GlobalContract.LOCAL_NOT_SHARED -> "Not shared"
            GlobalContract.LOCAL_SENDING -> "Sending"
            GlobalContract.LOCAL_UNCERTAIN -> "Submission uncertain"
            GlobalContract.LOCAL_RETRY -> "Retry available"
            GlobalContract.LOCAL_NO_CONNECTION -> "Connection unavailable"
            GlobalContract.LOCAL_SHARED -> "Shared"
            else -> record.contributionState
        }
        val origin = if (record.provenance ==
            EvidenceStore.PROVENANCE_IMPORTED
        ) "imported" else "this install"
        return "${s.runId} · ${s.status} · " +
            "%.1f tok/s · %s · %s".format(s.tpsGen, origin, server)
    }

    /** "Ready to contribute" gate text for one record (pure). */
    fun readinessLine(record: EvidenceStore.Record): String {
        if (record.contributionState == GlobalContract.LOCAL_SHARED &&
            record.submissionId != null
        ) {
            return "Shared: " + GlobalContract.displayServerState(
                record.serverEligibility)
        }
        return when (record.contributionState) {
            GlobalContract.LOCAL_UNCERTAIN ->
                "Submission uncertain — review before retrying"
            GlobalContract.LOCAL_SENDING -> "Sending…"
            GlobalContract.LOCAL_RETRY -> "Ready to contribute (retry available)"
            else -> "Ready to contribute"
        }
    }

    /**
     * EF-ANDROID-CONSENT-ACTIVATION-01: pure gate for the
     * "Approve and upload" action (host-testable). The activity
     * passes the live connection facts; all four must hold.
     */
    fun canOfferUpload(
        origin: String?,
        tokenSuffix: String?,
        approved: Boolean,
        eligible: Boolean
    ): Boolean =
        origin != null && tokenSuffix != null && approved && eligible

    fun showReview(
        activity: Activity,
        review: GlobalClient.Review,
        connected: Boolean,
        onApprove: () -> Unit
    ) {
        val origin = review.origin
        val sections = listOf(
            InsightCards.Section(
                "Result to share", listOf(
                    InsightCards.Row("Run", review.runId),
                    InsightCards.Row(
                        "Source",
                        if (review.provenance ==
                            EvidenceStore.PROVENANCE_IMPORTED
                        ) "Imported file (not proof of this install)"
                        else "Measured on this install"),
                    InsightCards.Row("Device", review.deviceModel),
                    InsightCards.Row(
                        "Model",
                        "${review.modelDisplay} " +
                            "(${review.modelSizeBytes / 1048576} MB)"),
                    InsightCards.Row(
                        "Config",
                        "${review.configText}; status ${review.status}"),
                    InsightCards.Row(
                        "Throughput",
                        "%.2f tok/s gen · %.2f tok/s prefill".format(
                            review.tpsGen, review.tpsPrefill)),
                    InsightCards.Row(
                        "Conditions",
                        "thermal ${review.thermalText}" +
                            (review.memAvailPctStart?.let {
                                " · mem %.1f%% at start".format(it)
                            } ?: "")),
                    InsightCards.Row("Collected", review.collectedAtUtc)
                ),
                details = "Model SHA-256: ${review.modelSha256}\n" +
                    "Artifact SHA-256: ${review.artifactSha256}"
            ),
            InsightCards.Section(
                "Data sharing", listOf(
                    InsightCards.Row(
                        "Sends",
                        "Full benchmark JSON + consent record " +
                            "(no API keys, no prompts beyond workload id)"),
                    InsightCards.Row(
                        "Scope", review.scopes.joinToString(", ") +
                            " (aggregate publication only; " +
                            "no public per-run listing)"),
                    InsightCards.Row(
                        "Policy", review.consentPolicyVersion),
                    InsightCards.Row(
                        "Destination", origin
                            ?: "Cloud connection not configured"),
                    InsightCards.Row(
                        "Credential",
                        review.tokenSuffix?.let { "upload token $it" }
                            ?: "no upload token provisioned"),
                    InsightCards.Row(
                        "Withdrawal",
                        "Revoke anytime; server tombstones the row " +
                            "and drops it from aggregates")
                )
            )
        )
        val actions = mutableListOf<CardDialog.Action>()
        if (connected) {
            actions.add(CardDialog.Action(
                "Approve and upload", primary = true, onTap = onApprove))
        }
        CardDialog.show(
            activity, "Contribute to EdgeFit Global", sections, actions)
    }

    fun showHistory(
        activity: Activity,
        lines: List<Pair<String, String>>,
        onOpen: (String) -> Unit
    ) {
        val rows = if (lines.isEmpty()) {
            listOf(InsightCards.Row(
                "No evidence",
                "Run a benchmark or import a result JSON first."))
        } else {
            lines.map { (runId, line) ->
                InsightCards.Row(runId, line)
            }
        }
        val actions = lines.take(10).map { (runId, _) ->
            CardDialog.Action("Open ${runId.takeLast(8)}") { onOpen(runId) }
        }
        CardDialog.show(
            activity, "Contribution history",
            listOf(InsightCards.Section("Local evidence", rows)), actions)
    }

    fun showReceipt(
        activity: Activity,
        submissionId: String,
        eligibility: String,
        duplicate: Boolean
    ) {
        CardDialog.show(
            activity, "Submission receipt",
            listOf(InsightCards.Section(
                if (duplicate) "Accepted (duplicate)" else "Accepted",
                listOf(
                    InsightCards.Row("Submission", submissionId),
                    InsightCards.Row(
                        "Server state",
                        GlobalContract.displayServerState(eligibility)),
                    InsightCards.Row(
                        "Meaning",
                        if (duplicate) {
                            "These exact bytes were already stored; " +
                                "no second row was created."
                        } else {
                            "Received by the backend. This is NOT " +
                                "publication; qualification is separate."
                        })
                ))))
    }

    fun showOutcome(
        activity: Activity,
        title: String,
        message: String,
        details: List<String> = emptyList()
    ) {
        val rows = mutableListOf(InsightCards.Row("Result", message))
        details.take(8).forEachIndexed { i, detail ->
            rows.add(InsightCards.Row("Detail ${i + 1}", detail))
        }
        CardDialog.show(
            activity, title,
            listOf(InsightCards.Section("EdgeFit Global", rows)))
    }
}
