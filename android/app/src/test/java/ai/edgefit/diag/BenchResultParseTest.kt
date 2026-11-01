package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BenchResultParseTest {
    companion object {
        const val NOW = 1793419432000L // 2026-10-31T04:03:52Z
        const val MODEL_SHA =
            "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
        const val PROMPT_SHA =
            "36104db0657a294048410f9043d4efc8510a77088ca1e2f17c0b6c8359c44382"

        /** Minimal contract-valid synthetic fixture (not a measurement). */
        fun validDoc(
            runId: String = "BENCH-TEST-001",
            status: String = "COMPLETED",
            mutate: MutableMap<String, Any?>.() -> Unit = {}
        ): String {
            val root = linkedMapOf<String, Any?>(
                "contract" to "edgefit-bench-result",
                "contract_version" to 1,
                "run_id" to runId,
                "device" to linkedMapOf(
                    "model" to "TEST-PHONE",
                    "sdk_int" to 36,
                    "supported_abis" to listOf("arm64-v8a"),
                    "logical_processors" to 8,
                    "total_ram_bytes" to 8000000000L),
                "model" to linkedMapOf(
                    "display_name" to "test-model.gguf",
                    "size_bytes" to 1000L,
                    "sha256" to MODEL_SHA,
                    "quantization" to "Q4_K_M"),
                "runtime" to linkedMapOf(
                    "impl" to "llama.cpp", "version" to "0.7.0-dev",
                    "backend" to "cpu", "abi" to "arm64-v8a"),
                "config" to linkedMapOf(
                    "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64,
                    "temperature" to 0.0, "sampler" to "greedy",
                    "seed" to 42, "timeout_ms" to 600000L),
                "workload" to linkedMapOf(
                    "id" to "edgefit-prompt-v1",
                    "prompt_sha256" to PROMPT_SHA,
                    "prompt_chars" to 120),
                "measurements" to linkedMapOf(
                    "load_ms" to 100L, "prefill_ms" to 1000L,
                    "prefill_tokens" to 23, "gen_ms" to 4000L,
                    "generated_tokens" to 64,
                    "tps_gen" to 16.0, "tps_prefill" to 23.0,
                    "eos_stop" to false,
                    "mem" to linkedMapOf(
                        "pss_before_kb" to 100, "pss_after_kb" to 200,
                        "alloc_delta_bytes" to 50L, "method" to "test"),
                    "thermal" to linkedMapOf(
                        "before" to "none", "mid" to "none",
                        "after" to "none", "available" to true)),
                "status" to status,
                "error" to if (status == "COMPLETED") null else "boom",
                "app_version" to "0.2.0-test",
                "collected_at_utc" to "2026-10-31T03:00:00Z")
            root.mutate()
            return SessionJson.stringify(root)
        }
    }

    @Test
    fun `valid document parses with summary fields`() {
        val parsed = BenchResultParse.parse(validDoc(), NOW)
        assertEquals("BENCH-TEST-001", parsed.summary.runId)
        assertEquals(64, parsed.summary.sha256.length)
        assertEquals("COMPLETED", parsed.summary.status)
        assertEquals(16.0, parsed.summary.tpsGen, 0.0)
        assertEquals(4, parsed.summary.nThreads)
        assertEquals(MODEL_SHA, parsed.summary.modelSha256)
        assertEquals("TEST-PHONE", parsed.summary.deviceModel)
    }

    @Test
    fun `raw map preserves every original key`() {
        val parsed = BenchResultParse.parse(validDoc {
            put("provenance", "on_device_measurement")
            put("x_mem_conditions", linkedMapOf("avail_pct_start" to 27.5))
        }, NOW)
        assertTrue(parsed.raw.containsKey("measurements"))
        assertEquals("on_device_measurement", parsed.raw["provenance"])
        assertEquals(27.5, parsed.summary.memAvailPctStart)
    }

    @Test
    fun `malformed json rejected`() {
        expectInvalid("{not json")
    }

    @Test
    fun `wrong contract rejected`() {
        expectInvalid(validDoc { put("contract", "other") })
    }

    @Test
    fun `bad run id rejected`() {
        expectInvalid(validDoc(runId = "../escape"))
    }

    @Test
    fun `future timestamp rejected`() {
        expectInvalid(validDoc {
            put("collected_at_utc", "2027-01-01T00:00:00Z")
        })
    }

    @Test
    fun `bad model sha rejected`() {
        expectInvalid(validDoc {
            @Suppress("UNCHECKED_CAST")
            val model = get("model") as MutableMap<String, Any?>
            model["sha256"] = "ZZZ"
        })
    }

    @Test
    fun `inconsistent throughput rejected`() {
        expectInvalid(validDoc {
            @Suppress("UNCHECKED_CAST")
            val measurements = get("measurements") as MutableMap<String, Any?>
            measurements["tps_gen"] = 99.0
        })
    }

    @Test
    fun `failed status without error rejected`() {
        expectInvalid(validDoc(status = "FAILED") {
            put("error", null)
        })
    }

    @Test
    fun `completed with error rejected`() {
        expectInvalid(validDoc { put("error", "stale") })
    }

    @Test
    fun `oversize document rejected`() {
        expectInvalid(validDoc(), maxBytes = 10)
    }

    @Test
    fun `failed run parses`() {
        val parsed = BenchResultParse.parse(validDoc(status = "FAILED"), NOW)
        assertEquals("FAILED", parsed.summary.status)
        assertEquals("boom", parsed.summary.error)
    }

    @Test
    fun `iso parsing round-trips`() {
        val ms = BenchResultParse.parseIsoUtc("2026-10-31T03:00:00Z")!!
        assertEquals("2026-10-31T03:00:00Z", BenchResultParse.formatIsoUtc(ms))
        assertNull(BenchResultParse.parseIsoUtc("not-a-date"))
        assertNull(BenchResultParse.parseIsoUtc("2026-13-01T00:00:00Z"))
    }

    private fun expectInvalid(text: String, maxBytes: Int = 1 shl 20) {
        try {
            BenchResultParse.parse(text, NOW, maxBytes)
            fail("expected InvalidResult")
        } catch (_: BenchResultParse.InvalidResult) {
        }
    }
}
