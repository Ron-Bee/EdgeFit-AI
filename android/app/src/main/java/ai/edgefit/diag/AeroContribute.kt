package ai.edgefit.diag

/**
 * EF-UX-AERO-01 Step 5 contribution derivations. Pure
 * builders for the Contribute presentation: readiness,
 * device/model/result preview, provenance, data
 * summary, and connection status. Presentation only —
 * consent, upload, refresh, and revocation behavior
 * stays in GlobalClient/GlobalView/MainActivity, and
 * nothing here auto-uploads. The terms "Contribute to
 * EdgeFit Global" and "Ready to contribute" are
 * preserved EXACTLY (mirrors of GlobalView and the
 * Benchmark action); tests pin them byte-for-byte.
 * Connection status is honest: an unconfigured origin
 * reads as not configured, never as working, and no
 * simulated receipts exist anywhere in this object.
 */
object AeroContribute {

    /**
     * EXACT contribution entry title (mirrors
     * GlobalView.showReview and the Benchmark action).
     */
    const val TITLE = "Contribute to EdgeFit Global"

    /**
     * EXACT ready gate term (mirrors
     * GlobalView.readinessLine).
     */
    const val READY = "Ready to contribute"

    /** EXACT unconfigured-origin line (mirrors GlobalView). */
    const val NOT_CONFIGURED = "Cloud connection not configured"

    /**
     * "Ready to contribute" gate text for one record.
     * Byte-identical mirror of GlobalView.readinessLine
     * over pure inputs, so the inline preview and the
     * dialog can never disagree.
     */
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
            GlobalContract.LOCAL_RETRY ->
                "Ready to contribute (retry available)"
            else -> READY
        }
    }

    /**
     * Honest connection status. An unconfigured origin
     * is reported as such; a missing token names the
     * fix; a configured pair shows only the non-secret
     * suffix and restates that nothing auto-uploads.
     */
    fun connectionLine(
        origin: String?,
        tokenSuffix: String?
    ): String = when {
        origin == null -> NOT_CONFIGURED + "."
        tokenSuffix == null ->
            "No upload token: open EdgeFit Global Connection."
        else -> "Connection configured (upload token " +
            "$tokenSuffix). Nothing uploads automatically."
    }

    /**
     * Imported-vs-captured provenance. Mirrors the
     * GlobalView review wording: imports are never
     * presented as proof of this install.
     */
    fun provenanceLine(provenance: String): String =
        if (provenance == EvidenceStore.PROVENANCE_IMPORTED) {
            "Imported file (not proof of this install)"
        } else {
            "Measured on this install"
        }

    /**
     * Device/model/result preview for one record.
     * Throughput renders only for COMPLETED runs;
     * anything else reports no measurement, since
     * failed runs carry zeroed counters, not
     * observations.
     */
    fun previewLines(record: EvidenceStore.Record): List<String> {
        val s = record.summary
        val lines = mutableListOf(
            "run: ${s.runId}",
            "device: ${s.deviceModel}",
            "model: ${s.modelDisplay} " +
                "(${s.modelSizeBytes / 1048576} MB)",
            "config: ctx=${s.nCtx} t=${s.nThreads} " +
                "n=${s.nPredict} · status ${s.status}",
            "source: ${provenanceLine(record.provenance)}"
        )
        if (s.status == BenchContract.STATUS_COMPLETED) {
            lines.add(
                "result: %.2f tok/s gen · %.2f tok/s prefill".format(
                    s.tpsGen, s.tpsPrefill))
        } else {
            lines.add("result: no measurement " +
                "(run ${s.status})")
        }
        return lines
    }

    /**
     * Static data summary: what an upload sends, its
     * scope, and the standing consent terms. Mirrors
     * the GlobalView review facts without duplicating
     * per-record values.
     */
    fun dataLines(): List<String> = listOf(
        "Sends the full benchmark JSON plus a consent " +
            "record (no API keys, no prompts beyond the " +
            "workload id).",
        "Scope is aggregate publication only; there is " +
            "no public per-run listing.",
        "Consent policy: ${GlobalContract.CONSENT_POLICY_VERSION}.",
        "You can withdraw consent any time; the server " +
            "tombstones the row and drops it from aggregates.",
        "Uploads run only when you tap Approve and " +
            "upload — never automatically."
    )

    /**
     * Inline Benchmark-line for the latest evidence:
     * readiness plus a short id tail, matching the
     * existing evidence line exactly.
     */
    fun evidenceLine(record: EvidenceStore.Record): String =
        readinessLine(record) +
            " (${record.summary.runId.takeLast(8)})"

    /** Empty evidence state: no fake results, no receipts. */
    fun emptyLines(): List<String> = listOf(
        "No preserved benchmark evidence yet.",
        "Run a benchmark or import a result JSON first."
    )
}
