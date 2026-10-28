package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JVM tests for the agent proposal protocol and validator. */
class AgentApiTest {
    companion object {
        const val MODEL_SHA = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0" +
            "fece9385611df83"
    }

    private fun expectation() = AgentApi.ProposalExpectation(
        modelSha = MODEL_SHA,
        workloadId = BenchContract.WORKLOAD_ID,
        currentConfig = BenchConfig.default(),
        nextSeq = 2,
        allowedRunIds = setOf("BENCH-1"),
        deviceMaxThreads = 8
    )

    private fun validPayload(): MutableMap<String, Any?> = mutableMapOf(
        "model_sha" to MODEL_SHA,
        "workload_id" to BenchContract.WORKLOAD_ID,
        "experiment_seq" to 2,
        "current_config" to mapOf(
            "n_ctx" to 512, "n_threads" to 4, "n_predict" to 64),
        "propose_experiment" to true,
        "proposed_config" to mapOf("n_threads" to 8),
        "hypothesis" to "threads bind throughput",
        "expected_direction" to "faster",
        "expected_gen_tps" to 16.0,
        "expected_prefill_tps" to 24.0,
        "uncertainty" to "single-device guess",
        "supporting_evidence_ids" to listOf("BENCH-1"),
        "limitations" to listOf("thermal unknown"),
        "benefit" to "may raise throughput",
        "no_experiment_reason" to null
    )

    @Test
    fun promptsCarryBoundsAndDecline() {
        val system = AgentApi.proposalSystem(8)
        assertTrue(system.contains("1..8"))
        assertTrue(system.contains("128..1024"))
        assertTrue(system.contains("ONLY"))
        assertTrue(system.contains("declining is a"))
        val user = AgentApi.proposalUser(
            "pack", BenchConfig.default(), 2, MODEL_SHA,
            BenchContract.WORKLOAD_ID, 1, 5)
        assertTrue(user.contains(MODEL_SHA))
        assertTrue(user.contains("used=1 max=5"))
        assertTrue(user.contains("NEXT_SEQ: 2"))
    }

    @Test
    fun validProposalPassesWithDelta() {
        val valid = AgentApi.validateProposal(
            validPayload(), "{}", expectation())
        assertTrue(valid.proposeExperiment)
        assertEquals(8, valid.proposedConfig!!.nThreads)
        assertEquals(512, valid.proposedConfig!!.nCtx)
        assertEquals(listOf("n_threads"), valid.changedKeys)
        assertEquals(16.0, valid.expectedGenTps, 0.0)
        assertEquals(listOf("BENCH-1"), valid.supportingEvidenceIds)
    }

    @Test
    fun honestDeclineIsValidTerminal() {
        val payload = validPayload()
        payload["propose_experiment"] = false
        payload["proposed_config"] = null
        payload["no_experiment_reason"] = "evidence too thin"
        val valid = AgentApi.validateProposal(payload, "{}", expectation())
        assertFalse(valid.proposeExperiment)
        assertNull(valid.proposedConfig)
        assertEquals("evidence too thin", valid.noExperimentReason)
    }

    @Test
    fun declineRequiresReason() {
        val payload = validPayload()
        payload["propose_experiment"] = false
        payload["no_experiment_reason"] = null
        try {
            AgentApi.validateProposal(payload, "{}", expectation())
            fail("reasonless decline accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
    }

    @Test
    fun identityEchoesEnforced() {
        val model = validPayload()
        model["model_sha"] = "0".repeat(64)
        try {
            AgentApi.validateProposal(model, "{}", expectation())
            fail("model mismatch accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
        val workload = validPayload()
        workload["workload_id"] = "other-v9"
        try {
            AgentApi.validateProposal(workload, "{}", expectation())
            fail("workload mismatch accepted")
        } catch (_: AgentApi.AgentError) {
        }
        // Case-insensitive sha passes.
        val upper = validPayload()
        upper["model_sha"] = MODEL_SHA.uppercase()
        AgentApi.validateProposal(upper, "{}", expectation())
    }

    @Test
    fun staleSeqRejected() {
        val payload = validPayload()
        payload["experiment_seq"] = 3
        try {
            AgentApi.validateProposal(payload, "{}", expectation())
            fail("stale seq accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
    }

    @Test
    fun currentConfigEchoEnforced() {
        val payload = validPayload()
        payload["current_config"] = mapOf(
            "n_ctx" to 512, "n_threads" to 8, "n_predict" to 64)
        try {
            AgentApi.validateProposal(payload, "{}", expectation())
            fail("echo mismatch accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
    }

    @Test
    fun deltaKeysStrictlyAllowlisted() {
        for (changes in listOf(
            mapOf("shell" to "x"),
            mapOf("temperature" to 0.5),
            mapOf("sampler" to "top_k"),
            mapOf("seed" to 7),
            emptyMap<String, Any?>(),
            "nope"
        )) {
            val payload = validPayload()
            payload["proposed_config"] = changes
            try {
                AgentApi.validateProposal(payload, "{}", expectation())
                fail("accepted $changes")
            } catch (e: AgentApi.AgentError) {
                assertEquals("BAD_PROPOSAL", e.code)
            }
        }
    }

    @Test
    fun deltaRangesEnforced() {
        // In-contract but outside the agent's narrower ctx bound.
        for (changes in listOf(
            mapOf("n_ctx" to 64),
            mapOf("n_ctx" to 2048),
            mapOf("n_threads" to 0),
            mapOf("n_threads" to 9),
            mapOf("n_predict" to 0),
            mapOf("n_predict" to 300),
            mapOf("n_threads" to "eight")
        )) {
            val payload = validPayload()
            payload["proposed_config"] = changes
            try {
                AgentApi.validateProposal(payload, "{}", expectation())
                fail("accepted $changes")
            } catch (e: AgentApi.AgentError) {
                assertEquals("BAD_PROPOSAL", e.code)
            }
        }
        // Same values = changes nothing.
        val same = validPayload()
        same["proposed_config"] = mapOf("n_threads" to 4)
        try {
            AgentApi.validateProposal(same, "{}", expectation())
            fail("no-op accepted")
        } catch (_: AgentApi.AgentError) {
        }
        // n_predict must fit in n_ctx.
        val tight = validPayload()
        tight["proposed_config"] = mapOf("n_ctx" to 128, "n_predict" to 128)
        try {
            AgentApi.validateProposal(tight, "{}", expectation())
            fail("tight fit accepted")
        } catch (_: AgentApi.AgentError) {
        }
    }

    @Test
    fun expectationsAndEvidenceValidated() {
        val direction = validPayload()
        direction["expected_direction"] = "sideways"
        try {
            AgentApi.validateProposal(direction, "{}", expectation())
            fail("bad direction accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
        val cited = validPayload()
        cited["supporting_evidence_ids"] = listOf("BENCH-FAKE")
        try {
            AgentApi.validateProposal(cited, "{}", expectation())
            fail("uncited id accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
        val empty = validPayload()
        empty["supporting_evidence_ids"] = emptyList<String>()
        try {
            AgentApi.validateProposal(empty, "{}", expectation())
            fail("evidence-less proposal accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
        val tps = validPayload()
        tps["expected_gen_tps"] = -5.0
        try {
            AgentApi.validateProposal(tps, "{}", expectation())
            fail("negative expectation accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
    }

    @Test
    fun dangerousLookingExtrasIgnoredNeverActedOn() {
        // Execution reads allowlisted keys only; unknown fields ride
        // along in raw storage without any effect. If this stance
        // ever changes, this test must change with it.
        val payload = validPayload()
        payload["file_path"] = "/data/data/x"
        payload["shell_command"] = "rm -rf /"
        payload["callback_url"] = "https://evil.example/x"
        payload["sql"] = "DROP TABLE sessions"
        val valid = AgentApi.validateProposal(payload, "{}", expectation())
        assertEquals(listOf("n_threads"), valid.changedKeys)
    }

    @Test
    fun oversizeRawRejected() {
        try {
            AgentApi.validateProposal(validPayload(),
                "x".repeat(AgentApi.MAX_PROPOSAL_RAW_BYTES + 1), expectation())
            fail("oversize accepted")
        } catch (e: AgentApi.AgentError) {
            assertEquals("BAD_PROPOSAL", e.code)
        }
    }
}
