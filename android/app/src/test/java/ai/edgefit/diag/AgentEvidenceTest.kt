package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM tests for bounded on-device evidence selection. */
class AgentEvidenceTest {
    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
    }

    private lateinit var manager: SessionManager

    private fun setup() {
        manager = SessionManager(temp.newFolder()) { "2026-10-28T00:00:00Z" }
    }

    private fun parent(): SessionRecord = manager.createSession(
        mode = "agent",
        deviceModel = "SM-S908U",
        profileSha256 = null,
        modelSha256 = MODEL_SHA,
        modelSizeBytes = 100L,
        modelDisplay = "mock.gguf",
        workloadId = BenchContract.WORKLOAD_ID,
        promptSha256 = PROMPT_SHA,
        config = SessionBenchBridge.configMap(BenchConfig.default())
    )

    private fun result(
        runId: String,
        genMs: Long,
        tokens: Int,
        status: String = BenchContract.STATUS_COMPLETED,
        config: BenchConfigData = BenchConfig.default(),
        modelSha: String = MODEL_SHA,
        deviceModel: String = "SM-S908U",
        runtimeVersion: String = "r1"
    ): BenchResult = BenchResult(
        runId = runId,
        device = BenchDeviceSnapshot("0".repeat(64), deviceModel, 36,
            listOf("arm64-v8a"), 8, 12884901888L),
        model = BenchModelIdentity("mock.gguf", 100L, modelSha, "4",
            mapOf("general.architecture" to "llama")),
        runtimeVersion = runtimeVersion,
        runtimeAbi = "arm64-v8a",
        config = config,
        workload = BenchWorkload(BenchContract.WORKLOAD_ID, PROMPT_SHA, 100),
        measurements = BenchMeasurements(1200L, 1000L, 23, genMs, tokens,
            BenchConfig.tps(tokens, genMs), 23.0, false,
            BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
            BenchThermal("none", "none", "none", true)),
        status = status,
        error = if (status == BenchContract.STATUS_COMPLETED) null else "mock",
        appVersion = "mock",
        collectedAtUtc = "2026-10-28T01:00:00Z"
    )

    private fun child(
        parent: SessionRecord,
        seq: Int,
        bench: BenchResult,
        predictedGen: Double = 15.0
    ): SessionRecord {
        val record = manager.createSession(
            mode = "byok",
            deviceModel = bench.device.model,
            profileSha256 = null,
            modelSha256 = bench.model.sha256,
            modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = parent.workloadId,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(bench.config),
            parentSessionId = parent.sessionId,
            experimentSeq = seq
        )
        manager.requestPrediction(record.sessionId)
        manager.recordPrediction(record.sessionId, predictedGen, 23.0, "{}",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(),
            "2026-10-28T00:00:00Z")
        manager.authorizeBenchmark(record.sessionId)
        manager.noteRunStarted(record.sessionId)
        return SessionBenchBridge.attach(manager, record.sessionId, bench)
    }

    private fun childrenOf(parent: SessionRecord): List<SessionRecord> =
        manager.store.listIds().mapNotNull {
            try {
                manager.get(it)
            } catch (_: Exception) {
                null
            }
        }.filter { it.parentSessionId == parent.sessionId }

    @Test
    fun comparableRunsSelectedWithValues() {
        setup()
        val parent = parent()
        child(parent, 1, result("BENCH-A", 5000L, 64))
        child(parent, 2, result("BENCH-B", 4000L, 64,
            config = BenchConfig.default().copy(nThreads = 8)))
        val pack = AgentEvidence.buildPack(parent, childrenOf(parent))
        assertEquals(2, pack.comparable.size)
        assertEquals(0, pack.contextual.size)
        assertEquals("BENCH-A", pack.comparable[0].runId)
        assertEquals(12.8, pack.comparable[0].genTps, 0.0)
        assertEquals(16.0, pack.comparable[1].genTps, 0.0)
        assertEquals(4, pack.comparable[0].nThreads)
        assertEquals(8, pack.comparable[1].nThreads)
        // Config is the varied axis: different threads stay comparable.
        assertEquals(64, pack.packHash.length)
    }

    @Test
    fun incomparableRunsLabeledContextual() {
        setup()
        val parent = parent()
        child(parent, 1, result("BENCH-GOOD", 5000L, 64))
        child(parent, 2, result("BENCH-FAILED", 5000L, 64,
            status = BenchContract.STATUS_FAILED))
        child(parent, 3, result("BENCH-OTHER-DEV", 5000L, 64,
            deviceModel = "Pixel-9"))
        // Model-mismatched child cannot attach honestly; craft by
        // direct save to test the selector's own guard.
        val odd = manager.createSession(
            mode = "byok", deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = "0".repeat(64), modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = parent.workloadId,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            parentSessionId = parent.sessionId,
            experimentSeq = 4
        )
        manager.store.save(odd.copy(
            benchmark = mapOf(
                "run_id" to "BENCH-OTHER-MODEL",
                "status" to BenchContract.STATUS_COMPLETED,
                "measurements" to mapOf(
                    "tps_gen" to 12.8, "tps_prefill" to 23.0,
                    "thermal_before" to "none", "thermal_mid" to "none",
                    "thermal_after" to "none")
            )
        ))
        val pack = AgentEvidence.buildPack(parent, childrenOf(parent))
        assertEquals(1, pack.comparable.size)
        assertEquals(3, pack.contextual.size)
        val reasons = pack.contextual.map { it.contextReason }
        assertTrue(reasons.any { it!!.contains("not COMPLETED") })
        assertTrue(reasons.any { it!!.contains("device differs") })
        assertTrue(reasons.any { it!!.contains("model differs") })
    }

    @Test
    fun runtimeChangeIsContextualUnknownIsFine() {
        setup()
        val parent = parent()
        child(parent, 1, result("BENCH-R1", 5000L, 64, runtimeVersion = "r1"))
        child(parent, 2, result("BENCH-R2", 5000L, 64, runtimeVersion = "r2"))
        val pack = AgentEvidence.buildPack(parent, childrenOf(parent))
        assertEquals(1, pack.comparable.size)
        assertEquals("BENCH-R1", pack.comparable[0].runId)
        assertEquals(1, pack.contextual.size)
        assertTrue(pack.contextual[0].contextReason!!.contains("runtime"))
    }

    @Test
    fun packCapsAndOrderingDeterministic() {
        setup()
        val parent = parent()
        repeat(7) { i ->
            child(parent, i + 1,
                result("BENCH-%d".format(i + 1), 5000L, 64))
        }
        val pack = AgentEvidence.buildPack(parent, childrenOf(parent))
        assertEquals(AgentEvidence.MAX_COMPARABLE, pack.comparable.size)
        // Newest five, ascending.
        assertEquals("BENCH-3", pack.comparable.first().runId)
        assertEquals("BENCH-7", pack.comparable.last().runId)
        val again = AgentEvidence.buildPack(
            manager.get(parent.sessionId), childrenOf(parent))
        assertEquals(pack.packHash, again.packHash)
    }

    @Test
    fun renderPackLabelsTiers() {
        setup()
        val parent = parent()
        child(parent, 1, result("BENCH-A", 5000L, 64))
        child(parent, 2, result("BENCH-B", 5000L, 64,
            status = BenchContract.STATUS_FAILED))
        val text = AgentEvidence.renderPack(
            AgentEvidence.buildPack(parent, childrenOf(parent)))
        assertTrue(text.contains("COMPARABLE_EVIDENCE:"))
        assertTrue(text.contains("BENCH-A"))
        assertTrue(text.contains("CONTEXTUAL_EVIDENCE"))
        assertTrue(text.contains("do not pool"))
        val empty = AgentEvidence.renderPack(AgentEvidence.EvidencePack(
            emptyList(), emptyList(), "0".repeat(64)))
        assertTrue(empty.contains("no on-device measurements"))
    }

    @Test
    fun childrenWithoutBenchmarkSkipped() {
        setup()
        val parent = parent()
        manager.createSession(
            mode = "byok", deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = parent.workloadId,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default()),
            parentSessionId = parent.sessionId,
            experimentSeq = 1
        )
        val pack = AgentEvidence.buildPack(parent, childrenOf(parent))
        assertTrue(pack.comparable.isEmpty())
        assertTrue(pack.contextual.isEmpty())
    }

    @Test
    fun bridgeCarriesRuntimeProvenance() {
        val map = SessionBenchBridge.measurementsMap(
            BenchMeasurements(1L, 1L, 1, 1L, 1, 1.0, 1.0, false,
                BenchMemory(0, 0, 0L, "m"),
                BenchThermal("a", "b", "c", true)),
            "rV", "arm64-v8a", mapOf("variant" to "v", "native_opt" to "o"))
        assertEquals("rV", map["runtime_version"])
        assertEquals("v", map["native_variant"])
        assertNull(SessionBenchBridge.measurementsMap(
            BenchMeasurements(1L, 1L, 1, 1L, 1, 1.0, 1.0, false,
                BenchMemory(0, 0, 0L, "m"),
                BenchThermal("a", "b", "c", true)))["runtime_version"])
    }
}
