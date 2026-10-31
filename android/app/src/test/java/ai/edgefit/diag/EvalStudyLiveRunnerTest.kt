package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FINAL Task 3: owner-executable live M0/M1 decision
 * capture. One invocation captures ONE arm; each call
 * needs its own explicit authorization. No retries, no
 * hidden calls, no key in args/logs/outputs (key comes
 * from EF_STUDY_API_KEY env only and is scrubbed from
 * every error).
 *
 * Env wiring:
 * - EF_STUDY_ARM: M0 or M1 (unset → fixture self-check).
 * - EF_STUDY_STUDYDIR: frozen study directory.
 * - EF_STUDY_AUTHORIZE: must be exactly YES.
 * - EF_STUDY_LIVE: YES performs the POST; anything else
 *   is verify-only (frozen request + manifest + guards
 *   checked, nothing sent, nothing written).
 * - EF_STUDY_API_KEY: provider key (live mode only).
 *
 * Live writes (into the study dir, never overwriting):
 * study-decision-<arm>.json, study-response-<arm>.json.
 * Either file already present refuses BEFORE any POST,
 * so a decision can never double-charge.
 */
class EvalStudyLiveRunnerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun ownerLiveCall() {
        val arm = System.getenv("EF_STUDY_ARM")?.ifBlank { null }
        if (arm == null) {
            gatingSelfCheck()
            return
        }
        if (arm != EvalArms.ARM_M0 && arm != EvalArms.ARM_M1) {
            fail("EF_STUDY_ARM must be M0 or M1, got $arm")
        }
        if (System.getenv("EF_STUDY_AUTHORIZE") != "YES") {
            fail("refusing without explicit per-call authorization " +
                "(EF_STUDY_AUTHORIZE=YES)")
        }
        val studyDir = System.getenv("EF_STUDY_STUDYDIR")?.ifBlank { null }
            ?: throw AssertionError("EF_STUDY_STUDYDIR is required")
        val dir = File(studyDir)
        val manifest = EvalStudy.parseManifest(
            File(dir, "study-manifest.json").readText(Charsets.UTF_8))
        val frozen = EvalStudy.loadFrozenRequest(
            File(dir, "study-request-$arm.json").readText(Charsets.UTF_8),
            arm, manifest)
        // M0/M1 separation re-checked on the frozen body.
        if (arm == EvalArms.ARM_M0) {
            val leaks = EvalStudy.verifyM0Clean(frozen.body, manifest)
            if (leaks.isNotEmpty()) {
                throw AssertionError(
                    "M0 body contaminated: ${leaks.joinToString("; ")}")
            }
        }
        val decisionFile = File(dir, "study-decision-$arm.json")
        val responseFile = File(dir, "study-response-$arm.json")
        if (decisionFile.isFile || responseFile.isFile) {
            throw AssertionError(
                "$arm already decided; decisions are immutable " +
                    "(one decision per arm, no overwrite, no re-call)")
        }
        if (System.getenv("EF_STUDY_LIVE") != "YES") {
            // Verify-only: everything above passed, nothing
            // sent, nothing written.
            assertTrue(frozen.body.isNotEmpty())
            return
        }
        val apiKey = System.getenv("EF_STUDY_API_KEY")?.ifBlank { null }
            ?: throw AssertionError(
                "EF_STUDY_API_KEY is required for the live call")
        val expectation = EvalStudy.StudyExpectation(
            candidateA = manifest.candidateA,
            candidateB = manifest.candidateB,
            modelSha256 = manifest.constraints.modelSha256,
            workloadId = manifest.constraints.workloadId,
            allowedRunIds = if (arm == EvalArms.ARM_M0) {
                emptySet()
            } else {
                manifest.admitted.map { it.runId }.toSet()
            },
            deviceMaxThreads = manifest.constraints.deviceMaxThreads
        )
        val startNs = System.nanoTime()
        val decision = try {
            EvalStudy.recordArmDecision(
                arm, frozen.body, expectation,
                EvalStudy.LiveStudyTransport(), apiKey,
                { SessionManager.utcNow() },
                EvalStudy.TRANSPORT_LIVE)
        } catch (e: Exception) {
            throw AssertionError(scrub(e.message, apiKey) +
                " [no retry: fix the cause, then decide]")
        }
        val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
        val cited = EvalStudy.verifyArmCitations(decision, manifest)
        if (cited.isNotEmpty()) {
            throw AssertionError(
                "citation re-check failed: ${cited.joinToString("; ")}")
        }
        val stored = decision.copy(latencyMs = latencyMs)
        // Raw response, preserved with hash (transport
        // already bounds it at 64 KiB).
        decisionFile.writeText(EvalStudy.decisionJson(stored),
            Charsets.UTF_8)
        responseFile.writeText(SessionJson.stringify(linkedMapOf(
            "study_id" to EvalStudy.STUDY_ID,
            "arm" to arm,
            "decided_at_utc" to stored.decidedAtUtc,
            "provider_request_id" to stored.providerRequestId,
            "request_sha256" to frozen.bodySha256,
            "response_sha256" to BenchConfig.sha256Hex(
                stored.rawJson.toByteArray(Charsets.UTF_8)),
            "latency_ms" to latencyMs,
            "prompt_tokens" to stored.promptTokens,
            "completion_tokens" to stored.completionTokens,
            "raw_json" to stored.rawJson
        )), Charsets.UTF_8)
        assertEquals(arm, stored.arm)
    }

    private fun scrub(message: String?, apiKey: String): String {
        if (message == null) return "live call failed"
        return message.replace(apiKey, "[redacted]")
    }

    /**
     * Fixture self-check (runs in every suite pass, no
     * network): frozen-request loading, guards, citation
     * re-checks, key/transport failure modes.
     */
    private fun gatingSelfCheck() {
        val json = SessionJson.stringify(linkedMapOf(
            "contract" to BenchContract.CONTRACT,
            "contract_version" to BenchContract.CONTRACT_VERSION,
            "run_id" to "BENCH-20261027T170002Z-ffff0001",
            "model" to linkedMapOf(
                "display_name" to "synthetic.gguf",
                "size_bytes" to EvalStudy.STUDY_MODEL_SIZE_BYTES,
                "sha256" to EvalStudy.STUDY_MODEL_SHA256,
                "quantization" to EvalStudy.STUDY_QUANTIZATION),
            "runtime" to linkedMapOf(
                "impl" to BenchContract.RUNTIME_IMPL,
                "version" to EvalStudy.STUDY_RUNTIME_VERSION,
                "backend" to BenchContract.BACKEND,
                "abi" to EvalStudy.STUDY_RUNTIME_ABI),
            "config" to SessionBenchBridge.configMap(
                EvalStudy.studyCandidateA()),
            "workload" to linkedMapOf(
                "id" to BenchContract.WORKLOAD_ID,
                "prompt_sha256" to EvalStudy.STUDY_PROMPT_SHA256,
                "prompt_chars" to 118),
            "measurements" to linkedMapOf(
                "tps_gen" to 14.0,
                "tps_prefill" to 22.0,
                "thermal" to linkedMapOf(
                    "before" to "none", "mid" to "none",
                    "after" to "none", "available" to true)),
            "status" to BenchContract.STATUS_COMPLETED,
            "collected_at_utc" to "2026-10-27T17:00:02Z",
            "x_native_build" to linkedMapOf(
                "native_opt" to "-O3 -g", "variant" to "benchmark")
        ))
        val bytes = json.toByteArray(Charsets.UTF_8)
        val out = EvalStudy.runPreflight(EvalStudy.PreflightInput(
            constraints = EvalStudy.studyConstraints(),
            candidateA = EvalStudy.studyCandidateA(),
            candidateB = EvalStudy.studyCandidateB(),
            artifacts = listOf(EvalStudy.ArtifactInput(
                "synthetic", BenchConfig.sha256Hex(bytes), bytes)),
            evidenceCutoffUtc = "2026-10-30T00:00:00Z",
            nowUtc = "2026-10-30T12:00:00Z"
        ))
        assertEquals(EvalStudy.VERDICT_READY, out.verdict)
        val manifest = EvalStudy.parseManifest(out.manifestJson!!)
        // Frozen request loads; tampering refuses.
        val frozen = EvalStudy.loadFrozenRequest(
            out.requestM0Json!!, EvalArms.ARM_M0, manifest)
        assertEquals(MetaApi.ENDPOINT, frozen.endpoint)
        try {
            EvalStudy.loadFrozenRequest(
                out.requestM0Json!!.replace(
                    MetaApi.ENDPOINT, "https://evil.example/v1/responses"),
                EvalArms.ARM_M0, manifest)
            fail("swapped endpoint accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_REQUEST", e.code)
        }
        try {
            EvalStudy.loadFrozenRequest(
                out.requestM0Json!!, EvalArms.ARM_M1, manifest)
            fail("wrong-arm request accepted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_REQUEST", e.code)
        }
        // Live transport: blank key refuses before any IO;
        // IO failures map to StudyError (fail closed).
        try {
            EvalStudy.LiveStudyTransport().post("   ", "{}")
            fail("blank key posted")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_KEY", e.code)
        }
        var calls = 0
        val failing = object : MetaTransport {
            override fun post(apiKey: String, body: String): MetaHttpResult {
                calls++
                throw java.io.IOException("synthetic network down")
            }
        }
        try {
            EvalStudy.LiveStudyTransport(failing).post("k", "{}")
            fail("IO failure escaped")
        } catch (e: EvalStudy.StudyError) {
            assertEquals("STUDY_PROVIDER", e.code)
        }
        assertEquals(1, calls)
    }
}
