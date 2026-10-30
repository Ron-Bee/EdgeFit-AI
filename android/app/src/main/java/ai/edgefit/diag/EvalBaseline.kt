package ai.edgefit.diag

/**
 * EF-COMP-01A Stage A: deterministic rules-only baseline (arm
 * R) for the Muse value evaluation. Pure JVM, offline, no
 * network, no measurements of its own: it ranks a
 * host-approved candidate set against genuinely comparable
 * historical observations, exposes every ranking factor
 * (NFR-MODEL-01), and abstains when no defensible choice
 * exists. A credible alternative, not a strawman: it
 * enforces the same bounds the host enforces, never
 * excludes on advisory memory (MEM-03 policy), and never
 * manufactures a throughput prediction (predictedGen is
 * null for unmeasured configs).
 *
 * Documented decision rules, in order:
 * 1. Drop candidates failing [BenchConfig.validate] or
 *    exceeding the device thread max. Record each drop.
 * 2. No survivors -> abstain ("no host-valid candidate").
 * 3. A candidate is MEASURED iff it has >= 1 usable
 *    (finite, positive) comparable sample at its exact
 *    (n_ctx, n_threads, n_predict) triple. Multiple
 *    records at one triple AGGREGATE (samples
 *    concatenate, thermal counts sum) so input order
 *    cannot change the result — but only within one
 *    cohort: records sharing a triple MUST share one
 *    `cohortKey` (model + workload + runtime/build
 *    identity), and mixed cohorts throw rather than
 *    merge. Distinct cohorts are never pooled.
 * 4. Rank measured candidates by sample median,
 *    descending; ties break by ascending triple, so
 *    identical input always yields identical output.
 * 5. Unmeasured candidates rank below every measured
 *    one, in ascending triple order, with null
 *    predictions. No invented priors.
 * 6. No measured candidate -> abstain
 *    ("insufficient-evidence"), even if unmeasured
 *    candidates exist. Selecting blindly is not a
 *    baseline; it is a guess.
 * 7. Otherwise select the top-ranked candidate.
 */
object EvalBaseline {

    /**
     * Comparable observations at one exact config triple.
     * @param cohortKey identity of the model + workload +
     * runtime/build cohort (e.g. SHA over those fields).
     * Same-triple records aggregate ONLY when their keys
     * match; a mismatch throws instead of merging.
     */
    data class CandidateEvidence(
        val config: BenchConfigData,
        /** COMPLETED comparable samples; usability filtered. */
        val genSamples: List<Double>,
        /** Samples collected under elevated thermal. */
        val elevatedThermal: Int = 0,
        val cohortKey: String
    )

    data class RankedCandidate(
        val config: BenchConfigData,
        val medianGen: Double?,
        /** Usable sample count. */
        val n: Int,
        /**
         * Sample median when measured, else null. Never
         * estimated for unmeasured configs.
         */
        val predictedGen: Double?,
        val factors: List<String>
    )

    data class BaselineDecision(
        val ranked: List<RankedCandidate>,
        val selected: BenchConfigData?,
        val abstained: Boolean,
        val abstainReason: String?,
        /** Global decision factors (NFR-MODEL-01). */
        val factors: List<String>
    )

    fun decide(
        candidates: List<BenchConfigData>,
        evidence: List<CandidateEvidence>,
        deviceMaxThreads: Int,
        memAvailPct: Double? = null,
        memLowMemory: Boolean? = null
    ): BaselineDecision {
        val factors = mutableListOf(
            "device thread max: $deviceMaxThreads",
            when {
                memAvailPct == null ->
                    "memory unknown; no memory-based exclusion"
                else -> "memory ${"%.1f".format(memAvailPct)}% available" +
                    " (advisory only; never excludes)"
            }
        )
        if (memLowMemory == true) {
            factors.add("Android lowMemory signal present; advisory only")
        }
        val valid = mutableListOf<BenchConfigData>()
        for (candidate in candidates) {
            val errors = BenchConfig.validate(candidate).toMutableList()
            if (candidate.nThreads > deviceMaxThreads) {
                errors.add("n_threads exceeds device max")
            }
            if (errors.isEmpty()) {
                valid.add(candidate)
            } else {
                factors.add("excluded ${label(candidate)}: " +
                    errors.joinToString("; "))
            }
        }
        if (valid.isEmpty()) {
            return BaselineDecision(
                ranked = emptyList(),
                selected = null,
                abstained = true,
                abstainReason = "no host-valid candidate",
                factors = factors
            )
        }
        // R1: aggregate same-triple records (never last-wins)
        // and refuse to pool distinct cohorts.
        val byTriple = evidence.groupBy { triple(it.config) }
            .mapValues { (_, records) -> aggregate(records) }
        val ranked = valid.map { candidate ->
            rankOne(candidate, byTriple[triple(candidate)])
        }.sortedWith(
            compareByDescending<RankedCandidate> { it.medianGen ?: -1.0 }
                .thenBy { it.config.nCtx }
                .thenBy { it.config.nThreads }
                .thenBy { it.config.nPredict }
        )
        // Median -1.0 sorts unmeasured (null) below every
        // measured candidate, whose medians are positive.
        if (ranked.none { it.medianGen != null }) {
            factors.add("0 of ${valid.size} candidates measured")
            return BaselineDecision(
                ranked = ranked,
                selected = null,
                abstained = true,
                abstainReason = "insufficient-evidence: " +
                    "no candidate has a comparable measurement",
                factors = factors
            )
        }
        val best = ranked.first { it.medianGen != null }
        factors.add("selected ${label(best.config)}: best observed " +
            "median ${"%.3f".format(best.medianGen)} tok/s over " +
            "${best.n} sample(s)")
        return BaselineDecision(
            ranked = ranked,
            selected = best.config,
            abstained = false,
            abstainReason = null,
            factors = factors
        )
    }

    /**
     * Combines same-triple records order-invariantly.
     * @throws IllegalArgumentException on mixed cohorts.
     */
    private fun aggregate(records: List<CandidateEvidence>): CandidateEvidence {
        val keys = records.map { it.cohortKey }.toSet()
        require(keys.size == 1) {
            "refusing to pool ${keys.size} cohorts at one config: " +
                keys.sorted().joinToString(",")
        }
        return CandidateEvidence(
            config = records.first().config,
            genSamples = records.flatMap { it.genSamples },
            elevatedThermal = records.sumOf { it.elevatedThermal },
            cohortKey = keys.single()
        )
    }

    private fun rankOne(
        candidate: BenchConfigData,
        evidence: CandidateEvidence?
    ): RankedCandidate {
        val usable = (evidence?.genSamples ?: emptyList())
            .filter { it.isFinite() && it > 0 }
        if (usable.isEmpty()) {
            return RankedCandidate(
                config = candidate,
                medianGen = null,
                n = 0,
                predictedGen = null,
                factors = listOf(
                    "unmeasured: no comparable observation; " +
                        "no prediction offered"
                )
            )
        }
        val median = medianOf(usable)
        val factors = mutableListOf(
            "measured: n=${usable.size} median=" +
                "${"%.3f".format(median)} tok/s"
        )
        val elevated = evidence?.elevatedThermal ?: 0
        if (elevated > 0) {
            factors.add("caveat: $elevated/${usable.size} sample(s) " +
                "under elevated thermal")
        }
        return RankedCandidate(
            config = candidate,
            medianGen = median,
            n = usable.size,
            predictedGen = median,
            factors = factors
        )
    }

    private fun triple(config: BenchConfigData): Triple<Int, Int, Int> =
        Triple(config.nCtx, config.nThreads, config.nPredict)

    private fun label(config: BenchConfigData): String =
        "ctx=${config.nCtx} threads=${config.nThreads} " +
            "predict=${config.nPredict}"

    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
