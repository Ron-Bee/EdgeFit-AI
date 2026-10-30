package ai.edgefit.diag

/**
 * EF-COMP-01A Stage A: deterministic evaluation metrics
 * shared by arms R, M0, and M1. Pure JVM. Every metric
 * returns [MetricValue]: either OK with a value, or
 * NOT_EVALUABLE with an explicit reason. Missing data is
 * never silently converted to zero, and prediction error
 * is computed only when the prediction genuinely predates
 * the outcome (lexicographic UTC comparison on
 * zero-padded ISO-8601 stamps, same convention as
 * SessionManager ordering).
 */
object EvalMetrics {

    const val OK = "OK"
    const val NOT_EVALUABLE = "NOT_EVALUABLE"

    data class MetricValue(
        val value: Double?,
        val status: String,
        val reason: String?,
        /** Denominator that produced this metric. */
        val n: Int
    ) {
        companion object {
            fun ok(value: Double, n: Int): MetricValue =
                MetricValue(value, OK, null, n)

            fun missing(reason: String): MetricValue =
                MetricValue(null, NOT_EVALUABLE, reason, 0)
        }
    }

    /**
     * One evaluated arm response. Timestamps are ISO-8601
     * UTC (`predictedAtUtc` must be < `measuredAtUtc` for
     * error metrics); null means unrecorded, never zero.
     */
    data class ProposalCase(
        /** R, M0, or M1. */
        val arm: String,
        /** The arm offered an experiment (vs abstained). */
        val proposed: Boolean,
        /**
         * Host validation verdict; null when nothing was
         * submitted for validation.
         */
        val valid: Boolean?,
        val abstained: Boolean,
        /**
         * Whether abstention was appropriate (no defensible
         * choice existed); null when unknown.
         */
        val abstainAppropriate: Boolean?,
        val citedIds: List<String> = emptyList(),
        val knownIds: Set<String> = emptySet(),
        val predictedGen: Double? = null,
        val predictedAtUtc: String? = null,
        val measuredGen: Double? = null,
        val measuredAtUtc: String? = null,
        /**
         * Selected config triple + measured value per
         * actually tested option, for regret.
         */
        val selectedTriple: Triple<Int, Int, Int>? = null,
        val testedOptions: Map<Triple<Int, Int, Int>, Double> = emptyMap(),
        val latencyMs: Long? = null,
        /** API cost in USD when the provider reports usage. */
        val costUsd: Double? = null
    )

    data class EvalReport(
        val arm: String,
        val n: Int,
        val validProposalRate: MetricValue,
        val invalidRejectionRate: MetricValue,
        val fabricatedCitationRate: MetricValue,
        val abstentionRate: MetricValue,
        /** Share of abstentions judged appropriate. */
        val appropriateAbstentionRate: MetricValue,
        val meanSignedErrorTps: MetricValue,
        val meanAbsErrorTps: MetricValue,
        val meanSelectionRegretTps: MetricValue,
        /** Passthrough of observed physical values (n shown). */
        val observedThroughputs: List<Double>,
        val meanLatencyMs: MetricValue,
        val totalCostUsd: MetricValue,
        val limitations: List<String>
    )

    fun summarize(cases: List<ProposalCase>, arm: String): EvalReport {
        val scoped = cases.filter { it.arm == arm }
        val limitations = mutableListOf<String>()
        if (scoped.isEmpty()) limitations.add("no cases for arm $arm")
        return EvalReport(
            arm = arm,
            n = scoped.size,
            validProposalRate = rate(
                scoped.filter { it.proposed },
                { it.valid == true },
                "no proposed experiments to validate"
            ),
            // Share of proposed experiments the host
            // refused as invalid. Every valid==false case
            // was refused by construction.
            invalidRejectionRate = rate(
                scoped.filter { it.proposed },
                { it.valid == false },
                "no proposed experiments"
            ),
            fabricatedCitationRate = citationRate(scoped),
            abstentionRate = rate(
                scoped, { it.abstained }, "no cases evaluated"
            ),
            appropriateAbstentionRate = rate(
                scoped.filter {
                    it.abstained && it.abstainAppropriate != null
                },
                { it.abstainAppropriate == true },
                "no judged abstentions"
            ),
            meanSignedErrorTps = errorMetric(scoped, signed = true),
            meanAbsErrorTps = errorMetric(scoped, signed = false),
            meanSelectionRegretTps = regretMetric(scoped),
            observedThroughputs = scoped.mapNotNull {
                it.measuredGen?.takeIf { v -> v.isFinite() && v > 0 }
            },
            meanLatencyMs = meanOf(
                scoped.mapNotNull { it.latencyMs?.toDouble() },
                "no latency observations"
            ),
            totalCostUsd = if (scoped.any { it.costUsd != null }) {
                MetricValue.ok(
                    scoped.mapNotNull { it.costUsd }.sum(),
                    scoped.count { it.costUsd != null }
                )
            } else {
                MetricValue.missing("provider reported no usage")
            },
            limitations = limitations
        )
    }

    private fun rate(
        denominator: List<ProposalCase>,
        numerator: (ProposalCase) -> Boolean,
        emptyReason: String
    ): MetricValue {
        if (denominator.isEmpty()) return MetricValue.missing(emptyReason)
        return MetricValue.ok(
            denominator.count(numerator).toDouble() / denominator.size,
            denominator.size
        )
    }

    private fun meanOf(values: List<Double>, emptyReason: String): MetricValue {
        if (values.isEmpty()) return MetricValue.missing(emptyReason)
        return MetricValue.ok(values.sum() / values.size, values.size)
    }

    private fun citationRate(cases: List<ProposalCase>): MetricValue {
        val cited = cases.flatMap { it.citedIds }
        if (cited.isEmpty()) {
            return MetricValue.missing("no citations offered")
        }
        val known = cases.flatMap { it.knownIds }.toSet()
        val fabricated = cited.count { it !in known }
        return MetricValue.ok(fabricated.toDouble() / cited.size, cited.size)
    }

    private fun errorMetric(
        cases: List<ProposalCase>,
        signed: Boolean
    ): MetricValue {
        val errors = cases.mapNotNull { c ->
            val predicted = c.predictedGen
            val measured = c.measuredGen
            val at = c.predictedAtUtc
            val collected = c.measuredAtUtc
            if (predicted == null || measured == null ||
                at == null || collected == null
            ) {
                return@mapNotNull null
            }
            if (!predicted.isFinite() || predicted <= 0 ||
                !measured.isFinite() || measured < 0
            ) {
                return@mapNotNull null
            }
            // Prediction must genuinely predate the outcome.
            // Equal stamps are FOREIGN here (unlike the
            // same-second attach rule): evaluation pairs
            // come from frozen artifacts, and equality
            // proves nothing about ordering.
            if (!(at < collected)) return@mapNotNull null
            val signedError = measured - predicted
            if (signed) signedError else kotlin.math.abs(signedError)
        }
        if (errors.isEmpty()) {
            return MetricValue.missing(
                "no prediction genuinely predates its outcome"
            )
        }
        return MetricValue.ok(errors.sum() / errors.size, errors.size)
    }

    private fun regretMetric(cases: List<ProposalCase>): MetricValue {
        val regrets = cases.mapNotNull { c ->
            val selected = c.selectedTriple ?: return@mapNotNull null
            if (c.testedOptions.size < 2) return@mapNotNull null
            val selectedValue = c.testedOptions[selected]
                ?: return@mapNotNull null
            if (!selectedValue.isFinite()) return@mapNotNull null
            val best = c.testedOptions.values
                .filter { it.isFinite() }
                .maxOrNull() ?: return@mapNotNull null
            maxOf(0.0, best - selectedValue)
        }
        if (regrets.isEmpty()) {
            return MetricValue.missing(
                "no case selects among >= 2 tested options"
            )
        }
        return MetricValue.ok(regrets.sum() / regrets.size, regrets.size)
    }
}
