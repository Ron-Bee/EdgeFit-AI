package ai.edgefit.diag

/**
 * Agent session lifecycle over [SessionManager]. A parent (mode
 * "agent") sequences child byok experiments: exactly-once approval
 * consumes budget, proposals persist with evidence hashes, and
 * termination follows the governed rules (user finish, budget
 * exhaustion, three consecutive non-improving comparable runs,
 * declined proposal, double-invalid proposal). Every op is
 * @Synchronized; calls into SessionManager nest consistently
 * (agent -> manager, never the reverse). Pure JVM.
 */
class AgentManager(
    val manager: SessionManager,
    private val clock: () -> String = { SessionManager.utcNow() }
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_FINISHED = "finished"
        const val MAX_INVALID_STRIKES = 2
        const val MAX_REJECTED = 8
        const val STREAK_NON_IMPROVING = 3

        const val END_USER_FINISH = "user_finish"
        const val END_BUDGET_EXHAUSTED = "budget_exhausted"
        const val END_STAGNATION = "stagnation"
        const val END_NO_FURTHER_TESTING = "no_further_testing"
        const val END_INVALID_PROPOSAL = "invalid_proposal"

        /**
         * Stagnation metric (governed rule made precise): over the
         * comparable COMPLETED generation throughputs in seq order,
         * the trailing run in which no value strictly exceeds the max
         * of everything before it. Ties count as non-improving.
         */
        fun stagnationStreak(gens: List<Double>): Boolean {
            if (gens.size < STREAK_NON_IMPROVING) return false
            var best = Double.NEGATIVE_INFINITY
            var trailing = 0
            for (value in gens) {
                if (value > best) {
                    best = value
                    trailing = 0
                } else {
                    trailing++
                }
            }
            return trailing >= STREAK_NON_IMPROVING
        }
    }

    // ---- creation ----

    /**
     * Creates the parent plus the experiment-1 child shell (byok,
     * CREATED, base config). The child earns its prediction through
     * the normal BYOK path; nothing is pre-recorded.
     */
    @Synchronized
    fun createAgent(
        deviceModel: String?,
        profileSha256: String?,
        modelSha256: String,
        modelSizeBytes: Long,
        modelDisplay: String,
        workloadId: String,
        promptSha256: String,
        baseConfig: BenchConfigData,
        budgetMax: Int = 5
    ): SessionRecord {
        val configMap = SessionBenchBridge.configMap(baseConfig)
        var parent = manager.createSession(
            mode = "agent",
            deviceModel = deviceModel,
            profileSha256 = profileSha256,
            modelSha256 = modelSha256,
            modelSizeBytes = modelSizeBytes,
            modelDisplay = modelDisplay,
            workloadId = workloadId,
            promptSha256 = promptSha256,
            config = configMap,
            budgetMax = budgetMax
        )
        parent = manager.store.save(parent.copy(
            agent = newAgentMap(),
            history = parent.history // created entry already written
        ))
        manager.createSession(
            mode = "byok",
            deviceModel = deviceModel,
            profileSha256 = profileSha256,
            modelSha256 = modelSha256,
            modelSizeBytes = modelSizeBytes,
            modelDisplay = modelDisplay,
            workloadId = workloadId,
            promptSha256 = promptSha256,
            config = configMap,
            parentSessionId = parent.sessionId,
            experimentSeq = 1
        )
        return manager.get(parent.sessionId)
    }

    private fun newAgentMap(): Map<String, Any?> = linkedMapOf(
        "agent_version" to AgentApi.AGENT_VERSION,
        "status" to STATUS_ACTIVE,
        "experiments" to mutableListOf<Map<String, Any?>>(),
        "pending_proposal" to null,
        "rejected" to mutableListOf<Map<String, Any?>>(),
        "invalid_strikes" to 0,
        "terminal_reason" to null,
        "summary" to null
    )

    // ---- reads ----

    @Synchronized
    fun getParent(parentId: String): SessionRecord {
        val parent = manager.get(parentId)
        if (parent.mode != "agent") {
            throw AgentApi.AgentError("NOT_AGENT", "not an agent session")
        }
        return parent
    }

    @Synchronized
    fun childrenOf(parentId: String): List<SessionRecord> =
        manager.store.listIds().mapNotNull {
            try {
                manager.get(it)
            } catch (_: Exception) {
                null
            }
        }.filter { it.parentSessionId == parentId }
            .sortedBy { it.experimentSeq ?: Int.MAX_VALUE }

    @Synchronized
    fun nextSeq(parentId: String): Int =
        (childrenOf(parentId).mapNotNull { it.experimentSeq }.maxOrNull()
            ?: 0) + 1

    /**
     * EF-FOLLOWUP-02: child owning an attached run, for
     * repeat citation. Null when no child of this parent
     * carries the run id (never crosses parents).
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun childForRun(parentId: String, runId: String): SessionRecord? =
        childrenOf(parentId).firstOrNull {
            ((it.benchmark as? Map<String, Any?>)?.get("run_id") == runId)
        }

    /**
     * EF-FOLLOWUP-02: executed config of a cited run, read
     * from the stored benchmark (never from model text).
     * Null when the run is missing or its config unparsable.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun executedConfigMapForRun(
        parentId: String,
        runId: String
    ): Map<String, Any?>? {
        val child = childForRun(parentId, runId) ?: return null
        val bench = child.benchmark as? Map<String, Any?> ?: return null
        return bench["executed_config"] as? Map<String, Any?>
    }

    /** Latest experiment config, else the parent base config. */
    @Synchronized
    fun currentConfig(parentId: String): BenchConfigData {
        val parent = getParent(parentId)
        val latest = childrenOf(parentId).lastOrNull()
        val map = latest?.config ?: parent.config
        return SessionBenchBridge.configData(map)
            ?: throw AgentApi.AgentError("BAD_STATE", "stored config corrupt")
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun buildPack(parentId: String): AgentEvidence.EvidencePack {
        val parent = getParent(parentId)
        return AgentEvidence.buildPack(parent, childrenOf(parentId))
    }

    /**
     * EF-MEM-03 proposal mode. OPTIMIZE needs comparable
     * measurements; RECOVERY needs a typed failed attempt
     * instead — a failed child no longer dead-ends the
     * session. BLOCKED names the missing prerequisite.
     */
    enum class ProposalMode { OPTIMIZE, RECOVERY, BLOCKED }

    data class GateResult(
        val mode: ProposalMode,
        val ok: Boolean,
        val reason: String
    )

    /** Gate with mode: may a proposal be requested now, and which kind? */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun proposalGateEx(parentId: String): GateResult {
        val parent = getParent(parentId)
        val agent = parent.agent as? Map<String, Any?>
            ?: return GateResult(ProposalMode.BLOCKED, false,
                "agent state missing")
        if (agent["status"] != STATUS_ACTIVE) {
            return GateResult(ProposalMode.BLOCKED, false,
                "session finished")
        }
        if (agent["pending_proposal"] != null) {
            return GateResult(ProposalMode.BLOCKED, false,
                "a proposal is already pending review")
        }
        if ((agent["invalid_strikes"] as? Number)?.toInt() ?: 0 >=
            MAX_INVALID_STRIKES
        ) {
            return GateResult(ProposalMode.BLOCKED, false,
                "proposal stage failed")
        }
        if (parent.budgetUsed >= parent.budgetMax) {
            return GateResult(ProposalMode.BLOCKED, false,
                "experiment budget exhausted")
        }
        if (buildPack(parentId).comparable.isNotEmpty()) {
            return GateResult(ProposalMode.OPTIMIZE, true, "ok")
        }
        // No completed measurements: a typed failed attempt
        // still qualifies for a RECOVERY proposal. Anything
        // else stays blocked with an actionable reason.
        val target = recoveryTarget(parentId)
        if (target != null) {
            val runId = target.benchmark?.get("run_id") ?: "?"
            return GateResult(ProposalMode.RECOVERY, true,
                "recovery from failed attempt $runId")
        }
        return GateResult(ProposalMode.BLOCKED, false,
            "no comparable evidence yet — complete or attempt " +
                "an experiment first")
    }

    /** Gate with reason: may a proposal be requested now? */
    @Synchronized
    fun proposalGate(parentId: String): Pair<Boolean, String> {
        val gate = proposalGateEx(parentId)
        return gate.ok to gate.reason
    }

    /**
     * Latest failed child qualifying for recovery: an
     * uncompleted run with a valid run id, same
     * model/workload as the parent. Null when no failure
     * evidence exists (a completed-only history uses
     * OPTIMIZE; an empty history stays BLOCKED).
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun recoveryTarget(parentId: String): SessionRecord? {
        val parent = getParent(parentId)
        return childrenOf(parentId)
            .sortedByDescending { it.experimentSeq ?: Int.MIN_VALUE }
            .firstOrNull { child ->
                child.modelSha256 == parent.modelSha256 &&
                    child.workloadId == parent.workloadId &&
                    AgentEvidence.describeFailure(child) != null
            }
    }

    // ---- proposal intake ----

    /**
     * Stores a validated proposal as pending, or terminates on an
     * honest decline. A second consecutive invalid strike (recorded
     * via [recordInvalidProposal]) terminates the session.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun storeProposal(
        parentId: String,
        proposal: AgentApi.ValidProposal,
        packHash: String,
        requestedSeq: Int
    ): SessionRecord {
        val parent = getParent(parentId)
        val agent = (parent.agent as? Map<String, Any?>)?.toMutableMap()
            ?: throw AgentApi.AgentError("BAD_STATE", "agent state missing")
        requireActive(agent)
        if (!proposal.proposeExperiment) {
            return finalize(parent, agent, END_NO_FURTHER_TESTING,
                "model declined further testing: " +
                    (proposal.noExperimentReason ?: ""))
        }
        if (agent["pending_proposal"] != null) {
            throw AgentApi.AgentError("HAS_PENDING", "review pending first")
        }
        if (requestedSeq != nextSeq(parentId)) {
            throw AgentApi.AgentError("STALE_SEQ", "experiments moved on")
        }
        // EF-MEM-03 host verification: a recovery cites a
        // FAILED/TIMEOUT/CANCELLED run of THIS parent with
        // matching model/workload. Never a completed run
        // (that is repeat's job), never a foreign run.
        if (proposal.purpose == AgentApi.PURPOSE_RECOVERY) {
            val cited = proposal.recoveryOfRunId
                ?: throw AgentApi.AgentError(
                    "BAD_STATE", "recovery lacks cited run")
            val owner = childForRun(parentId, cited)
                ?: throw AgentApi.AgentError(
                    "BAD_STATE", "recovery run is foreign")
            if (AgentEvidence.describeFailure(owner) == null) {
                throw AgentApi.AgentError("BAD_STATE",
                    "recovery run is not a failed attempt")
            }
            if (owner.modelSha256 != parent.modelSha256 ||
                owner.workloadId != parent.workloadId
            ) {
                throw AgentApi.AgentError("BAD_STATE",
                    "recovery identity mismatch")
            }
        }
        val config = proposal.proposedConfig
            ?: throw AgentApi.AgentError("BAD_STATE", "proposal lacks config")
        // Host-controlled cohort labeling (model text is never
        // trusted for this): any n_predict change defines a
        // distinct configuration cohort that must not be pooled.
        val hostNotes = mutableListOf<String>()
        val currentPredict = try {
            currentConfig(parentId).nPredict
        } catch (_: Exception) {
            null
        }
        if (currentPredict != null && config.nPredict != currentPredict) {
            hostNotes.add("HOST: n_predict $currentPredict→${config.nPredict} " +
                "forms a distinct configuration cohort; results must not " +
                "be pooled with $currentPredict-token runs.")
        }
        agent["pending_proposal"] = linkedMapOf(
            "experiment_seq" to requestedSeq,
            "proposed_config" to SessionBenchBridge.configMap(config),
            "changed_keys" to proposal.changedKeys,
            "purpose" to proposal.purpose,
            "repeat_of_run_id" to proposal.repeatOfRunId,
            "recovery_of_run_id" to proposal.recoveryOfRunId,
            "host_notes" to hostNotes,
            "hypothesis" to proposal.hypothesis,
            "expected_direction" to proposal.expectedDirection,
            "expected_gen_tps" to proposal.expectedGenTps,
            "expected_prefill_tps" to proposal.expectedPrefillTps,
            "uncertainty" to proposal.uncertainty,
            "supporting_evidence_ids" to proposal.supportingEvidenceIds,
            "limitations" to proposal.limitations,
            "benefit" to proposal.benefit,
            "counterargument" to proposal.counterargument,
            "alternative_explanation" to proposal.alternativeExplanation,
            "next_benchmark_establishes" to proposal.nextBenchmarkEstablishes,
            "contract_version" to proposal.contractVersion,
            "proposal_hash" to sha256(proposal.rawJson),
            "evidence_pack_hash" to packHash,
            "requested_at_utc" to clock()
        )
        agent["invalid_strikes"] = 0
        return saveAgent(parent, agent, "proposal pending review")
    }

    /**
     * EF-AGENT-REVIEW-01 developer Modify: replaces the
     * pending proposed config with host-validated values.
     * Repeat configs are fixed to the cited run (reject and
     * re-request to change those). The model's expected
     * values are kept but flagged stale; approval then
     * executes the modified config like any pending one.
     * No budget charge: none was consumed to modify.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun updatePendingConfig(
        parentId: String,
        nCtx: Int,
        nThreads: Int,
        nPredict: Int,
        maxThreads: Int
    ): SessionRecord {
        val parent = getParent(parentId)
        val agent = (parent.agent as? Map<String, Any?>)?.toMutableMap()
            ?: throw AgentApi.AgentError("BAD_STATE", "agent state missing")
        requireActive(agent)
        val pending = (agent["pending_proposal"] as? Map<String, Any?>)
            ?.toMutableMap()
            ?: throw AgentApi.AgentError("NO_PENDING", "no proposal pending")
        if ((pending["purpose"] as? String) == AgentApi.PURPOSE_REPEAT) {
            throw AgentApi.AgentError("REPEAT_FIXED",
                "repeat config is fixed to the cited run; reject " +
                    "and re-request to change it")
        }
        val stored = pending["proposed_config"] as? Map<String, Any?>
            ?: throw AgentApi.AgentError("BAD_STATE", "pending lacks config")
        val merged = stored.toMutableMap()
        merged["n_ctx"] = nCtx
        merged["n_threads"] = nThreads
        merged["n_predict"] = nPredict
        val target = SessionBenchBridge.configData(merged)
            ?: throw AgentApi.AgentError("BAD_CONFIG", "modified config bad")
        val errors = BenchConfig.validate(target).toMutableList()
        if (target.nThreads > maxThreads) {
            errors.add("n_threads $nThreads exceeds device max $maxThreads")
        }
        if (errors.isNotEmpty()) {
            throw AgentApi.AgentError("BAD_CONFIG", errors.joinToString("; "))
        }
        val current = currentConfig(parentId)
        val changed = mutableListOf<String>()
        if (target.nCtx != current.nCtx) changed.add("n_ctx")
        if (target.nThreads != current.nThreads) changed.add("n_threads")
        if (target.nPredict != current.nPredict) changed.add("n_predict")
        if (changed.isEmpty() &&
            (pending["purpose"] as? String) == AgentApi.PURPOSE_OPTIMIZE
        ) {
            throw AgentApi.AgentError("NO_CHANGE",
                "modification equals the current config; nothing to test")
        }
        val notes = ((pending["host_notes"] as? List<*>)?.mapNotNull {
            it as? String
        } ?: emptyList()).toMutableList()
        notes.add("HOST: developer-modified to ctx=$nCtx threads=$nThreads " +
            "predict=$nPredict; model expected values predate the change.")
        if (target.nPredict != current.nPredict) {
            notes.add("HOST: n_predict ${current.nPredict}→" +
                "${target.nPredict} forms a distinct configuration cohort; " +
                "results must not be pooled with " +
                "${current.nPredict}-token runs.")
        }
        pending["proposed_config"] = SessionBenchBridge.configMap(target)
        pending["changed_keys"] = changed.sorted()
        pending["modified_by"] = "developer"
        pending["host_notes"] = notes
        pending["proposal_hash"] = sha256(
            (pending["proposal_hash"] as? String).orEmpty() +
                SessionJson.stringify(
                    SessionBenchBridge.configMap(target)))
        agent["pending_proposal"] = pending
        return saveAgent(parent, agent, "proposal modified by developer")
    }

    /** Records one schema-invalid reply; the second strike terminates. */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun recordInvalidProposal(parentId: String, reason: String): SessionRecord {
        val parent = getParent(parentId)
        val agent = (parent.agent as? Map<String, Any?>)?.toMutableMap()
            ?: throw AgentApi.AgentError("BAD_STATE", "agent state missing")
        requireActive(agent)
        val strikes = ((agent["invalid_strikes"] as? Number)?.toInt() ?: 0) + 1
        agent["invalid_strikes"] = strikes
        appendRejected(agent, null, "invalid: $reason")
        if (strikes >= MAX_INVALID_STRIKES) {
            return finalize(parent, agent, END_INVALID_PROPOSAL,
                "two consecutive invalid proposals")
        }
        return saveAgent(parent, agent, "invalid proposal (strike $strikes)")
    }

    // ---- approval ----

    /**
     * Atomically links an AUTHORIZED child to the pending proposal and
     * charges one budget attempt. The child must already exist with
     * the exact proposed config; nothing here invents it. Second
     * calls fail: the pending slot is consumed exactly once.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun approveProposal(parentId: String, childId: String): SessionRecord {
        val parent = getParent(parentId)
        val agent = (parent.agent as? Map<String, Any?>)?.toMutableMap()
            ?: throw AgentApi.AgentError("BAD_STATE", "agent state missing")
        requireActive(agent)
        val pending = agent["pending_proposal"] as? Map<String, Any?>
            ?: throw AgentApi.AgentError("NO_PENDING", "no proposal pending")
        if (parent.budgetUsed >= parent.budgetMax) {
            throw AgentApi.AgentError("BUDGET_OUT", "budget exhausted")
        }
        val child = manager.get(childId)
        if (child.parentSessionId != parentId) {
            throw AgentApi.AgentError("NOT_CHILD", "child belongs elsewhere")
        }
        if (child.state != SessionState.BENCHMARK_AUTHORIZED) {
            throw AgentApi.AgentError(
                "CHILD_NOT_READY", "child must be AUTHORIZED")
        }
        val pendingSeq = (pending["experiment_seq"] as? Number)?.toInt()
        if (child.experimentSeq != pendingSeq) {
            throw AgentApi.AgentError("SEQ_MISMATCH", "stale approval")
        }
        if (!configEquals(child.config,
                pending["proposed_config"] as? Map<String, Any?>)
        ) {
            throw AgentApi.AgentError(
                "CONFIG_MISMATCH", "child config differs from proposal")
        }
        // EF-FOLLOWUP-02 repeat verification: the child must
        // carry the cited run's prediction, and the cited
        // run's STORED executed config must equal the child
        // config (pending text alone is not trusted).
        if ((pending["purpose"] as? String) == AgentApi.PURPOSE_REPEAT) {
            val cited = pending["repeat_of_run_id"] as? String
                ?: throw AgentApi.AgentError(
                    "REPEAT_NO_CITE", "repeat cites nothing")
            if (child.repeatOfRunId != cited) {
                throw AgentApi.AgentError(
                    "REPEAT_LINK", "child does not carry cited run")
            }
            val executed =
                executedConfigMapForRun(parentId, cited)
                    ?: throw AgentApi.AgentError(
                        "REPEAT_NO_CITE", "cited run not found")
            if (!configEquals(child.config, executed)) {
                throw AgentApi.AgentError(
                    "REPEAT_CONFIG", "repeat differs from cited run")
            }
        }
        @Suppress("UNCHECKED_CAST")
        val experiments = ((agent["experiments"] as? List<*>)
            ?.mapNotNull { it as? Map<String, Any?> } ?: emptyList())
            .toMutableList()
        experiments.add(linkedMapOf(
            "seq" to pendingSeq,
            "child_id" to childId,
            "proposal_hash" to pending["proposal_hash"],
            "evidence_pack_hash" to pending["evidence_pack_hash"],
            "approved_config" to pending["proposed_config"],
            "approved_at_utc" to clock()
        ))
        agent["experiments"] = experiments
        agent["pending_proposal"] = null
        val updated = parent.copy(
            agent = agent,
            budgetUsed = parent.budgetUsed + 1,
            updatedAtUtc = clock()
        )
        return manager.store.save(pushHistory(updated,
            "experiment $pendingSeq approved (attempt ${updated.budgetUsed})"))
    }

    /** Rejection preserves the proposal as rejected, never executed. */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun rejectProposal(parentId: String, reason: String): SessionRecord {
        val parent = getParent(parentId)
        val agent = (parent.agent as? Map<String, Any?>)?.toMutableMap()
            ?: throw AgentApi.AgentError("BAD_STATE", "agent state missing")
        requireActive(agent)
        val pending = agent["pending_proposal"] as? Map<String, Any?>
            ?: throw AgentApi.AgentError("NO_PENDING", "no proposal pending")
        appendRejected(agent, pending["proposal_hash"] as? String,
            reason.ifBlank { "rejected by developer" })
        agent["pending_proposal"] = null
        return saveAgent(parent, agent, "proposal rejected")
    }

    // ---- post-experiment + termination ----

    /**
     * Called after a child attaches. Trails + charges experiments
     * that ran outside the approval path (experiment 1 runs on a
     * live prediction, not a proposal), then auto-finishes on
     * budget exhaustion or the governed stagnation rule. Returns
     * the terminal reason, or null when the session stays active.
     * Idempotent: re-entry appends nothing twice.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun afterExperiment(parentId: String): String? {
        var parent = getParent(parentId)
        var agent = parent.agent as? Map<String, Any?>
        if (agent == null || agent["status"] != STATUS_ACTIVE) return null
        val trailed = ((agent["experiments"] as? List<*>)
            ?.mapNotNull { (it as? Map<String, Any?>)?.get("child_id") }
            ?: emptyList<Any?>()).toSet()
        val untrailed = childrenOf(parentId).filter {
            it.benchmark != null && it.sessionId !in trailed
        }
        if (untrailed.isNotEmpty()) {
            val mutable = agent.toMutableMap()
            val experiments = ((mutable["experiments"] as? List<*>)
                ?.mapNotNull { it as? Map<String, Any?> } ?: emptyList())
                .toMutableList()
            for (child in untrailed) {
                experiments.add(linkedMapOf(
                    "seq" to child.experimentSeq,
                    "child_id" to child.sessionId,
                    "proposal_hash" to null,
                    "evidence_pack_hash" to null,
                    "approved_config" to child.config,
                    "approved_at_utc" to clock()
                ))
            }
            mutable["experiments"] = experiments
            parent = manager.store.save(pushHistory(
                parent.copy(
                    agent = mutable,
                    budgetUsed = parent.budgetUsed + untrailed.size,
                    updatedAtUtc = clock()
                ),
                "trailed ${untrailed.size} completed experiment(s)"
            ))
            agent = mutable
        }
        if (parent.budgetUsed >= parent.budgetMax) {
            finalize(parent, agent.toMutableMap(), END_BUDGET_EXHAUSTED,
                "budget exhausted (${parent.budgetUsed}/${parent.budgetMax})")
            return END_BUDGET_EXHAUSTED
        }
        val gens = buildPack(parentId).comparable.map { it.genTps }
        if (stagnationStreak(gens)) {
            finalize(parent, agent.toMutableMap(), END_STAGNATION,
                "three consecutive non-improving comparable runs")
            return END_STAGNATION
        }
        return null
    }

    @Synchronized
    fun finishAgent(parentId: String): SessionRecord {
        val parent = getParent(parentId)
        @Suppress("UNCHECKED_CAST")
        val agent = (parent.agent as? Map<String, Any?>)?.toMutableMap()
            ?: throw AgentApi.AgentError("BAD_STATE", "agent state missing")
        requireActive(agent)
        return finalize(parent, agent, END_USER_FINISH, "finished by developer")
    }

    /**
     * Crash-window repair: an AUTHORIZED linked child created for a
     * still-pending proposal (approve died between child-authorize
     * and approval) is linked and charged exactly once. Anything
     * else is left for explicit user action. Returns actions taken.
     */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun reconcile(parentId: String): List<String> {
        val actions = mutableListOf<String>()
        val parent = try {
            getParent(parentId)
        } catch (_: Exception) {
            return actions
        }
        val agent = parent.agent as? Map<String, Any?> ?: return actions
        if (agent["status"] == STATUS_FINISHED &&
            parent.state != SessionState.SESSION_FINISHED
        ) {
            // Crash between trail-finalize and the state transition.
            try {
                manager.finishSession(parent.sessionId,
                    if (agent["terminal_reason"] == END_INVALID_PROPOSAL) {
                        "failed"
                    } else {
                        "completed"
                    },
                    "reconciled unfinished finish")
                actions.add("$parentId: finish transition completed")
            } catch (e: Exception) {
                actions.add("$parentId: finish stuck (${e.message})")
            }
            return actions
        }
        if (agent["status"] != STATUS_ACTIVE) return actions
        val pending = agent["pending_proposal"] as? Map<String, Any?>
            ?: return actions
        val pendingSeq = (pending["experiment_seq"] as? Number)?.toInt()
        val orphan = childrenOf(parentId).firstOrNull {
            it.experimentSeq == pendingSeq &&
                it.state == SessionState.BENCHMARK_AUTHORIZED
        } ?: return actions
        try {
            approveProposal(parentId, orphan.sessionId)
            actions.add("$parentId: orphan experiment $pendingSeq linked " +
                "after interruption (budget charged once)")
        } catch (e: Exception) {
            actions.add("$parentId: orphan kept for review (${e.message})")
        }
        return actions
    }

    /** Terminal summary over live children + pack. Stored at finish. */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun buildSummary(parentId: String): Map<String, Any?> {
        val parent = getParent(parentId)
        val children = childrenOf(parentId)
        val pack = AgentEvidence.buildPack(parent, children)
        var completed = 0
        var failed = 0
        var cancelled = 0
        for (child in children) {
            val bench = child.benchmark as? Map<String, Any?>
            when (bench?.get("status") as? String) {
                BenchContract.STATUS_COMPLETED -> completed++
                BenchContract.STATUS_CANCELLED -> cancelled++
                BenchContract.STATUS_FAILED, BenchContract.STATUS_TIMEOUT ->
                    failed++
            }
        }
        val best = pack.comparable.maxByOrNull { it.genTps }
        return linkedMapOf(
            "agent_version" to AgentApi.AGENT_VERSION,
            "attempts_used" to parent.budgetUsed,
            "attempts_max" to parent.budgetMax,
            "experiments" to children.size,
            "completed" to completed,
            "failed" to failed,
            "cancelled" to cancelled,
            "comparable_runs" to pack.comparable.size,
            "best_run_id" to best?.runId,
            "best_gen_tps" to best?.genTps,
            "best_config" to best?.let {
                "${it.nCtx}/${it.nThreads}/${it.nPredict}"
            },
            "pack_hash" to pack.packHash,
            "child_ids" to children.map { it.sessionId }
        )
    }

    // ---- internals ----

    private fun requireActive(agent: Map<String, Any?>) {
        if (agent["status"] != STATUS_ACTIVE) {
            throw AgentApi.AgentError("FINISHED", "agent session finished")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendRejected(
        agent: MutableMap<String, Any?>,
        hash: String?,
        reason: String
    ) {
        val rejected = ((agent["rejected"] as? List<*>)
            ?.mapNotNull { it as? Map<String, Any?> } ?: emptyList())
            .toMutableList()
        rejected.add(linkedMapOf(
            "proposal_hash" to hash,
            "reason" to reason,
            "at_utc" to clock()
        ))
        agent["rejected"] = rejected.takeLast(MAX_REJECTED)
    }

    private fun saveAgent(
        parent: SessionRecord,
        agent: Map<String, Any?>,
        cause: String
    ): SessionRecord {
        return manager.store.save(pushHistory(
            parent.copy(agent = agent, updatedAtUtc = clock()), cause))
    }

    private fun pushHistory(record: SessionRecord, cause: String): SessionRecord {
        val entry = mapOf(
            "state" to record.state, "at" to clock(), "cause" to cause)
        return record.copy(
            history = (record.history + entry).takeLast(SessionStore.MAX_HISTORY))
    }

    @Suppress("UNCHECKED_CAST")
    private fun finalize(
        parent: SessionRecord,
        agent: MutableMap<String, Any?>,
        reason: String,
        cause: String
    ): SessionRecord {
        agent["status"] = STATUS_FINISHED
        agent["terminal_reason"] = reason
        agent["pending_proposal"] = null
        val withAgent = manager.store.save(pushHistory(
            parent.copy(agent = agent, updatedAtUtc = clock()),
            "terminated: $reason"))
        // Summary over the finished trail, then the state transition.
        val summary = buildSummary(withAgent.sessionId) +
            mapOf("terminal_reason" to reason)
        manager.store.save(withAgent.copy(
            agent = (withAgent.agent as Map<String, Any?>) + mapOf(
                "summary" to summary),
            updatedAtUtc = clock()
        ))
        return manager.finishSession(
            withAgent.sessionId,
            if (reason == END_INVALID_PROPOSAL) "failed" else "completed",
            cause
        )
    }

    private fun configEquals(
        stored: Map<String, Any?>,
        proposed: Map<String, Any?>?
    ): Boolean {
        if (proposed == null) return false
        for (key in listOf("n_ctx", "n_threads", "n_predict", "seed",
            "timeout_ms", "temperature", "sampler")) {
            val a = stored[key]
            val b = proposed[key]
            val equal = if (a is Number && b is Number) {
                a.toDouble() == b.toDouble()
            } else {
                a == b
            }
            if (!equal) return false
        }
        return true
    }

    private fun sha256(text: String): String =
        BenchConfig.sha256Hex(text.toByteArray(Charsets.UTF_8))
}
