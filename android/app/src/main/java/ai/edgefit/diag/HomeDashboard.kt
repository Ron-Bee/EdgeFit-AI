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
}
