package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** EF-COMP-01A Stage A: arm contracts, separation, ledger. */
class EvalArmsTest {

    private fun constraints() = EvalArms.ArmConstraints(
        modelSha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83",
        modelSizeBytes = 807143936L,
        quantization = "4",
        workloadId = BenchContract.WORKLOAD_ID,
        promptSha256 = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382",
        runtimeVersion = "0.7.0-dev",
        runtimeAbi = "arm64-v8a",
        nativeVariant = "benchmark",
        deviceMaxThreads = 8,
        memAvailPct = 24.7,
        memLowMemory = false
    )

    private fun candidates() = listOf(
        BenchConfig.default().copy(nThreads = 2),
        BenchConfig.default().copy(nThreads = 4),
        BenchConfig.default().copy(nThreads = 8)
    )

    private fun measurement(
        runId: String,
        threads: Int = 4,
        gen: Double = 16.0,
        identity: Boolean = true,
        comparable: Boolean = true
    ) = EvalArms.ArmMeasurement(
        runId = runId,
        config = BenchConfig.default().copy(nThreads = threads),
        genTps = gen,
        prefillTps = 23.0,
        thermalAfter = "none",
        identityMatched = identity,
        comparable = comparable
    )

    @Test
    fun m0WithholdsAllMeasurementsByDesign() {
        val pack = EvalArms.m0Pack(constraints(), candidates(),
            withheldCount = 4)
        assertEquals(EvalArms.ARM_M0, pack.arm)
        assertNull(pack.measurements)
        assertTrue(pack.exclusions.any { it.contains("withheld 4") })
        assertEquals(64, pack.packHash.length)
    }

    @Test
    fun m1AdmitsOnlyIdentityMatchedComparable() {
        val pack = EvalArms.m1Pack(
            constraints(), candidates(),
            offered = listOf(
                measurement("BENCH-GOOD"),
                measurement("BENCH-WRONG-BUILD", identity = false),
                measurement("BENCH-FAILED", comparable = false),
                measurement("BENCH-BAD-TPS", gen = Double.NaN)
            )
        )
        assertEquals(listOf("BENCH-GOOD"),
            pack.measurements!!.map { it.runId })
        assertEquals(3, pack.exclusions.size)
        assertTrue(pack.exclusions.any { it.contains("BENCH-WRONG-BUILD") })
    }

    @Test
    fun candidateSetsMustMatchAcrossArms() {
        val m0 = EvalArms.m0Pack(constraints(), candidates(),
            withheldCount = 1)
        val m1 = EvalArms.m1Pack(constraints(), candidates(),
            offered = listOf(measurement("BENCH-GOOD")))
        EvalArms.requireSharedCandidates(m0, m1)
        val other = EvalArms.m0Pack(constraints(),
            listOf(BenchConfig.default()), withheldCount = 1)
        try {
            EvalArms.requireSharedCandidates(m0, other)
            fail("mismatched candidate sets accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("candidate sets"))
        }
    }

    @Test
    fun packsAreDeterministic() {
        val reversed = candidates().reversed()
        val first = EvalArms.m1Pack(constraints(), candidates(),
            offered = listOf(measurement("BENCH-GOOD")))
        val second = EvalArms.m1Pack(constraints(), reversed,
            offered = listOf(measurement("BENCH-GOOD")))
        assertEquals(first.packHash, second.packHash)
        assertEquals(first.candidateHash, second.candidateHash)
    }

    @Test
    fun candidatesMustPassHostValidation() {
        try {
            EvalArms.m0Pack(constraints(),
                listOf(BenchConfig.default().copy(nCtx = 99999)),
                withheldCount = 0)
            fail("unvalidated candidate accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("host validation"))
        }
        try {
            EvalArms.m0Pack(constraints(),
                listOf(BenchConfig.default().copy(nThreads = 16)),
                withheldCount = 0)
            fail("over-device candidate accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("thread max"))
        }
        try {
            EvalArms.m0Pack(constraints(), emptyList(), withheldCount = 0)
            fail("empty candidate set accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-empty"))
        }
    }

    @Test
    fun armResponsesValidateThroughProductionGate() {
        val expected = AgentApi.ProposalExpectation(
            modelSha = constraints().modelSha256,
            workloadId = BenchContract.WORKLOAD_ID,
            currentConfig = BenchConfig.default(),
            nextSeq = 2,
            allowedRunIds = setOf("BENCH-GOOD"),
            deviceMaxThreads = 8
        )
        val valid = EvalArms.validateArmResponse(
            mapOf(
                "model_sha" to constraints().modelSha256,
                "workload_id" to BenchContract.WORKLOAD_ID,
                "experiment_seq" to 2,
                "current_config" to mapOf(
                    "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
                "propose_experiment" to true,
                "proposed_config" to mapOf("n_threads" to 8),
                "hypothesis" to "synthetic fixture",
                "expected_direction" to "faster",
                "expected_gen_tps" to 18.0,
                "expected_prefill_tps" to 24.0,
                "uncertainty" to "synthetic",
                "supporting_evidence_ids" to listOf("BENCH-GOOD"),
                "limitations" to listOf("synthetic"),
                "benefit" to "synthetic",
                "no_experiment_reason" to null
            ),
            """{"synthetic":"fixture"}""",
            expected
        )
        assertEquals(8, valid.proposedConfig!!.nThreads)
        try {
            EvalArms.validateArmResponse(
                mapOf("propose_experiment" to "eventually"),
                """{"synthetic":"bad"}""",
                expected
            )
            fail("malformed arm response accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
    }

    @Test
    fun costLedgerTotalsOrWithholds() {
        val ledger = EvalArms.CostLedger(listOf(
            EvalArms.CostEntry("M0", latencyMs = 1000L, costUsd = 0.02),
            EvalArms.CostEntry("M1", latencyMs = 3000L)
        ))
        assertEquals(0.02, ledger.totalCost().value!!, 1e-9)
        assertEquals(2000.0, ledger.meanLatency().value!!, 1e-9)
        val silent = EvalArms.CostLedger(listOf(
            EvalArms.CostEntry("R", note = "no provider call")))
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            silent.totalCost().status)
        assertEquals(EvalMetrics.NOT_EVALUABLE,
            silent.meanLatency().status)
    }
}
