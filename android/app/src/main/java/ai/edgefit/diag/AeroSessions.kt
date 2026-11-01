package ai.edgefit.diag

/**
 * EF-UX-AERO-01 Step 4 sessions-library derivations. Pure
 * builders over durable [SessionRecord] history plus
 * best-effort evidence lookups the activity resolves.
 * The activity renders; this object never touches views,
 * storage, or the network.
 *
 * Session-vs-evidence identity is preserved throughout:
 * a session id (SES-…) names the lifecycle record, a run
 * id (BENCH-…) names one measurement, and the evidence
 * line names the preserved artifact (captured on this
 * install vs imported) plus its contribution state. None
 * of the three is ever substituted for another.
 */
object AeroSessions {

    /** Inline recent cards on the Sessions tab. The full
     * browser (dialog) windows at SessionView.PAGE_SIZE;
     * inline stays far below it so refresh never janks. */
    const val INLINE_MAX = 8

    /** Contribution evidence resolved by the activity for
     * one run id, or null when no artifact is preserved. */
    data class EvidenceInfo(
        val provenance: String,
        val contributionState: String,
        val serverEligibility: String?,
        val submissionId: String?
    )

    fun evidenceInfo(record: EvidenceStore.Record): EvidenceInfo =
        EvidenceInfo(
            provenance = record.provenance,
            contributionState = record.contributionState,
            serverEligibility = record.serverEligibility,
            submissionId = record.submissionId
        )

    data class Card(
        val sessionId: String,
        val title: String,
        val isLatest: Boolean,
        val isBound: Boolean,
        val createdLocal: String,
        val modelLine: String,
        val stateLine: String,
        val measuredLine: String,
        /** True only for a COMPLETED finite rate. */
        val measuredPresent: Boolean,
        val deltaLine: String?,
        val evidenceLine: String
    )

    /** "62 local sessions." / "1 local session." /
     * "No local sessions yet." — any count renders. */
    fun headerLine(total: Int): String = when {
        total <= 0 -> "No local sessions yet."
        total == 1 -> "1 local session."
        else -> "$total local sessions."
    }

    /** Run id attached to a record, or null when no run
     * has attached yet. */
    @Suppress("UNCHECKED_CAST")
    fun runIdOf(record: SessionRecord): String? {
        val bench = record.benchmark as? Map<String, Any?>
            ?: return null
        return bench["run_id"] as? String
    }

    /**
     * Honest measurement line. A COMPLETED finite rate
     * reads as measured; a failed run is never a zero
     * datapoint; a run-less record says so.
     */
    @Suppress("UNCHECKED_CAST")
    fun measuredLine(record: SessionRecord): String {
        SessionListUi.primaryMeasurement(record)?.let {
            return "$it (MEASURED)"
        }
        val bench = record.benchmark as? Map<String, Any?>
        if (bench == null) return "No run attached yet"
        return "No measurement — run ${bench["status"] ?: "?"}"
    }

    /**
     * Prediction-vs-measured one-liner, or null when no
     * prediction exists. Predictions always read
     * ESTIMATE; the delta is host-computed; a refused
     * comparison names its reason. Never "verified".
     */
    @Suppress("UNCHECKED_CAST")
    fun deltaLine(record: SessionRecord?): String? {
        if (record == null) return null
        val prediction = record.prediction as? Map<String, Any?>
            ?: return null
        val predicted =
            InsightCards.tps(prediction["predicted_gen_tps"])
        val comparison = record.comparison as? Map<String, Any?>
        if (comparison == null) {
            return if (record.benchmark == null) {
                "Predicted $predicted gen (ESTIMATE) — " +
                    "awaiting measurement"
            } else {
                "Predicted $predicted gen (ESTIMATE) — " +
                    "not compared yet"
            }
        }
        if (comparison["comparable"] != true) {
            return "Not comparable " +
                "(${comparison["reason"] ?: "no reason recorded"})"
        }
        val bench = record.benchmark as? Map<String, Any?>
        val measures = bench?.get("measurements") as? Map<String, Any?>
        val measured = InsightCards.tps(measures?.get("tps_gen"))
        return "Predicted $predicted → measured $measured " +
            "(Δ ${InsightCards.signed(comparison["gen_signed"])} " +
            "tok/s, ${InsightCards.signedPct(comparison["gen_pct"])})"
    }

    /**
     * Evidence line for one record. No run, no artifact,
     * captured, or imported — each reads distinctly —
     * plus the local contribution state (shared records
     * name the server eligibility).
     */
    fun evidenceLine(
        record: SessionRecord,
        evidence: EvidenceInfo?
    ): String {
        val runId = runIdOf(record)
            ?: return "Evidence: no run attached"
        if (evidence == null) {
            return "Evidence: not preserved on this install"
        }
        val origin = if (evidence.provenance ==
            EvidenceStore.PROVENANCE_IMPORTED
        ) {
            "imported"
        } else {
            "captured"
        }
        return "Evidence: $origin · ${contributionLabel(evidence)}"
    }

    /**
     * Local contribution state in words. Mirrors
     * GlobalView history/readiness semantics (kept in
     * sync by review): server states only ever qualify
     * an actual SHARED record with a submission id.
     */
    fun contributionLabel(evidence: EvidenceInfo): String {
        if (evidence.contributionState ==
            GlobalContract.LOCAL_SHARED &&
            evidence.submissionId != null
        ) {
            return "Shared (${GlobalContract.displayServerState(
                evidence.serverEligibility)})"
        }
        return when (evidence.contributionState) {
            GlobalContract.LOCAL_NOT_SHARED -> "Not shared"
            GlobalContract.LOCAL_AWAITING_CONSENT -> "Awaiting consent"
            GlobalContract.LOCAL_SENDING -> "Sending…"
            GlobalContract.LOCAL_UNCERTAIN -> "Submission uncertain"
            GlobalContract.LOCAL_RETRY -> "Retry available"
            GlobalContract.LOCAL_NO_CONNECTION ->
                "Connection unavailable"
            GlobalContract.LOCAL_SHARED -> "Shared"
            else -> evidence.contributionState
        }
    }

    /** One inline card. [evidence] is the resolved
     * artifact for this record's run, or null. */
    fun card(
        record: SessionRecord,
        isLatest: Boolean,
        isBound: Boolean,
        evidence: EvidenceInfo?
    ): Card {
        var title = SessionListUi.rowLabel(record)
        if (isLatest) title = "Latest · $title"
        if (isBound) title += " · BOUND"
        return Card(
            sessionId = record.sessionId,
            title = title,
            isLatest = isLatest,
            isBound = isBound,
            createdLocal = SessionTimeline.formatLocal(
                record.createdAtUtc),
            modelLine = SessionListUi.shortModelName(
                record.modelDisplay) + " · " +
                SessionListUi.configShort(record.config),
            stateLine = SessionListUi.friendlyState(record),
            measuredLine = measuredLine(record),
            measuredPresent =
                SessionListUi.primaryMeasurement(record) != null,
            deltaLine = deltaLine(record),
            evidenceLine = evidenceLine(record, evidence)
        )
    }

    /**
     * Inline window over a newest-first list: at most
     * [limit] cards, head first. [evidenceFor] resolves
     * each visible record's artifact (the activity
     * try/catches per record, so one corrupt artifact
     * never blanks the list).
     */
    fun visibleCards(
        sortedNewestFirst: List<SessionRecord>,
        latestId: String?,
        boundId: String?,
        evidenceFor: (SessionRecord) -> EvidenceInfo?,
        limit: Int = INLINE_MAX
    ): List<Card> =
        sortedNewestFirst.take(limit.coerceAtLeast(0)).map {
            card(it, it.sessionId == latestId,
                it.sessionId == boundId, evidenceFor(it))
        }

    /** "Showing 8 of 62 …" overflow line, or null when
     * everything is visible. */
    fun showMoreLine(total: Int, shown: Int): String? =
        if (total > shown) {
            "Showing $shown of $total — open the browser for all."
        } else {
            null
        }

    /**
     * Screen-reader label for one card's Details
     * button. Every card's visible label reads
     * "Details", so the description names the
     * session — otherwise TalkBack announces an
     * indistinguishable row of Details buttons.
     */
    fun detailsDescription(card: Card): String =
        "Details for ${card.title}"

    /** Honest empty state: no fake sample sessions. */
    fun emptyLines(): List<String> = listOf(
        "No local sessions yet.",
        "Create an offline test in the browser, or stage a " +
            "model and run a benchmark."
    )
}
