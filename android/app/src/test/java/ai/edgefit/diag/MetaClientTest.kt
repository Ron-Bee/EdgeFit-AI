package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque

/**
 * JVM tests for live orchestration. The transport is scripted; every
 * payload here is a mock labeled as such — mocks never qualify the
 * live API or the device path.
 */
class MetaClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Scripted fake transport. Records bodies, never real keys. */
    class FakeTransport : MetaTransport {
        val script = ArrayDeque<Any>()
        val bodies = mutableListOf<String>()
        var calls = 0
        var sawKey = false

        override fun post(apiKey: String, body: String): MetaHttpResult {
            calls++
            sawKey = sawKey || apiKey.isNotEmpty()
            bodies.add(body)
            return when (val next = script.removeFirst()) {
                is MetaHttpResult -> next
                is IOException -> throw next
                is MetaApi.MetaError -> throw next
                else -> throw AssertionError("bad script entry")
            }
        }

        fun respondText(text: String) {
            script.add(MetaHttpResult(200, SessionJson.stringify(
                mapOf("output_text" to text))))
        }

        fun respondStatus(status: Int, body: String) {
            script.add(MetaHttpResult(status, body))
        }
    }

    class FakeSeal : KeySeal {
        override fun seal(plain: ByteArray) =
            "fake-iv-0123".toByteArray(Charsets.UTF_8) to plain.reversedArray()

        override fun open(iv: ByteArray, cipher: ByteArray) =
            cipher.reversedArray()
    }

    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
        const val PROMPT_SHA = "36104db0657a294048410f9043d4efc8510a77088ca1e2f1" +
            "7c0b6c8359c44382"
        const val KEY = "mock-key-clearly-labeled-fake"
    }

    private lateinit var manager: SessionManager
    private lateinit var transport: FakeTransport
    private lateinit var vault: MetaKeyVault
    private lateinit var client: MetaSessionClient

    private fun setup() {
        val dir = temp.newFolder()
        manager = SessionManager(dir) { "2026-10-28T00:00:00Z" }
        transport = FakeTransport()
        vault = MetaKeyVault(dir, FakeSeal())
        client = MetaSessionClient(manager, transport, vault)
    }

    private fun pendingSession(): String {
        val record = manager.createSession(
            mode = "byok",
            deviceModel = "SM-S908U",
            profileSha256 = null,
            modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default())
        )
        manager.requestPrediction(record.sessionId)
        return record.sessionId
    }

    private fun spec(id: String) = MetaSessionClient.PredictionSpec(
        sessionId = id,
        deviceLines = listOf("model=mock"),
        modelDisplay = "mock.gguf",
        modelSha = MODEL_SHA,
        modelSizeBytes = 100L,
        quantization = "unknown",
        workloadId = BenchContract.WORKLOAD_ID,
        promptSha = PROMPT_SHA,
        config = BenchConfig.default(),
        runtimeVersion = "mock",
        runtimeAbi = "arm64-v8a"
    )

    private fun predictionJson(
        gen: Any? = 15.0,
        prefill: Any? = 23.0,
        evidence: List<String> = listOf("none")
    ): String = SessionJson.stringify(linkedMapOf(
        "predicted_gen_tps" to gen,
        "predicted_prefill_tps" to prefill,
        "confidence" to "low",
        "rationale" to "mock rationale",
        "uncertainty_notes" to listOf("mock uncertainty"),
        "evidence_cited" to evidence,
        "limits" to listOf("mock limit"),
        "workload_id" to BenchContract.WORKLOAD_ID,
        "model_sha" to MODEL_SHA
    ))

    private fun followUpJson(
        direction: String = "overestimated",
        runId: String = "BENCH-MOCK-1"
    ): String = SessionJson.stringify(linkedMapOf(
        "direction" to direction,
        "summary" to "mock assessment",
        "limitations" to listOf("mock limit"),
        "supports_conclusion" to false,
        "next_experiment" to null,
        "run_id" to runId
    ))

    private fun completedResult(runId: String): BenchResult = BenchResult(
        runId = runId,
        device = BenchDeviceSnapshot("0".repeat(64), "SM-S908U", 36,
            listOf("arm64-v8a"), 8, 12884901888L),
        model = BenchModelIdentity("mock.gguf", 100L, MODEL_SHA, "4",
            mapOf("general.architecture" to "llama")),
        runtimeVersion = "mock",
        runtimeAbi = "arm64-v8a",
        config = BenchConfig.default(),
        workload = BenchWorkload(BenchContract.WORKLOAD_ID, PROMPT_SHA, 100),
        measurements = BenchMeasurements(1200L, 1000L, 23, 5000L, 64,
            12.8, 23.0, false,
            BenchMemory(100, 200, 4096L, BenchRunner.MEM_METHOD),
            BenchThermal("none", "none", "none", true)),
        status = BenchContract.STATUS_COMPLETED,
        error = null,
        appVersion = "mock",
        collectedAtUtc = "2026-10-28T01:00:00Z"
    )

    /** Drives a byok session to COMPARISON_READY via offline fixtures. */
    private fun comparedSession(runId: String): String {
        val record = manager.createSession(
            mode = "byok",
            deviceModel = "SM-S908U",
            profileSha256 = null,
            modelSha256 = MODEL_SHA,
            modelSizeBytes = 100L,
            modelDisplay = "mock.gguf",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(BenchConfig.default())
        )
        val id = record.sessionId
        manager.requestPrediction(id)
        manager.recordPrediction(id, 15.0, 23.0, """{"mock":true}""",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(),
            "2026-10-28T00:00:00Z")
        manager.authorizeBenchmark(id)
        manager.noteRunStarted(id)
        SessionBenchBridge.attach(manager, id, completedResult(runId))
        return id
    }

    // ---- prediction ----

    @Test
    @Suppress("UNCHECKED_CAST")
    fun predictionSuccessPersistsLiveRecord() {
        setup()
        val id = pendingSession()
        vault.setSessionKey(KEY)
        transport.respondText(predictionJson())
        val record = client.requestPrediction(spec(id))
        assertEquals(SessionState.PREDICTION_RECORDED, record.state)
        val prediction = record.prediction as Map<String, Any?>
        assertEquals(15.0, prediction["predicted_gen_tps"])
        assertEquals(SessionManager.SOURCE_LIVE_META,
            prediction["source_type"])
        assertEquals(1, transport.calls)
        assertTrue(transport.sawKey)
        // Wire shape: qualified model, no tools, identities present.
        val body = SessionJson.parse(transport.bodies.single())
            as Map<String, Any?>
        assertEquals(MetaApi.MODEL, body["model"])
        assertFalse(body.containsKey("tools"))
        val input = body["input"] as List<Map<String, Any?>>
        assertTrue((input[1]["content"] as String).contains(MODEL_SHA))
    }

    @Test
    fun noKeyMeansNoTransportCall() {
        setup()
        val id = pendingSession()
        try {
            client.requestPrediction(spec(id))
            fail("keyless call attempted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("NO_KEY", e.code)
        }
        assertEquals(0, transport.calls)
        assertEquals(SessionState.PREDICTION_PENDING, manager.get(id).state)
    }

    @Test
    fun malformedReplyPersistsNothing() {
        setup()
        val id = pendingSession()
        vault.setSessionKey(KEY)
        transport.respondText("I think it will be fast, no JSON here.")
        try {
            client.requestPrediction(spec(id))
            fail("prose accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("BAD_RESPONSE", e.code)
        }
        assertEquals(1, transport.calls)
        assertNull(manager.get(id).prediction)
        assertEquals(SessionState.PREDICTION_PENDING, manager.get(id).state)
    }

    @Test
    fun invalidPredictionPersistsNothing() {
        setup()
        val id = pendingSession()
        vault.setSessionKey(KEY)
        transport.respondText(predictionJson(evidence = listOf("BENCH-FAKE")))
        try {
            client.requestPrediction(spec(id))
            fail("uncited evidence accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("BAD_PREDICTION", e.code)
        }
        assertNull(manager.get(id).prediction)
        assertEquals(SessionState.PREDICTION_PENDING, manager.get(id).state)
    }

    @Test
    fun httpFailuresMapToStableCodes() {
        for ((status, code) in listOf(
            401 to "INVALID_KEY", 403 to "INVALID_KEY",
            429 to "RATE_LIMITED", 500 to "SERVER_ERROR",
            503 to "SERVER_ERROR", 400 to "HTTP_400"
        )) {
            setup()
            val id = pendingSession()
            vault.setSessionKey(KEY)
            transport.respondStatus(status, """{"error":"mock"}""")
            try {
                client.requestPrediction(spec(id))
                fail("HTTP $status accepted")
            } catch (e: MetaSessionClient.ClientError) {
                assertEquals("HTTP $status", code, e.code)
            }
            assertEquals(1, transport.calls)
            assertNull(manager.get(id).prediction)
        }
    }

    @Test
    fun transportFailuresMapOfflineTimeoutTls() {
        setup()
        var id = pendingSession()
        vault.setSessionKey(KEY)
        transport.script.add(UnknownHostException("mock dns"))
        try {
            client.requestPrediction(spec(id))
            fail("dns failure accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("OFFLINE", e.code)
        }
        id = pendingSession()
        transport.script.add(SocketTimeoutException("mock timeout"))
        try {
            client.requestPrediction(spec(id))
            fail("timeout accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("TIMEOUT", e.code)
        }
    }

    @Test
    fun ioErrorDetailRedactsKey() {
        setup()
        val id = pendingSession()
        vault.setSessionKey(KEY)
        transport.script.add(IOException("reset by $KEY peer"))
        try {
            client.requestPrediction(spec(id))
            fail("io error accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("NETWORK_ERROR", e.code)
            assertFalse(e.message!!.contains(KEY))
            assertTrue(e.message!!.contains("[REDACTED]"))
        }
    }

    @Test
    fun nonJsonAndBlankRepliesRejected() {
        setup()
        var id = pendingSession()
        vault.setSessionKey(KEY)
        transport.script.add(MetaHttpResult(200, "not json{{{"))
        try {
            client.requestPrediction(spec(id))
            fail("non-JSON accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("BAD_RESPONSE", e.code)
        }
        id = pendingSession()
        transport.script.add(MetaHttpResult(200, """{"output_text":"  "}"""))
        try {
            client.requestPrediction(spec(id))
            fail("blank accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("BAD_RESPONSE", e.code)
        }
    }

    @Test
    fun duplicateConcurrentPredictionSuppressed() {
        setup()
        val id = pendingSession()
        vault.setSessionKey(KEY)
        val gate = java.util.concurrent.CountDownLatch(1)
        val blocking = object : MetaTransport {
            override fun post(apiKey: String, body: String): MetaHttpResult {
                gate.await()
                return MetaHttpResult(200, SessionJson.stringify(
                    mapOf("output_text" to predictionJson())))
            }
        }
        val gated = MetaSessionClient(manager, blocking, vault)
        var threadError: Throwable? = null
        val thread = Thread({
            try {
                gated.requestPrediction(spec(id))
            } catch (e: Throwable) {
                threadError = e
            }
        })
        thread.start()
        Thread.sleep(300)
        try {
            gated.requestPrediction(spec(id))
            fail("duplicate admitted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("IN_PROGRESS", e.code)
        } finally {
            gate.countDown()
        }
        thread.join(5000)
        assertNull(threadError)
        assertEquals(SessionState.PREDICTION_RECORDED,
            manager.get(id).state)
    }

    @Test
    fun guardReleasesAfterFailureForRetry() {
        setup()
        val id = pendingSession()
        vault.setSessionKey(KEY)
        transport.respondStatus(429, "mock limited")
        transport.respondText(predictionJson())
        try {
            client.requestPrediction(spec(id))
            fail("429 accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("RATE_LIMITED", e.code)
        }
        val record = client.requestPrediction(spec(id))
        assertEquals(SessionState.PREDICTION_RECORDED, record.state)
        assertEquals(2, transport.calls)
    }

    // ---- follow-up ----

    @Test
    @Suppress("UNCHECKED_CAST")
    fun followUpSuccessPersistsAssessment() {
        setup()
        val id = comparedSession("BENCH-MOCK-1")
        manager.requestFollowup(id)
        vault.setSessionKey(KEY)
        transport.respondText(followUpJson())
        val record = client.requestFollowUp(id)
        assertEquals(SessionState.RECOMMENDATION_RECORDED, record.state)
        val followup = record.followup as Map<String, Any?>
        assertEquals("live_meta ${MetaApi.MODEL}", followup["provenance"])
        val recommendation = followup["recommendation"] as Map<String, Any?>
        assertEquals("overestimated", recommendation["direction"])
        assertEquals(1, transport.calls)
    }

    @Test
    fun followUpWithoutComparisonMakesNoCall() {
        setup()
        val id = pendingSession()
        vault.setSessionKey(KEY)
        try {
            client.requestFollowUp(id)
            fail("comparison-less follow-up attempted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("NO_COMPARISON", e.code)
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun followUpDirectionMismatchPersistsNothing() {
        setup()
        val id = comparedSession("BENCH-MOCK-2")
        manager.requestFollowup(id)
        vault.setSessionKey(KEY)
        // Measured 12.8 < predicted 15.0: "underestimated" disagrees.
        transport.respondText(followUpJson("underestimated", "BENCH-MOCK-2"))
        try {
            client.requestFollowUp(id)
            fail("disagreement accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("BAD_FOLLOWUP", e.code)
        }
        assertNull(manager.get(id).followup)
        assertEquals(SessionState.FOLLOWUP_PENDING, manager.get(id).state)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun followUpInvalidProposalQuarantined() {
        setup()
        val id = comparedSession("BENCH-MOCK-3")
        manager.requestFollowup(id)
        vault.setSessionKey(KEY)
        val payload = SessionJson.stringify(linkedMapOf(
            "direction" to "overestimated",
            "summary" to "mock assessment",
            "limitations" to listOf("mock limit"),
            "supports_conclusion" to false,
            "next_experiment" to mapOf(
                "description" to "mock experiment",
                "config_changes" to mapOf("n_threads" to 99),
                "rationale" to "mock rationale"
            ),
            "run_id" to "BENCH-MOCK-3"
        ))
        transport.respondText(payload)
        val record = client.requestFollowUp(id)
        val recommendation =
            (record.followup as Map<String, Any?>)["recommendation"]
                as Map<String, Any?>
        assertEquals(false, recommendation["proposal_valid"])
        assertTrue((recommendation["proposal_rejection"] as String)
            .contains("n_threads"))
    }

    // ---- agent proposals (mock transport; never live) ----

    private fun proposalSpec() = MetaSessionClient.ProposalSpec(
        parentId = "SES-0000000000000000",
        packText = "mock pack",
        currentConfig = BenchConfig.default(),
        nextSeq = 2,
        modelSha = MODEL_SHA,
        workloadId = BenchContract.WORKLOAD_ID,
        attemptsUsed = 1,
        attemptsMax = 5,
        allowedRunIds = setOf("BENCH-1"),
        deviceMaxThreads = 8
    )

    private fun proposalJson(): String = SessionJson.stringify(linkedMapOf(
        "model_sha" to MODEL_SHA,
        "workload_id" to BenchContract.WORKLOAD_ID,
        "experiment_seq" to 2,
        "current_config" to mapOf(
            "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
        "propose_experiment" to true,
        "proposed_config" to mapOf("n_threads" to 8),
        "hypothesis" to "mock hypothesis",
        "expected_direction" to "faster",
        "expected_gen_tps" to 16.0,
        "expected_prefill_tps" to 24.0,
        "uncertainty" to "mock uncertainty",
        "supporting_evidence_ids" to listOf("BENCH-1"),
        "limitations" to listOf("mock limit"),
        "benefit" to "mock benefit",
        "no_experiment_reason" to null
    ))

    @Test
    fun proposalSuccessValidatesWithoutPersisting() {
        setup()
        vault.setSessionKey(KEY)
        transport.respondText(proposalJson())
        val valid = client.requestProposal(proposalSpec())
        assertTrue(valid.proposeExperiment)
        assertEquals(8, valid.proposedConfig!!.nThreads)
        assertEquals(1, transport.calls)
        // The client persists nothing; the store is untouched.
        assertEquals(0, manager.store.count())
    }

    @Test
    fun proposalRefusedWithoutKey() {
        setup()
        try {
            client.requestProposal(proposalSpec())
            fail("keyless proposal attempted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("NO_KEY", e.code)
        }
        assertEquals(0, transport.calls)
    }

    @Test
    fun proposalValidationFailureSurfacesCode() {
        setup()
        vault.setSessionKey(KEY)
        transport.respondText(SessionJson.stringify(linkedMapOf(
            "model_sha" to "0".repeat(64),
            "workload_id" to BenchContract.WORKLOAD_ID,
            "experiment_seq" to 2,
            "current_config" to mapOf(
                "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
            "propose_experiment" to true,
            "proposed_config" to mapOf("n_threads" to 8),
            "hypothesis" to "h",
            "expected_direction" to "faster",
            "expected_gen_tps" to 16.0,
            "expected_prefill_tps" to 24.0,
            "uncertainty" to "u",
            "supporting_evidence_ids" to listOf("BENCH-1"),
            "limitations" to emptyList<String>(),
            "benefit" to "b",
            "no_experiment_reason" to null
        )))
        try {
            client.requestProposal(proposalSpec())
            fail("identity mismatch accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
        assertEquals(1, transport.calls)
    }

    @Test
    fun proposalHonorsHttpAndTransportErrors() {
        setup()
        vault.setSessionKey(KEY)
        transport.respondStatus(429, "mock limited")
        try {
            client.requestProposal(proposalSpec())
            fail("429 accepted")
        } catch (e: MetaSessionClient.ClientError) {
            assertEquals("RATE_LIMITED", e.code)
        }
    }

    // ---- prior evidence ----

    @Test
    fun priorEvidenceSelectsComparableCompletedOnly() {
        setup()
        comparedSession("BENCH-MOCK-A")
        comparedSession("BENCH-MOCK-B")
        // Different config: must be excluded.
        val other = manager.createSession(
            mode = "byok", deviceModel = "SM-S908U", profileSha256 = null,
            modelSha256 = MODEL_SHA, modelSizeBytes = 100L,
            modelDisplay = "mock.gguf", workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = PROMPT_SHA,
            config = SessionBenchBridge.configMap(
                BenchConfig.default().copy(nThreads = 8))
        )
        manager.requestPrediction(other.sessionId)
        manager.recordPrediction(other.sessionId, 15.0, 23.0, "{}",
            SessionManager.SOURCE_OFFLINE_TEST, emptyList(),
            "2026-10-28T00:00:00Z")
        manager.authorizeBenchmark(other.sessionId)
        manager.noteRunStarted(other.sessionId)
        // Attach needs matching config; craft a matching result instead
        // of reusing the default-config fixture would pass — but the
        // point is exclusion, so attach with its own config.
        val custom = completedResult("BENCH-MOCK-OTHER").copy(
            config = BenchConfig.default().copy(nThreads = 8))
        SessionBenchBridge.attach(manager, other.sessionId, custom)
        val prior = client.priorEvidence(
            MODEL_SHA, BenchContract.WORKLOAD_ID, BenchConfig.default())
        assertEquals(2, prior.size)
        assertEquals(
            setOf("BENCH-MOCK-A", "BENCH-MOCK-B"),
            prior.map { it.first }.toSet())
        assertTrue(prior.all { it.second == 12.8 && it.third == 23.0 })
    }
}
