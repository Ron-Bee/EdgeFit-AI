package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JVM tests for the Meta API contract, prompts, and validators. */
class MetaApiTest {
    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBody(body: String): Map<String, Any?> =
        SessionJson.parse(body) as Map<String, Any?>

    private fun validPrediction(): MutableMap<String, Any?> = mutableMapOf(
        "predicted_gen_tps" to 15.0,
        "predicted_prefill_tps" to 23.0,
        "confidence" to "medium",
        "rationale" to "bounded guess",
        "uncertainty_notes" to listOf("no prior evidence"),
        "evidence_cited" to listOf("none"),
        "limits" to listOf("single device guess"),
        "workload_id" to BenchContract.WORKLOAD_ID,
        "model_sha" to MODEL_SHA
    )

    private fun validFollowUp(): MutableMap<String, Any?> = mutableMapOf(
        "direction" to "overestimated",
        "summary" to "measured below prediction",
        "limitations" to listOf("single pair"),
        "supports_conclusion" to false,
        "next_experiment" to mapOf(
            "description" to "rerun to check variance",
            "config_changes" to mapOf("n_threads" to 8),
            "rationale" to "threads may bind throughput"
        ),
        "run_id" to "BENCH-1"
    )

    // ---- request shape ----

    @Test
    @Suppress("UNCHECKED_CAST")
    fun requestBodyMatchesQualifiedShape() {
        val body = MetaApi.requestBody("sys", "user")
        val map = parseBody(body)
        assertEquals(MetaApi.MODEL, map["model"])
        assertEquals(0L, map["temperature"])
        val input = map["input"] as List<Map<String, Any?>>
        assertEquals(2, input.size)
        assertEquals("system", input[0]["role"])
        assertEquals("sys", input[0]["content"])
        assertEquals("user", input[1]["role"])
        assertEquals("user", input[1]["content"])
        // No-tools: both keys absent, the supported opt-out.
        assertFalse(map.containsKey("tools"))
        assertFalse(map.containsKey("tool_choice"))
        assertFalse(map.containsKey("parallel_tool_calls"))
    }

    @Test
    fun requestBodyRejectsOversize() {
        try {
            MetaApi.requestBody("x".repeat(MetaApi.MAX_REQUEST_BYTES + 1), "u")
            fail("oversize accepted")
        } catch (e: MetaApi.MetaError) {
            assertEquals("REQUEST_TOO_LARGE", e.code)
        }
    }

    @Test
    fun authHeaderIsBearer() {
        assertEquals("Bearer k123", MetaApi.authHeader("k123"))
    }

    // ---- URL allowlist ----

    @Test
    fun endpointPassesAllowlist() {
        assertTrue(MetaApi.checkUrl(MetaApi.ENDPOINT))
        assertTrue(MetaApi.checkUrl("https://api.meta.ai:443/v1/responses"))
    }

    @Test
    fun hostileUrlsRejected() {
        for (bad in listOf(
            "http://api.meta.ai/v1/responses",
            "https://evil.com/v1/responses",
            "https://api.meta.ai.evil.com/v1/responses",
            "https://user:pass@api.meta.ai/v1/responses",
            "https://api.meta.ai:8443/v1/responses",
            "https://api.meta.ai/v1/other",
            "https://api.meta.ai/",
            "not a url",
            ""
        )) {
            assertFalse("accepted: $bad", MetaApi.checkUrl(bad))
        }
    }

    // ---- prompts ----

    @Test
    fun predictionPromptCarriesIdentities() {
        val user = MetaApi.predictionUser(
            listOf("model=x"), "m.gguf", MODEL_SHA, 100L, "unknown",
            BenchContract.WORKLOAD_ID, "ab".repeat(32),
            BenchConfig.default(), "v", "arm64-v8a",
            listOf(Triple("BENCH-9", 11.0, 22.0))
        )
        assertTrue(user.contains(MODEL_SHA))
        assertTrue(user.contains(BenchContract.WORKLOAD_ID))
        assertTrue(user.contains("BENCH-9"))
        assertTrue(MetaApi.predictionSystem().contains("ONLY"))
    }

    @Test
    fun predictionPromptDeclaresNoEvidence() {
        val user = MetaApi.predictionUser(
            listOf("model=x"), "m.gguf", MODEL_SHA, 100L, "unknown",
            BenchContract.WORKLOAD_ID, "ab".repeat(32),
            BenchConfig.default(), "v", "arm64-v8a", emptyList()
        )
        assertTrue(user.contains("none"))
    }

    // ---- response extraction ----

    @Test
    fun extractTextReadsQualifiedShapes() {
        assertEquals("hi", MetaApi.extractText(mapOf("output_text" to "hi")))
        val nested = mapOf("output" to listOf(
            mapOf("type" to "function_call", "name" to "x"),
            mapOf("type" to "message", "content" to listOf(
                mapOf("type" to "output_text", "text" to "a"),
                mapOf("type" to "text", "text" to "b"),
                mapOf("type" to "image", "text" to "no")
            ))
        ))
        assertEquals("a\nb", MetaApi.extractText(nested))
    }

    @Test
    fun extractTextToleratesGarbage() {
        assertEquals("", MetaApi.extractText(null))
        assertEquals("", MetaApi.extractText(listOf(1)))
        assertEquals("", MetaApi.extractText(mapOf("output" to "nope")))
        assertEquals("", MetaApi.extractText(mapOf("output_text" to 5)))
    }

    @Test
    fun extractJsonObjectSlicesBraces() {
        val pure = """{"a":1}"""
        assertEquals(1L, MetaApi.extractJsonObject(pure)?.get("a"))
        val wrapped = "Here:\n{\"a\":2}\nDone."
        assertEquals(2L, MetaApi.extractJsonObject(wrapped)?.get("a"))
        assertNull(MetaApi.extractJsonObject("no braces"))
        assertNull(MetaApi.extractJsonObject("{oops"))
        assertNull(MetaApi.extractJsonObject("[1,2]"))
    }

    @Test
    fun rawSliceEnforcesBounds() {
        assertEquals("{\"a\":1}", MetaApi.rawSlice("x {\"a\":1} y"))
        try {
            MetaApi.rawSlice("nothing")
            fail("accepted")
        } catch (e: MetaApi.MetaError) {
            assertEquals("BAD_RESPONSE", e.code)
        }
        try {
            MetaApi.rawSlice("{\"" + "k".repeat(25000) + "\":1}")
            fail("oversize accepted")
        } catch (e: MetaApi.MetaError) {
            assertEquals("REPLY_TOO_LARGE", e.code)
        }
    }

    // ---- prediction validation ----

    @Test
    fun validPredictionPasses() {
        val raw = SessionJson.stringify(validPrediction())
        val valid = MetaApi.validatePrediction(
            validPrediction(), raw, BenchContract.WORKLOAD_ID, MODEL_SHA,
            emptySet())
        assertEquals(15.0, valid.genTps, 0.0)
        assertEquals("medium", valid.confidence)
    }

    @Test
    fun citedRunIdMustBeSupplied() {
        val payload = validPrediction()
        payload["evidence_cited"] = listOf("BENCH-9")
        val raw = SessionJson.stringify(payload)
        MetaApi.validatePrediction(payload, raw, BenchContract.WORKLOAD_ID,
            MODEL_SHA, setOf("BENCH-9"))
        try {
            MetaApi.validatePrediction(payload, raw, BenchContract.WORKLOAD_ID,
                MODEL_SHA, emptySet())
            fail("uncited id accepted")
        } catch (e: MetaApi.MetaError) {
            assertEquals("BAD_PREDICTION", e.code)
        }
    }

    @Test
    fun predictionRejectsMissingFields() {
        for (key in listOf("predicted_gen_tps", "predicted_prefill_tps",
            "confidence", "rationale", "uncertainty_notes", "evidence_cited",
            "limits", "workload_id", "model_sha")) {
            val payload = validPrediction()
            payload.remove(key)
            try {
                MetaApi.validatePrediction(payload, "{}", 
                    BenchContract.WORKLOAD_ID, MODEL_SHA, emptySet())
                fail("missing $key accepted")
            } catch (e: MetaApi.MetaError) {
                assertEquals(key, "BAD_PREDICTION", e.code)
            }
        }
    }

    @Test
    fun predictionRejectsBadThroughputs() {
        for (bad in listOf(0.0, -1.0, Double.NaN,
            Double.POSITIVE_INFINITY, 2_000_000.0, "fast")) {
            for (key in listOf("predicted_gen_tps", "predicted_prefill_tps")) {
                val payload = validPrediction()
                payload[key] = bad
                try {
                    MetaApi.validatePrediction(payload, "{}",
                        BenchContract.WORKLOAD_ID, MODEL_SHA, emptySet())
                    fail("accepted $key=$bad")
                } catch (e: MetaApi.MetaError) {
                    assertEquals("BAD_PREDICTION", e.code)
                }
            }
        }
    }

    @Test
    fun predictionRejectsIdentityMismatch() {
        val workload = validPrediction()
        workload["workload_id"] = "other-v9"
        try {
            MetaApi.validatePrediction(workload, "{}",
                BenchContract.WORKLOAD_ID, MODEL_SHA, emptySet())
            fail("workload mismatch accepted")
        } catch (e: MetaApi.MetaError) {
            assertEquals("BAD_PREDICTION", e.code)
        }
        val model = validPrediction()
        model["model_sha"] = "0".repeat(64)
        try {
            MetaApi.validatePrediction(model, "{}",
                BenchContract.WORKLOAD_ID, MODEL_SHA, emptySet())
            fail("model mismatch accepted")
        } catch (_: MetaApi.MetaError) {
        }
        // Case-insensitive sha match passes.
        val upper = validPrediction()
        upper["model_sha"] = MODEL_SHA.uppercase()
        MetaApi.validatePrediction(upper, "{}", BenchContract.WORKLOAD_ID,
            MODEL_SHA, emptySet())
    }

    @Test
    fun predictionRejectsBadConfidenceAndOversize() {
        val confidence = validPrediction()
        confidence["confidence"] = "certain"
        try {
            MetaApi.validatePrediction(confidence, "{}",
                BenchContract.WORKLOAD_ID, MODEL_SHA, emptySet())
            fail("bad confidence accepted")
        } catch (_: MetaApi.MetaError) {
        }
        val big = validPrediction()
        big["rationale"] = "x".repeat(MetaApi.MAX_TEXT_FIELD + 1)
        try {
            MetaApi.validatePrediction(big, "{}",
                BenchContract.WORKLOAD_ID, MODEL_SHA, emptySet())
            fail("oversize rationale accepted")
        } catch (_: MetaApi.MetaError) {
        }
        val blank = validPrediction()
        blank["rationale"] = "  "
        try {
            MetaApi.validatePrediction(blank, "{}",
                BenchContract.WORKLOAD_ID, MODEL_SHA, emptySet())
            fail("blank rationale accepted")
        } catch (_: MetaApi.MetaError) {
        }
    }

    // ---- follow-up validation ----

    @Test
    fun validFollowUpPassesAllDirections() {
        val over = MetaApi.validateFollowUp(
            validFollowUp(), "{}", "BENCH-1", -3.825, -25.5)
        assertEquals("overestimated", over.direction)
        assertTrue(over.proposalValid)
        val under = validFollowUp()
        under["direction"] = "underestimated"
        MetaApi.validateFollowUp(under, "{}", "BENCH-1", 2.0, 13.3)
        val accurate = validFollowUp()
        accurate["direction"] = "accurate"
        accurate["next_experiment"] = null
        val result = MetaApi.validateFollowUp(
            accurate, "{}", "BENCH-1", 0.1, 1.0)
        assertNull(result.nextExperiment)
    }

    @Test
    fun followUpRejectsDirectionDisagreement() {
        // Claims overestimated while measured above prediction.
        try {
            MetaApi.validateFollowUp(
                validFollowUp(), "{}", "BENCH-1", 2.0, 13.3)
            fail("disagreement accepted")
        } catch (e: MetaApi.MetaError) {
            assertEquals("BAD_FOLLOWUP", e.code)
        }
        // Claims accurate at -25.5%.
        val accurate = validFollowUp()
        accurate["direction"] = "accurate"
        try {
            MetaApi.validateFollowUp(accurate, "{}", "BENCH-1", -3.825, -25.5)
            fail("false accurate accepted")
        } catch (_: MetaApi.MetaError) {
        }
        // Wrong run echo.
        try {
            MetaApi.validateFollowUp(
                validFollowUp(), "{}", "BENCH-OTHER", -3.825, -25.5)
            fail("run mismatch accepted")
        } catch (_: MetaApi.MetaError) {
        }
    }

    @Test
    fun invalidProposalQuarantinedNotFatal() {
        val payload = validFollowUp()
        @Suppress("UNCHECKED_CAST")
        val experiment = (payload["next_experiment"] as Map<String, Any?>)
            .toMutableMap()
        experiment["config_changes"] = mapOf("n_threads" to 99)
        payload["next_experiment"] = experiment
        val result = MetaApi.validateFollowUp(
            payload, "{}", "BENCH-1", -3.825, -25.5)
        assertFalse(result.proposalValid)
        assertTrue(result.proposalRejection!!.contains("n_threads"))
        assertEquals("overestimated", result.direction)
    }

    @Test
    fun proposalRejectsBadKeysAndShapes() {
        for (changes in listOf(
            mapOf("shell" to "rm -rf"),
            mapOf("n_ctx" to 64),
            mapOf("n_predict" to 999),
            emptyMap<String, Any?>(),
            "not-an-object"
        )) {
            val payload = validFollowUp()
            @Suppress("UNCHECKED_CAST")
            val experiment = (payload["next_experiment"] as Map<String, Any?>)
                .toMutableMap()
            experiment["config_changes"] = changes
            payload["next_experiment"] = experiment
            val result = MetaApi.validateFollowUp(
                payload, "{}", "BENCH-1", -3.825, -25.5)
            assertFalse("accepted $changes", result.proposalValid)
        }
    }

    // ---- status + redaction ----

    @Test
    fun classifyStatusMapsKnownCodes() {
        assertEquals("OK", MetaApi.classifyStatus(200))
        assertEquals("INVALID_KEY", MetaApi.classifyStatus(401))
        assertEquals("INVALID_KEY", MetaApi.classifyStatus(403))
        assertEquals("RATE_LIMITED", MetaApi.classifyStatus(429))
        assertEquals("SERVER_ERROR", MetaApi.classifyStatus(500))
        assertEquals("SERVER_ERROR", MetaApi.classifyStatus(503))
        assertEquals("HTTP_400", MetaApi.classifyStatus(400))
    }

    @Test
    fun redactScrubsKeyMaterial() {
        assertEquals("err [REDACTED] end",
            MetaApi.redact("err sk-secret-1 end", "sk-secret-1"))
        assertEquals("untouched", MetaApi.redact("untouched", null))
        // Short strings are never treated as secrets.
        assertEquals("abc", MetaApi.redact("abc", "abc"))
    }
}
