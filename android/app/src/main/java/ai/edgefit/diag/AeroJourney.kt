package ai.edgefit.diag

/**
 * EF-ANDROID-AERO-OVERHAUL-01 Priority Four: Global
 * Evidence journey derivation. Pure JVM over one
 * evidence record: four journey stages, each lit ONLY
 * from known record fields. Local measurement, server
 * acceptance, qualification, and publication are never
 * conflated — a receipt is not publication, and planned
 * stages without markers render as planned, never as
 * achieved. The honesty line reuses the receipt
 * dialog's exact wording.
 */
object AeroJourney {

    /** EXACT receipt-dialog honesty line (GlobalView). */
    const val HONESTY = "Received by the backend. This is NOT " +
        "publication; qualification is separate."

    data class Node(
        val label: String,
        val lit: Boolean,
        /** True when unlit for lack of any marker. */
        val planned: Boolean,
        val caption: String
    )

    data class Journey(
        val nodes: List<Node>,
        val consentLine: String,
        /** Full receipt line, or null when unsubmitted. */
        val receiptLine: String?,
        val serverLine: String
    )

    fun journey(record: EvidenceStore.Record?): Journey {
        if (record == null) {
            return Journey(
                nodes = listOf(
                    Node("Local measurement", false, false,
                        "No preserved evidence"),
                    Node("Submitted", false, false,
                        "No preserved evidence"),
                    Node("Qualifying", false, true,
                        "Planned stage"),
                    Node("Published", false, true,
                        "Planned stage")),
                consentLine = "Consent not recorded.",
                receiptLine = null,
                serverLine =
                    "No preserved benchmark evidence yet.")
        }
        val measured = record.summary.status ==
            BenchContract.STATUS_COMPLETED
        val shared = record.contributionState ==
            GlobalContract.LOCAL_SHARED &&
            record.submissionId != null
        val eligibility = record.serverEligibility
        val display =
            GlobalContract.displayServerState(eligibility)
        val localWord = when (record.contributionState) {
            GlobalContract.LOCAL_NOT_SHARED -> "Not shared"
            GlobalContract.LOCAL_AWAITING_CONSENT ->
                "Awaiting consent"
            GlobalContract.LOCAL_SENDING -> "Sending"
            GlobalContract.LOCAL_UNCERTAIN ->
                "Submission uncertain"
            GlobalContract.LOCAL_RETRY -> "Retry available"
            GlobalContract.LOCAL_NO_CONNECTION ->
                "Connection unavailable"
            GlobalContract.LOCAL_SHARED ->
                if (record.submissionId == null) "Shared"
                else display
            else -> record.contributionState
        }
        val measuredNode = Node("Local measurement",
            measured, false,
            if (measured) {
                "Run ${record.summary.runId.takeLast(8)}"
            } else {
                "No completed run"
            })
        val submittedNode = Node("Submitted", shared, false,
            if (shared) {
                "Receipt " +
                    "${record.submissionId!!.takeLast(8)} · " +
                    display
            } else {
                localWord
            })
        val qualified = eligibility ==
            GlobalContract.QUALIFIED ||
            eligibility == GlobalContract.PUBLISHED
        val qualifyingCaption = when (eligibility) {
            GlobalContract.QUALIFIED,
            GlobalContract.PUBLISHED -> "Qualified"
            GlobalContract.VALIDATED,
            GlobalContract.SUBMITTED ->
                "$display — not qualified yet"
            GlobalContract.REVOKED,
            GlobalContract.REJECTED -> display
            null -> if (shared) {
                "Submitted — qualification pending"
            } else {
                "Planned stage"
            }
            else -> display
        }
        val qualifyingNode = Node("Qualifying", qualified,
            eligibility == null && !shared,
            qualifyingCaption)
        val published =
            eligibility == GlobalContract.PUBLISHED
        val publishedCaption = when {
            published -> "Published aggregate"
            eligibility == GlobalContract.REVOKED ||
                eligibility == GlobalContract.REJECTED ->
                display
            eligibility == null -> "Planned stage"
            else -> "$display — not published"
        }
        val publishedNode = Node("Published", published,
            eligibility == null, publishedCaption)
        val consent = record.consentPolicyVersion?.let {
            "Consent $it" + (record.consentAcceptedAtUtc?.let
                { at -> " · $at" } ?: "")
        } ?: "Consent not recorded."
        return Journey(
            nodes = listOf(measuredNode, submittedNode,
                qualifyingNode, publishedNode),
            consentLine = consent,
            receiptLine = record.submissionId?.let {
                "Submission $it"
            },
            serverLine = eligibility?.let {
                "Server state: " +
                    GlobalContract.displayServerState(it)
            } ?: "No server state recorded.")
    }
}
