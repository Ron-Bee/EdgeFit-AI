package ai.edgefit.diag

/**
 * Bounded on-device evidence selection for agent proposals. Reads
 * only this app's session store — never the workstation SQLite
 * database, never an undeployed stats backend. Comparable runs share
 * model, workload, device, runtime, and COMPLETED status; config is
 * the intentionally varied axis. Everything else is contextual and
 * never numerically combined. Deterministic ordering (seq asc),
 * hard caps, canonical SHA-256. Pure JVM.
 */
object AgentEvidence {
    const val MAX_COMPARABLE = 5
    const val MAX_CONTEXTUAL = 3

    data class RunEvidence(
        val seq: Int,
        val childId: String,
        val runId: String,
        val nCtx: Int,
        val nThreads: Int,
        val nPredict: Int,
        val genTps: Double,
        val prefillTps: Double,
        val predictedGen: Double?,
        val genPct: Double?,
        val thermal: String,
        val comparable: Boolean,
        /** Set when contextual: why it cannot be pooled. */
        val contextReason: String?
    )

    data class EvidencePack(
        val comparable: List<RunEvidence>,
        val contextual: List<RunEvidence>,
        /** Canonical hash over the pack content (not the parent id). */
        val packHash: String
    )

    @Suppress("UNCHECKED_CAST")
    fun buildPack(parent: SessionRecord, children: List<SessionRecord>): EvidencePack {
        val ordered = children.sortedBy { it.experimentSeq ?: Int.MAX_VALUE }
        // Runtime baseline: the oldest run that recorded provenance.
        // Runs without keys (pre-agent) stay comparable on unknowns.
        val baseline = ordered.mapNotNull(::runtimeKeys).firstOrNull()
        val comparable = mutableListOf<RunEvidence>()
        val contextual = mutableListOf<RunEvidence>()
        for (child in ordered) {
            val entry = toEvidence(parent, child, baseline) ?: continue
            if (entry.comparable) comparable.add(entry) else contextual.add(entry)
        }
        // Newest experiments, then deterministic seq-ascending order.
        val comp = comparable.takeLast(MAX_COMPARABLE).sortedBy { it.seq }
        val ctx = contextual.takeLast(MAX_CONTEXTUAL).sortedBy { it.seq }
        return EvidencePack(comp, ctx, hashPack(comp, ctx))
    }

    @Suppress("UNCHECKED_CAST")
    private fun toEvidence(
        parent: SessionRecord,
        child: SessionRecord,
        baseline: Triple<String?, String?, String?>?
    ): RunEvidence? {
        val seq = child.experimentSeq ?: return null
        val bench = child.benchmark as? Map<String, Any?> ?: return null
        val runId = bench["run_id"] as? String ?: return null
        val measurements = bench["measurements"] as? Map<String, Any?>
            ?: return null
        val gen = (measurements["tps_gen"] as? Number)?.toDouble()
            ?: return null
        val prefill = (measurements["tps_prefill"] as? Number)?.toDouble()
            ?: return null
        if (!gen.isFinite() || !prefill.isFinite()) return null
        val prediction = child.prediction as? Map<String, Any?>
        val comparison = child.comparison as? Map<String, Any?>
        val reasons = mutableListOf<String>()
        if (child.modelSha256.lowercase() != parent.modelSha256.lowercase()) {
            reasons.add("model differs")
        }
        if (child.workloadId != parent.workloadId) {
            reasons.add("workload differs")
        }
        if (!sameDevice(child.deviceModel, parent.deviceModel)) {
            reasons.add("device differs")
        }
        if ((bench["status"] as? String) != BenchContract.STATUS_COMPLETED) {
            reasons.add("not COMPLETED")
        }
        val keys = runtimeKeys(child)
        if (baseline != null && keys != null && keys != baseline) {
            reasons.add("runtime differs")
        }
        val nCtx = (child.config["n_ctx"] as? Number)?.toInt() ?: 0
        val nThreads = (child.config["n_threads"] as? Number)?.toInt() ?: 0
        val nPredict = (child.config["n_predict"] as? Number)?.toInt() ?: 0
        return RunEvidence(
            seq = seq,
            childId = child.sessionId,
            runId = runId,
            nCtx = nCtx,
            nThreads = nThreads,
            nPredict = nPredict,
            genTps = gen,
            prefillTps = prefill,
            predictedGen = (prediction?.get("predicted_gen_tps")
                as? Number)?.toDouble(),
            genPct = (comparison?.get("gen_pct") as? Number)?.toDouble(),
            thermal = listOf(
                measurements["thermal_before"] as? String ?: "?",
                measurements["thermal_mid"] as? String ?: "?",
                measurements["thermal_after"] as? String ?: "?"
            ).joinToString("/"),
            comparable = reasons.isEmpty(),
            contextReason = reasons.joinToString("; ").ifEmpty { null }
        )
    }

    private fun sameDevice(child: String?, parent: String?): Boolean {
        // Both unknown (pre-agent records): do not exclude on unknowns.
        if (child == null && parent == null) return true
        return child != null && child == parent
    }

    /** Runtime provenance triple, or null when unrecorded. */
    @Suppress("UNCHECKED_CAST")
    private fun runtimeKeys(
        child: SessionRecord
    ): Triple<String?, String?, String?>? {
        val bench = child.benchmark as? Map<String, Any?> ?: return null
        val measurements = bench["measurements"] as? Map<String, Any?>
            ?: return null
        val version = measurements["runtime_version"] as? String
        val abi = measurements["runtime_abi"] as? String
        val variant = measurements["native_variant"] as? String
        if (version == null && abi == null && variant == null) return null
        return Triple(version, abi, variant)
    }

    private fun hashPack(
        comparable: List<RunEvidence>,
        contextual: List<RunEvidence>
    ): String {
        fun entry(e: RunEvidence): Map<String, Any?> = linkedMapOf(
            "seq" to e.seq,
            "child_id" to e.childId,
            "run_id" to e.runId,
            "n_ctx" to e.nCtx,
            "n_threads" to e.nThreads,
            "n_predict" to e.nPredict,
            "gen_tps" to e.genTps,
            "prefill_tps" to e.prefillTps,
            "predicted_gen" to e.predictedGen,
            "gen_pct" to e.genPct,
            "thermal" to e.thermal,
            "comparable" to e.comparable,
            "context_reason" to e.contextReason
        )
        val canonical = SessionJson.stringify(linkedMapOf(
            "agent_evidence_v" to AgentApi.AGENT_VERSION,
            "comparable" to comparable.map(::entry),
            "contextual" to contextual.map(::entry)
        ))
        return BenchConfig.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /** Bounded prompt rendering of a pack. */
    /**
     * EF-MEM-03 failure evidence: a typed, bounded
     * description of one uncompleted child attempt. Names
     * config, stage, cause, and memory conditions — never
     * a throughput, which does not exist for this run.
     * Null when the child has no usable failure record.
     */
    @Suppress("UNCHECKED_CAST")
    fun describeFailure(child: SessionRecord): String? {
        val seq = child.experimentSeq ?: return null
        val bench = child.benchmark as? Map<String, Any?>
            ?: return null
        val status = bench["status"] as? String ?: return null
        if (status != BenchContract.STATUS_FAILED &&
            status != BenchContract.STATUS_TIMEOUT &&
            status != BenchContract.STATUS_CANCELLED
        ) {
            return null
        }
        val runId = bench["run_id"] as? String ?: return null
        if (!SessionStore.isValidRunId(runId)) return null
        val exec = bench["executed_config"] as? Map<String, Any?>
        val cfg = SessionBenchBridge.configData(exec ?: child.config)
        val cause = (bench["failure_cause"] as? String)
            ?.take(280) ?: "no recorded cause"
        val measurements = bench["measurements"] as? Map<String, Any?>
        val memPct = measurements?.get("mem_avail_pct_start")
            as? Number
        val memLow = measurements?.get("mem_low_memory")
        val memText = if (memPct != null) {
            "mem_avail_pct=${"%.1f".format(memPct.toDouble())} " +
                "mem_low=${memLow ?: "?"}"
        } else {
            "mem_avail_pct=unknown"
        }
        return "$runId seq=$seq status=$status " +
            "ctx=${cfg?.nCtx ?: "?"} " +
            "threads=${cfg?.nThreads ?: "?"} " +
            "predict=${cfg?.nPredict ?: "?"} $memText " +
            "cause=[$cause]"
    }

    /**
     * Bounded failure section for proposal prompts (newest
     * three). Empty string when no failure evidence exists.
     */
    fun renderFailures(children: List<SessionRecord>): String {
        val lines = children.sortedBy {
            it.experimentSeq ?: Int.MAX_VALUE
        }.takeLast(3).mapNotNull(::describeFailure)
        if (lines.isEmpty()) return ""
        return "FAILED_ATTEMPTS (no throughput exists for " +
            "these runs; never invent one):\n" +
            lines.joinToString("\n")
    }

    fun renderPack(pack: EvidencePack): String {
        if (pack.comparable.isEmpty() && pack.contextual.isEmpty()) {
            return "no on-device measurements yet"
        }
        val lines = mutableListOf<String>()
        if (pack.comparable.isNotEmpty()) {
            lines.add("COMPARABLE_EVIDENCE:")
            for (e in pack.comparable) {
                lines.add(
                    "${e.runId} seq=${e.seq} " +
                        "ctx=${e.nCtx} threads=${e.nThreads} " +
                        "predict=${e.nPredict} gen_tps=${e.genTps} " +
                        "prefill_tps=${e.prefillTps} " +
                        "predicted_gen=${e.predictedGen ?: "?"} " +
                        "gen_pct=${e.genPct ?: "?"} thermal=${e.thermal}"
                )
            }
        }
        if (pack.contextual.isNotEmpty()) {
            lines.add("CONTEXTUAL_EVIDENCE (do not pool numerically):")
            for (e in pack.contextual) {
                lines.add(
                    "${e.runId} seq=${e.seq} gen_tps=${e.genTps} " +
                        "reason=${e.contextReason}"
                )
            }
        }
        return lines.joinToString("\n")
    }
}
