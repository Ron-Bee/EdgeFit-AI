package ai.edgefit.diag

/**
 * EF-COMP-01A Stage A: evaluation-arm evidence contracts
 * for M0 (Muse, constraints only) and M1 (Muse, constraints
 * + authorized compatible measurements). Pure JVM. Stage A
 * defines contracts, experimental separation, response
 * validation, and cost accounting ONLY — no live Muse
 * request is constructed or sent here. All arms share one
 * host-approved candidate set; the pack hash proves which
 * set and which evidence each arm saw.
 *
 * Separation rules (enforced by construction):
 * - M0 packs carry constraints + candidates and NOTHING
 *   measured. `measurements` is null, and `exclusions`
 *   records the withheld evidence.
 * - M1 packs admit only evidence the caller marks
 *   comparable AND identity-matched (model SHA, workload,
 *   runtime/build). Anything else is refused, never
 *   silently dropped: refusals land in `exclusions`.
 * - Target-decision outcomes NEVER enter either pack:
 *   callers supply prior evidence only. The pack hash
 *   commits to the exact bytes, so post-hoc leakage is
 *   detectable by rehashing.
 */
object EvalArms {

    const val ARM_R = "R"
    const val ARM_M0 = "M0"
    const val ARM_M1 = "M1"

    /** Device/model constraints shared by every arm. */
    data class ArmConstraints(
        val modelSha256: String,
        val modelSizeBytes: Long,
        val quantization: String,
        val workloadId: String,
        val promptSha256: String,
        val runtimeVersion: String,
        val runtimeAbi: String,
        val nativeVariant: String,
        val deviceMaxThreads: Int,
        val memAvailPct: Double?,
        val memLowMemory: Boolean?
    )

    /** One measurement offered to an M1 pack. */
    data class ArmMeasurement(
        val runId: String,
        val config: BenchConfigData,
        val genTps: Double,
        val prefillTps: Double,
        val thermalAfter: String,
        /** Caller asserts same model/workload/build. */
        val identityMatched: Boolean,
        /** Caller asserts COMPLETED + comparable. */
        val comparable: Boolean
    )

    data class ArmPack(
        val arm: String,
        val constraints: ArmConstraints,
        /** The SAME host-approved set every arm sees. */
        val candidates: List<BenchConfigData>,
        /**
         * Null for M0 (withheld by design); admitted-only
         * for M1. Never target outcomes.
         */
        val measurements: List<ArmMeasurement>?,
        /** Everything withheld or refused, with reasons. */
        val exclusions: List<String>,
        /** SHA-256 over the canonical pack bytes. */
        val packHash: String,
        /** SHA-256 over the canonical candidate set. */
        val candidateHash: String
    )

    fun m0Pack(
        constraints: ArmConstraints,
        candidates: List<BenchConfigData>,
        withheldCount: Int
    ): ArmPack {
        require(candidates.isNotEmpty()) { "candidate set must be non-empty" }
        for (candidate in candidates) {
            val errors = BenchConfig.validate(candidate)
            require(errors.isEmpty()) {
                "candidate fails host validation: $errors"
            }
            require(candidate.nThreads <= constraints.deviceMaxThreads) {
                "candidate exceeds device thread max"
            }
        }
        return assemble(
            arm = ARM_M0,
            constraints = constraints,
            candidates = candidates,
            measurements = null,
            exclusions = listOf(
                "withheld $withheldCount measurement(s) by M0 design: " +
                    "constraints only, no historical performance"
            )
        )
    }

    fun m1Pack(
        constraints: ArmConstraints,
        candidates: List<BenchConfigData>,
        offered: List<ArmMeasurement>
    ): ArmPack {
        require(candidates.isNotEmpty()) { "candidate set must be non-empty" }
        for (candidate in candidates) {
            val errors = BenchConfig.validate(candidate)
            require(errors.isEmpty()) {
                "candidate fails host validation: $errors"
            }
            require(candidate.nThreads <= constraints.deviceMaxThreads) {
                "candidate exceeds device thread max"
            }
        }
        val admitted = mutableListOf<ArmMeasurement>()
        val exclusions = mutableListOf<String>()
        for (m in offered) {
            val reason = when {
                !m.identityMatched ->
                    "identity mismatch (model/workload/build)"
                !m.comparable -> "not COMPLETED/comparable"
                !m.genTps.isFinite() || m.genTps <= 0 ->
                    "unusable throughput"
                else -> null
            }
            if (reason == null) admitted.add(m)
            else exclusions.add("refused ${m.runId}: $reason")
        }
        return assemble(
            arm = ARM_M1,
            constraints = constraints,
            candidates = candidates,
            measurements = admitted,
            exclusions = exclusions
        )
    }

    /**
     * All arms MUST share one candidate set: differing
     * hashes prove a broken comparison. Throws when the
     * packs disagree.
     */
    fun requireSharedCandidates(vararg packs: ArmPack) {
        val hashes = packs.map { it.candidateHash }.toSet()
        require(hashes.size <= 1) {
            "arms span ${hashes.size} candidate sets; " +
                "comparisons need exactly one"
        }
    }

    /**
     * Stage B response gate: every M0/M1 reply validates
     * through the production proposal schema. Thin
     * wrapper so the evaluation path cannot drift from
     * the shipped gate.
     */
    fun validateArmResponse(
        payload: Map<String, Any?>,
        rawJson: String,
        expected: AgentApi.ProposalExpectation
    ): AgentApi.ValidProposal =
        AgentApi.validateProposal(payload, rawJson, expected)

    // ---- cost accounting (structure only; no live calls) ----

    data class CostEntry(
        val arm: String,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val latencyMs: Long? = null,
        /** USD when the provider reports usage. */
        val costUsd: Double? = null,
        val note: String = ""
    )

    data class CostLedger(val entries: List<CostEntry>) {
        fun totalCost(): EvalMetrics.MetricValue {
            val known = entries.mapNotNull { it.costUsd }
            if (known.isEmpty()) {
                return EvalMetrics.MetricValue.missing(
                    "provider reported no usage"
                )
            }
            return EvalMetrics.MetricValue.ok(known.sum(), known.size)
        }

        fun meanLatency(): EvalMetrics.MetricValue {
            val known = entries.mapNotNull { it.latencyMs?.toDouble() }
            if (known.isEmpty()) {
                return EvalMetrics.MetricValue.missing(
                    "no latency observations"
                )
            }
            return EvalMetrics.MetricValue.ok(
                known.sum() / known.size, known.size)
        }
    }

    // ---- internals ----

    private fun assemble(
        arm: String,
        constraints: ArmConstraints,
        candidates: List<BenchConfigData>,
        measurements: List<ArmMeasurement>?,
        exclusions: List<String>
    ): ArmPack {
        val ordered = candidates.sortedWith(
            compareBy({ it.nCtx }, { it.nThreads }, { it.nPredict })
        )
        val candidateMaps = ordered.map { SessionBenchBridge.configMap(it) }
        val candidateHash = BenchConfig.sha256Hex(
            SessionJson.stringify(candidateMaps)
                .toByteArray(Charsets.UTF_8)
        )
        val canonical = SessionJson.stringify(linkedMapOf(
            "arm" to arm,
            "constraints" to linkedMapOf(
                "model_sha256" to constraints.modelSha256,
                "model_size_bytes" to constraints.modelSizeBytes,
                "quantization" to constraints.quantization,
                "workload_id" to constraints.workloadId,
                "prompt_sha256" to constraints.promptSha256,
                "runtime_version" to constraints.runtimeVersion,
                "runtime_abi" to constraints.runtimeAbi,
                "native_variant" to constraints.nativeVariant,
                "device_max_threads" to constraints.deviceMaxThreads,
                "mem_avail_pct" to constraints.memAvailPct,
                "mem_low_memory" to constraints.memLowMemory
            ),
            "candidates" to candidateMaps,
            "measurements" to measurements?.map {
                linkedMapOf(
                    "run_id" to it.runId,
                    "config" to SessionBenchBridge.configMap(it.config),
                    "gen_tps" to it.genTps,
                    "prefill_tps" to it.prefillTps,
                    "thermal_after" to it.thermalAfter
                )
            },
            "exclusions" to exclusions.sorted()
        ))
        return ArmPack(
            arm = arm,
            constraints = constraints,
            candidates = ordered,
            measurements = measurements,
            exclusions = exclusions,
            packHash = BenchConfig.sha256Hex(
                canonical.toByteArray(Charsets.UTF_8)),
            candidateHash = candidateHash
        )
    }
}
