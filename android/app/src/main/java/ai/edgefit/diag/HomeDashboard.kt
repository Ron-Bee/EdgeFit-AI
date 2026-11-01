package ai.edgefit.diag

/**
 * UX-01BC Home dashboard derivations (FR-UX-01): pure
 * builders over the collected profile, staged model,
 * and session records. The activity renders; this
 * object never touches views or storage.
 */
object HomeDashboard {

    data class DeviceCard(val lines: List<String>)
    data class ModelCard(val staged: Boolean, val lines: List<String>)
    data class LastRun(
        val sessionId: String,
        val runId: String,
        val genTps: Double?,
        val prefillTps: Double?,
        val whenUtc: String
    )

    /**
     * Static identity lines only. Dynamic health (battery,
     * available RAM, thermal) comes from [DeviceTelemetry]
     * samples so the Home card never shows stale readings
     * as current.
     */
    fun deviceCard(profile: CollectedProfile): DeviceCard {
        val mb = 1024L * 1024L
        return DeviceCard(listOf(
            "${profile.device.manufacturer} ${profile.device.model}",
            "Android ${profile.device.androidRelease} " +
                "(API ${profile.device.sdkInt})",
            "${profile.device.logicalProcessors} cores · " +
                "${profile.device.totalRamBytes / mb} MB total"
        ))
    }

    fun modelCard(
        display: String?,
        sha256: String?,
        sizeBytes: Long
    ): ModelCard {
        if (display == null) {
            return ModelCard(
                false,
                listOf("No model staged", "Select a GGUF file to begin.")
            )
        }
        val mb = sizeBytes / (1024L * 1024L)
        return ModelCard(
            true,
            listOf(
                display,
                "SHA ${sha256?.take(12) ?: "?"} · $mb MB"
            )
        )
    }

    /**
     * Latest session with a COMPLETED attached benchmark.
     * Ordered by record update time; returns null when no
     * measured result exists yet (never fabricated).
     */
    @Suppress("UNCHECKED_CAST")
    fun lastMeasured(sessions: List<SessionRecord>): LastRun? {
        return sessions
            .filter {
                val bench = it.benchmark as? Map<String, Any?>
                    ?: return@filter false
                bench["status"] == BenchContract.STATUS_COMPLETED
            }
            .maxByOrNull { it.updatedAtUtc }
            ?.let { record ->
                val bench = record.benchmark as Map<String, Any?>
                val measures =
                    bench["measurements"] as? Map<String, Any?>
                LastRun(
                    sessionId = record.sessionId,
                    runId = bench["run_id"].toString(),
                    genTps = (measures?.get("tps_gen") as? Number)
                        ?.toDouble(),
                    prefillTps = (measures?.get("tps_prefill") as? Number)
                        ?.toDouble(),
                    whenUtc = bench["collected_at_utc"]?.toString()
                        ?: record.updatedAtUtc
                )
            }
    }

    /** Destination tab for the derived next action. */
    fun actionTab(action: NextAction): NavTab = when (action) {
        NextAction.SELECT_MODEL -> NavTab.BENCHMARK
        NextAction.RUN_BENCHMARK -> NavTab.BENCHMARK
        NextAction.REVIEW_PROPOSAL -> NavTab.AGENT
        NextAction.REQUEST_FOLLOW_UP -> NavTab.SESSIONS
        NextAction.VIEW_RESULTS -> NavTab.SESSIONS
    }

    // ---- EF-UX-AERO-01 Step 3: dashboard derivations ----

    /** Hero triple: static brand copy, rendered once. */
    data class Hero(
        val overline: String,
        val title: String,
        val subtitle: String
    )

    fun hero(): Hero = Hero(
        overline = "ON-DEVICE AI",
        title = "EdgeFit AI",
        subtitle = "Real performance"
    )

    /**
     * Device identity from the real profile only, never
     * a marketing string (android-profile-contract.md).
     * On the study phone this is the S22 Ultra report.
     */
    fun deviceTitle(profile: CollectedProfile): String =
        "${profile.device.manufacturer} ${profile.device.model}"

    /** Fixed tile labels in display order. */
    val metricLabels: List<String> = listOf(
        "CPU",
        "Total RAM",
        "Available RAM (live)",
        "Battery",
        "Battery temperature",
        "Thermal status",
        "Android"
    )

    /**
     * One labeled metric tile. Labels name the exact
     * signal: total vs available RAM stay distinct, and
     * battery temperature never stands in for the
     * Android thermal status (or vice versa).
     */
    data class MetricTile(val label: String, val value: String)

    /**
     * Screen-reader label for one metric tile value.
     * The label and value are separate views, so the
     * value announces the pair ("Battery: 85%") and
     * never a bare number out of context.
     */
    fun tileDescription(label: String, value: String): String =
        "$label: $value"

    /**
     * Fixed-order tiles over the profile plus one live
     * snapshot. A null snapshot leaves the live tiles
     * unavailable, never stale or fabricated.
     */
    fun metricTiles(
        profile: CollectedProfile,
        snapshot: DeviceTelemetry.Snapshot?
    ): List<MetricTile> {
        val device = profile.device
        val batteryValue = if (snapshot == null) {
            "unavailable"
        } else {
            DeviceTelemetry.batteryPctLabel(snapshot.batteryPct) +
                " · " + DeviceTelemetry.chargingLabel(
                    snapshot.batteryStatus, snapshot.batteryPlugged)
        }
        val values = listOf(
            "${device.logicalProcessors} cores",
            DeviceTelemetry.formatRam(device.totalRamBytes),
            if (snapshot == null) "unavailable"
            else DeviceTelemetry.formatRam(snapshot.availRamBytes),
            batteryValue,
            if (snapshot == null) "unavailable"
            else DeviceTelemetry.batteryTempLabel(
                snapshot.batteryTempC),
            DeviceTelemetry.thermalLabel(snapshot?.thermalName),
            "Android ${device.androidRelease} " +
                "(API ${device.sdkInt})"
        )
        return metricLabels.zip(values) { label, value ->
            MetricTile(label, value)
        }
    }

    /** Model readiness: none, staged, or measured here. */
    enum class ModelReadiness { NONE, STAGED, MEASURED }

    data class ModelReadinessCard(
        val state: ModelReadiness,
        val lines: List<String>
    )

    /**
     * Readiness over the staged artifact and durable
     * history. MEASURED needs a COMPLETED local run of
     * the exact staged hash — name or size alone never
     * qualify (same rule as ModelRecommend).
     */
    fun modelReadiness(
        display: String?,
        sha256: String?,
        sizeBytes: Long,
        sessions: List<SessionRecord>
    ): ModelReadinessCard {
        if (display == null) {
            return ModelReadinessCard(
                ModelReadiness.NONE,
                listOf(
                    "No model staged",
                    "Select a GGUF file to begin."
                )
            )
        }
        val mb = sizeBytes / (1024L * 1024L)
        val head = listOf(
            display,
            "SHA ${sha256?.take(12) ?: "?"} · $mb MB"
        )
        if (stagedMeasured(sha256, sessions)) {
            return ModelReadinessCard(
                ModelReadiness.MEASURED,
                head + "Measured on this device " +
                    "(exact artifact match)."
            )
        }
        return ModelReadinessCard(
            ModelReadiness.STAGED,
            head + "Staged — no completed run of this " +
                "exact artifact yet."
        )
    }

    /** Exact-hash COMPLETED evidence for one artifact. */
    fun stagedMeasured(
        sha256: String?,
        sessions: List<SessionRecord>
    ): Boolean {
        val want = sha256?.lowercase() ?: return false
        @Suppress("UNCHECKED_CAST")
        return sessions.any { record ->
            if (record.modelSha256.lowercase() != want) {
                return@any false
            }
            val bench = record.benchmark as? Map<String, Any?>
                ?: return@any false
            bench["status"] == BenchContract.STATUS_COMPLETED
        }
    }

    /** Bold readiness title for the Model card. */
    fun readinessLabel(state: ModelReadiness): String =
        when (state) {
            ModelReadiness.NONE -> "No model"
            ModelReadiness.STAGED -> "Staged — not yet measured"
            ModelReadiness.MEASURED -> "Measured on this device"
        }

    /**
     * The one contextual primary-action label for Home.
     * Exactly one primary per state; the destination
     * stays in [actionTab].
     */
    fun primaryAction(action: NextAction): String =
        when (action) {
            NextAction.SELECT_MODEL -> "Select model"
            NextAction.RUN_BENCHMARK -> "Run benchmark"
            NextAction.REVIEW_PROPOSAL -> "Review proposal"
            NextAction.REQUEST_FOLLOW_UP -> "Request follow-up"
            NextAction.VIEW_RESULTS -> "View results"
        }

    /**
     * Latest-measurement lines from a real [LastRun],
     * or the designed empty state. The empty state
     * carries no throughput figure — none was measured.
     */
    fun lastRunLines(run: LastRun?): List<String> {
        if (run == null) {
            return listOf(
                "No completed measurement yet.",
                "Run a benchmark to record one."
            )
        }
        return listOf(
            run.sessionId,
            "Run ${run.runId}",
            "Generation ${InsightCards.tps(run.genTps)}",
            "Prefill ${InsightCards.tps(run.prefillTps)}",
            run.whenUtc
        )
    }

    /**
     * Quick-access links. A null tab opens a dialog
     * (model catalog), never a destination.
     */
    data class QuickLink(
        val label: String,
        val detail: String,
        val tab: NavTab?
    )

    fun quickAccess(): List<QuickLink> = listOf(
        QuickLink(
            "Measure", "Run a benchmark",
            NavTab.BENCHMARK
        ),
        QuickLink(
            "Sessions", "Local measurement history",
            NavTab.SESSIONS
        ),
        QuickLink("Models", "Recommended models", null),
        QuickLink(
            "Agent", "Guided optimization",
            NavTab.AGENT
        )
    )
}
