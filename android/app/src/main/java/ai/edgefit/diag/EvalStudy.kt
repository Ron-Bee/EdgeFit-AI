package ai.edgefit.diag

/**
 * EF-COMP-01B-PREP: offline study-preparation bridge. Turns
 * the R/M0/M1 evaluation contracts + Stage B protocol into
 * a reproducible, owner-executable experiment WITHOUT
 * touching production Agent authority.
 *
 * Study/production separation (Task 1):
 * - Production [AgentApi.validateProposal] requires an
 *   actual config change for purpose=optimize, and
 *   [AgentManager.proposalGateEx] requires qualifying
 *   session evidence. Both are CORRECT for production and
 *   are NOT weakened here.
 * - A study decision is a DIFFERENT type
 *   ([StudyDecision], never [AgentApi.ValidProposal]).
 *   There is intentionally NO conversion from a study
 *   decision to a proposal: a study decision cannot
 *   authorize a benchmark, consume Agent budget, or enter
 *   any session state machine. It only records which
 *   frozen candidate an arm selected (or that it
 *   abstained) for later scoring against physical runs.
 * - Candidate identity validates against the FROZEN
 *   host-approved A/B set (exact config match +
 *   [BenchConfig.validate] + device thread max), so the
 *   study reuses the production source of truth for
 *   bounds without reusing production's change-gate.
 *
 * API orchestration reuse (Task 3): request bodies reuse
 * [MetaApi.requestBody] (the qualified no-tools wire
 * shape) and replies parse via [MetaApi.extractText] /
 * [MetaApi.extractJsonObject] / [MetaApi.rawSlice].
 * [MetaSessionClient.requestProposal] is NOT reused: it
 * is session-bound (parent id, key vault, single-flight
 * guards) and a study deliberately has no session. A
 * future live step can wrap the existing
 * [HttpsMetaTransport] with an owner-supplied key; no
 * new transport code is needed.
 *
 * Pure JVM except for file reads performed by the
 * runner edge ([EvalStudyRunnerTest]); every function
 * below takes bytes/strings and returns data.
 */
object EvalStudy {

    const val STUDY_ID = "ef-comp-01b"
    const val STUDY_MANIFEST_VERSION = 1

    /**
     * Frozen study identities, transcribed from
     * docs/ef-comp-01b-stage-b.md §1. The freeze
     * re-verifies model/workload/prompt/runtime/ABI/
     * variant per artifact; size/quantization ride the
     * manifested evidence verbatim.
     */
    const val STUDY_MODEL_SHA256 =
        "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
    const val STUDY_MODEL_SIZE_BYTES = 807694464L
    const val STUDY_QUANTIZATION = "2"
    const val STUDY_PROMPT_SHA256 =
        "36104db0657a294048410f9043d4efc8510a77088ca1e2f17c0b6c8359c44382"
    const val STUDY_RUNTIME_VERSION = "0.7.0-dev"
    const val STUDY_RUNTIME_ABI = "arm64-v8a"
    const val STUDY_NATIVE_VARIANT = "benchmark"
    const val STUDY_DEVICE_MAX_THREADS = 8
    /** Protocol §3 authorized historical set (run-a excluded: Debug). */
    val STUDY_AUTHORIZED_IDS = listOf(
        "run-b-benchmark", "run-c-benchmark", "run-d-benchmark")

    fun studyConstraints(
        memAvailPct: Double? = null,
        memLowMemory: Boolean? = null
    ) = EvalArms.ArmConstraints(
        modelSha256 = STUDY_MODEL_SHA256,
        modelSizeBytes = STUDY_MODEL_SIZE_BYTES,
        quantization = STUDY_QUANTIZATION,
        workloadId = BenchContract.WORKLOAD_ID,
        promptSha256 = STUDY_PROMPT_SHA256,
        runtimeVersion = STUDY_RUNTIME_VERSION,
        runtimeAbi = STUDY_RUNTIME_ABI,
        nativeVariant = STUDY_NATIVE_VARIANT,
        deviceMaxThreads = STUDY_DEVICE_MAX_THREADS,
        memAvailPct = memAvailPct,
        memLowMemory = memLowMemory
    )

    fun studyCandidateA(): BenchConfigData =
        BenchConfig.default().copy(nThreads = 4)

    fun studyCandidateB(): BenchConfigData =
        BenchConfig.default().copy(nThreads = 8)

    const val SELECT_A = "A"
    const val SELECT_B = "B"
    const val SELECT_ABSTAIN = "abstain"
    val SELECTIONS = setOf(SELECT_A, SELECT_B, SELECT_ABSTAIN)

    /** StudyDecision.transport values. */
    const val TRANSPORT_OFFLINE = "offline"
    const val TRANSPORT_MOCK = "mock-synthetic"
    const val TRANSPORT_LIVE = "live"

    const val VERDICT_READY = "READY"
    const val VERDICT_BLOCKED = "BLOCKED"

    class StudyError(val code: String, message: String) : Exception(message)

    // ---- Task 1: study decision contract ----

    /**
     * Frozen expectations for one study reply. M0's
     * [allowedRunIds] is EMPTY (constraints only: any
     * citation refuses); M1's holds the manifested
     * admitted run ids.
     */
    data class StudyExpectation(
        val candidateA: BenchConfigData,
        val candidateB: BenchConfigData,
        val modelSha256: String,
        val workloadId: String,
        val allowedRunIds: Set<String>,
        val deviceMaxThreads: Int
    )

    /**
     * One validated arm selection. Deliberately NOT an
     * [AgentApi.ValidProposal]: it carries no seq, no
     * budget, no session linkage, and no path exists from
     * this type into [AgentManager] or [SessionManager].
     */
    data class StudyDecision(
        val arm: String,
        /** A, B, or abstain. */
        val selection: String,
        /** Exact frozen config for A/B; null on abstain. */
        val selectedConfig: BenchConfigData?,
        val abstainReason: String?,
        val expectedGenTps: Double?,
        val expectedPrefillTps: Double?,
        val uncertainty: String?,
        val supportingEvidenceIds: List<String>,
        val limitations: List<String>,
        /** R ranking factors / host notes; model never writes these. */
        val notes: List<String> = emptyList(),
        val rawJson: String,
        val decidedAtUtc: String,
        /** offline | mock-synthetic | live. */
        val transport: String,
        val providerRequestId: String? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val latencyMs: Long? = null,
        /** USD when the provider reports priced usage. */
        val costUsd: Double? = null
    )

    /**
     * Study reply schema (what the model is asked to
     * return; see [studySystemM0]/[studySystemM1]):
     * model_sha, workload_id, selection (A|B|abstain),
     * abstain_reason?, expected_gen_tps?,
     * expected_prefill_tps?, uncertainty?,
     * supporting_evidence_ids[], limitations[].
     * Selecting A (the current baseline config) is LEGAL:
     * no change is required, unlike production optimize.
     */
    @Suppress("UNCHECKED_CAST")
    fun validateStudyDecision(
        payload: Map<String, Any?>,
        rawJson: String,
        expected: StudyExpectation,
        arm: String,
        decidedAtUtc: String,
        transport: String
    ): StudyDecision {
        if (rawJson.toByteArray(Charsets.UTF_8).size >
            AgentApi.MAX_PROPOSAL_RAW_BYTES
        ) {
            throw StudyError("STUDY_REPLY_TOO_LARGE",
                "reply exceeds bound")
        }
        if ((payload["model_sha"] as? String)?.lowercase() !=
            expected.modelSha256.lowercase()
        ) {
            throw StudyError("STUDY_IDENTITY",
                "model identity mismatch")
        }
        if ((payload["workload_id"] as? String) != expected.workloadId) {
            throw StudyError("STUDY_IDENTITY",
                "workload identity mismatch")
        }
        val selection = payload["selection"] as? String
        if (selection !in SELECTIONS) {
            throw StudyError("STUDY_SELECTION",
                "selection must be one of $SELECTIONS")
        }
        // Frozen-set identity: the selection resolves to
        // the EXACT frozen config (host bounds re-checked
        // through the production source of truth).
        val selected: BenchConfigData? = when (selection) {
            SELECT_A -> expected.candidateA
            SELECT_B -> expected.candidateB
            else -> null
        }
        if (selected != null) {
            val errors = BenchConfig.validate(selected).toMutableList()
            if (selected.nThreads > expected.deviceMaxThreads) {
                errors.add("n_threads exceeds device max")
            }
            if (errors.isNotEmpty()) {
                throw StudyError("STUDY_CANDIDATE",
                    "frozen candidate fails host validation: " +
                        errors.joinToString("; "))
            }
        }
        val abstainReason = payload["abstain_reason"] as? String
        if (selection == SELECT_ABSTAIN) {
            if (abstainReason.isNullOrBlank() ||
                abstainReason.length > MetaApi.MAX_NOTE_FIELD
            ) {
                throw StudyError("STUDY_ABSTAIN",
                    "abstain_reason must be 1..500 chars")
            }
            // An abstention carries no estimates: estimates
            // alongside abstain are contradictory.
            if (payload["expected_gen_tps"] != null ||
                payload["expected_prefill_tps"] != null
            ) {
                throw StudyError("STUDY_ABSTAIN",
                    "abstention must not carry estimates")
            }
        } else if (!abstainReason.isNullOrBlank()) {
            throw StudyError("STUDY_ABSTAIN",
                "abstain_reason only with abstain")
        }
        val cited = payload["supporting_evidence_ids"] as? List<*>
            ?: throw StudyError("STUDY_CITATIONS",
                "supporting_evidence_ids must be an array")
        if (cited.size > MetaApi.MAX_LIST_ITEMS) {
            throw StudyError("STUDY_CITATIONS", "too many citations")
        }
        val ids = cited.map {
            val id = it as? String
                ?: throw StudyError("STUDY_CITATIONS",
                    "citations hold strings")
            if (id !in expected.allowedRunIds) {
                throw StudyError("STUDY_CITATIONS",
                    "citation $id is not manifested evidence")
            }
            id
        }
        return StudyDecision(
            arm = arm,
            selection = selection!!,
            selectedConfig = selected,
            abstainReason = abstainReason
                ?.takeIf { selection == SELECT_ABSTAIN },
            expectedGenTps = studyThroughput(
                payload, "expected_gen_tps"),
            expectedPrefillTps = studyThroughput(
                payload, "expected_prefill_tps"),
            uncertainty = (payload["uncertainty"] as? String)
                ?.also {
                    if (it.length > MetaApi.MAX_NOTE_FIELD) {
                        throw StudyError("STUDY_NOTE",
                            "uncertainty too long")
                    }
                }?.ifBlank { null },
            supportingEvidenceIds = ids,
            limitations = studyNoteList(payload, "limitations"),
            rawJson = rawJson,
            decidedAtUtc = decidedAtUtc,
            transport = transport
        )
    }

    private fun studyThroughput(
        payload: Map<String, Any?>,
        key: String
    ): Double? {
        if (!payload.containsKey(key) || payload[key] == null) return null
        val value = (payload[key] as? Number)?.toDouble()
            ?: throw StudyError("STUDY_ESTIMATE",
                "$key must be a number or null")
        if (!value.isFinite() || value <= 0 ||
            value > MetaApi.MAX_PREDICTED_TPS
        ) {
            throw StudyError("STUDY_ESTIMATE", "$key out of bounds")
        }
        return value
    }

    private fun studyNoteList(
        payload: Map<String, Any?>,
        key: String
    ): List<String> {
        val value = payload[key] as? List<*>
            ?: throw StudyError("STUDY_NOTE", "$key must be an array")
        if (value.size > MetaApi.MAX_LIST_ITEMS) {
            throw StudyError("STUDY_NOTE", "$key too long")
        }
        return value.map {
            val item = it as? String
                ?: throw StudyError("STUDY_NOTE",
                    "$key holds strings")
            if (item.length > MetaApi.MAX_NOTE_FIELD) {
                throw StudyError("STUDY_NOTE", "$key item too long")
            }
            item
        }
    }

    // ---- Task 2: real evidence freeze ----

    /**
     * One offered historical artifact: the EXPECTED hash
     * (from the corpus manifest) plus the ACTUAL bytes.
     * The freeze hashes the bytes itself and transcribes
     * the proof from the parsed content — a
     * caller-supplied MeasurementProof is NEVER accepted
     * as proof of the artifact's content.
     */
    data class ArtifactInput(
        val corpusId: String,
        val expectedSha256: String,
        val bytes: ByteArray
    )

    data class FrozenEvidence(
        val corpusId: String,
        val runId: String,
        val artifactSha256: String,
        val config: BenchConfigData,
        val genTps: Double,
        val prefillTps: Double,
        val thermalAfter: String,
        val collectedAtUtc: String,
        val modelSizeBytes: Long,
        val quantization: String,
        /** Requested flags, verbatim; effective status noted separately. */
        val nativeOpt: String
    )

    data class StudyManifest(
        val studyId: String,
        val manifestVersion: Int,
        val frozenAtUtc: String,
        val frozenAtNote: String,
        val evidenceCutoffUtc: String,
        val constraints: EvalArms.ArmConstraints,
        val candidateA: BenchConfigData,
        val candidateB: BenchConfigData,
        val candidateHash: String,
        val admitted: List<FrozenEvidence>,
        /** Explicit M0 withholding record (design, not accident). */
        val m0WithheldRunIds: List<String>,
        val m0PackHash: String,
        val m1PackHash: String,
        val m1Exclusions: List<String>,
        /** SHA-256 over the canonical manifest bytes. */
        val manifestHash: String
    )

    /**
     * Freezes the study: verifies every offered artifact
     * byte-for-byte against its expected hash, parses and
     * identity-checks the content, and admits ONLY
     * qualified COMPLETED records collected strictly
     * before [evidenceCutoffUtc] (one identical cutoff
     * for all arms). ANY rejection — duplicate run id or
     * artifact hash, corrupt JSON, wrong contract,
     * identity mismatch, unrecorded native variant,
     * non-COMPLETED status, missing/future timestamp,
     * unusable throughput — fails the WHOLE freeze with
     * every reason listed. Fail closed: the study offers
     * exactly its authorized set, nothing is silently
     * dropped.
     */
    @Suppress("UNCHECKED_CAST")
    fun freezeStudy(
        constraints: EvalArms.ArmConstraints,
        candidateA: BenchConfigData,
        candidateB: BenchConfigData,
        artifacts: List<ArtifactInput>,
        evidenceCutoffUtc: String,
        frozenAtUtc: String
    ): StudyManifest {
        for ((label, candidate) in listOf("A" to candidateA,
            "B" to candidateB)
        ) {
            val errors = BenchConfig.validate(candidate).toMutableList()
            if (candidate.nThreads > constraints.deviceMaxThreads) {
                errors.add("n_threads exceeds device max")
            }
            if (errors.isNotEmpty()) {
                throw StudyError("STUDY_CANDIDATE",
                    "candidate $label fails host validation: " +
                        errors.joinToString("; "))
            }
        }
        if (tripleOf(candidateA) == tripleOf(candidateB)) {
            throw StudyError("STUDY_CANDIDATE",
                "candidates A and B must differ")
        }
        if (artifacts.isEmpty()) {
            throw StudyError("STUDY_FREEZE", "no artifacts offered")
        }
        val problems = mutableListOf<String>()
        val frozen = mutableListOf<FrozenEvidence>()
        for (artifact in artifacts) {
            try {
                frozen.add(verifyArtifact(artifact, constraints,
                    evidenceCutoffUtc))
            } catch (e: StudyError) {
                problems.add("${artifact.corpusId}: [${e.code}] ${e.message}")
            }
        }
        // Duplicates are checked across the verified set:
        // the same run or the same bytes twice is a
        // corrupted offer, not two observations.
        val runIds = frozen.map { it.runId }
        for (id in runIds.groupingBy { it }.eachCount()
            .filter { it.value > 1 }.keys
        ) {
            problems.add("duplicate run id $id")
        }
        val shas = frozen.map { it.artifactSha256.lowercase() }
        for (sha in shas.groupingBy { it }.eachCount()
            .filter { it.value > 1 }.keys
        ) {
            problems.add("duplicate artifact bytes sha $sha")
        }
        if (problems.isNotEmpty()) {
            throw StudyError("STUDY_FREEZE",
                problems.joinToString(" | "))
        }
        val admitted = frozen.sortedBy { it.runId }
        val offered = admitted.map { e ->
            EvalArms.ArmMeasurement(
                runId = e.runId,
                config = e.config,
                genTps = e.genTps,
                prefillTps = e.prefillTps,
                thermalAfter = e.thermalAfter,
                // Gate outputs; values ignored + recomputed.
                identityMatched = false,
                comparable = false
            ) to EvalArms.MeasurementProof(
                artifactSha256 = e.artifactSha256,
                runId = e.runId,
                modelSha256 = constraints.modelSha256,
                workloadId = constraints.workloadId,
                promptSha256 = constraints.promptSha256,
                runtimeVersion = constraints.runtimeVersion,
                runtimeAbi = constraints.runtimeAbi,
                nativeVariant = constraints.nativeVariant,
                status = BenchContract.STATUS_COMPLETED,
                collectedAtUtc = e.collectedAtUtc,
                nCtx = e.config.nCtx,
                nThreads = e.config.nThreads,
                nPredict = e.config.nPredict
            )
        }
        val m1 = EvalArms.m1Pack(constraints,
            listOf(candidateA, candidateB), offered)
        if (m1.measurements!!.size != admitted.size) {
            // Cannot happen (proofs transcribe verified
            // content), but a second gate must never
            // silently narrow the manifested set.
            throw StudyError("STUDY_FREEZE",
                "M1 gate narrowed the verified set: " +
                    m1.exclusions.joinToString("; "))
        }
        val m0 = EvalArms.m0Pack(constraints,
            listOf(candidateA, candidateB),
            withheldCount = admitted.size)
        EvalArms.requireSharedCandidates(m0, m1)
        val manifest = StudyManifest(
            studyId = STUDY_ID,
            manifestVersion = STUDY_MANIFEST_VERSION,
            frozenAtUtc = frozenAtUtc,
            frozenAtNote = "local workstation clock at freeze; " +
                "not tamper-proof. Ordering is enforced by " +
                "comparing these stamps against run START " +
                "stamps, not by trusting this clock alone.",
            evidenceCutoffUtc = evidenceCutoffUtc,
            constraints = constraints,
            candidateA = candidateA,
            candidateB = candidateB,
            candidateHash = m1.candidateHash,
            admitted = admitted,
            m0WithheldRunIds = admitted.map { it.runId },
            m0PackHash = m0.packHash,
            m1PackHash = m1.packHash,
            m1Exclusions = m1.exclusions,
            manifestHash = ""
        )
        return manifest.copy(manifestHash = hashManifest(manifest))
    }

    private fun verifyArtifact(
        artifact: ArtifactInput,
        constraints: EvalArms.ArmConstraints,
        evidenceCutoffUtc: String
    ): FrozenEvidence {
        val actualSha = BenchConfig.sha256Hex(artifact.bytes)
        if (!actualSha.equals(artifact.expectedSha256, ignoreCase = true)) {
            throw StudyError("STUDY_HASH",
                "bytes do not match corpus hash (changed or misrecorded)")
        }
        val doc = try {
            SessionJson.parse(artifact.bytes.toString(Charsets.UTF_8))
        } catch (_: SessionJson.JsonError) {
            throw StudyError("STUDY_CORRUPT", "artifact is not valid JSON")
        }
        if (doc !is Map<*, *>) {
            throw StudyError("STUDY_CORRUPT", "artifact is not an object")
        }
        @Suppress("UNCHECKED_CAST")
        val root = doc as Map<String, Any?>
        if (root["contract"] != BenchContract.CONTRACT) {
            throw StudyError("STUDY_CONTRACT",
                "contract is not ${BenchContract.CONTRACT}")
        }
        if ((root["contract_version"] as? Number)?.toInt() !=
            BenchContract.CONTRACT_VERSION
        ) {
            throw StudyError("STUDY_CONTRACT",
                "unsupported contract version")
        }
        val runId = root["run_id"] as? String
        if (runId.isNullOrBlank()) {
            throw StudyError("STUDY_IDENTITY", "run id missing")
        }
        if (root["status"] != BenchContract.STATUS_COMPLETED) {
            throw StudyError("STUDY_STATUS",
                "status is ${root["status"]}, not COMPLETED")
        }
        val model = root["model"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_IDENTITY", "model block missing")
        if ((model["sha256"] as? String)?.lowercase() !=
            constraints.modelSha256.lowercase()
        ) {
            throw StudyError("STUDY_IDENTITY", "model SHA-256 mismatch")
        }
        val workload = root["workload"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_IDENTITY", "workload block missing")
        if (workload["id"] != constraints.workloadId) {
            throw StudyError("STUDY_IDENTITY", "workload mismatch")
        }
        if ((workload["prompt_sha256"] as? String)?.lowercase() !=
            constraints.promptSha256.lowercase()
        ) {
            throw StudyError("STUDY_IDENTITY", "prompt SHA-256 mismatch")
        }
        val runtime = root["runtime"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_IDENTITY", "runtime block missing")
        if (runtime["impl"] != BenchContract.RUNTIME_IMPL ||
            runtime["backend"] != BenchContract.BACKEND
        ) {
            throw StudyError("STUDY_IDENTITY",
                "runtime is not ${BenchContract.RUNTIME_IMPL}/" +
                    BenchContract.BACKEND)
        }
        if (runtime["version"] != constraints.runtimeVersion) {
            throw StudyError("STUDY_IDENTITY", "runtime version mismatch")
        }
        if (runtime["abi"] != constraints.runtimeAbi) {
            throw StudyError("STUDY_IDENTITY", "runtime ABI mismatch")
        }
        val native = root["x_native_build"] as? Map<String, Any?>
        val variant = native?.get("variant") as? String
        if (variant.isNullOrBlank()) {
            // Run-a class: no recorded build identity is
            // unqualified, never assumed benchmark.
            throw StudyError("STUDY_BUILD",
                "native variant unrecorded; build unqualified")
        }
        if (variant != constraints.nativeVariant) {
            throw StudyError("STUDY_BUILD",
                "native variant $variant, expected " +
                    constraints.nativeVariant)
        }
        val configMap = root["config"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_CONFIG", "config block missing")
        val config = SessionBenchBridge.configData(configMap)
            ?: throw StudyError("STUDY_CONFIG", "config unparsable")
        val configErrors = BenchConfig.validate(config)
        if (configErrors.isNotEmpty()) {
            throw StudyError("STUDY_CONFIG",
                "config fails host validation: " +
                    configErrors.joinToString("; "))
        }
        val measurements = root["measurements"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_THROUGHPUT",
                "measurements block missing")
        val gen = (measurements["tps_gen"] as? Number)?.toDouble()
        val prefill = (measurements["tps_prefill"] as? Number)?.toDouble()
        if (gen == null || !gen.isFinite() || gen <= 0 ||
            prefill == null || !prefill.isFinite() || prefill <= 0
        ) {
            throw StudyError("STUDY_THROUGHPUT",
                "throughput missing, non-finite, or non-positive")
        }
        val thermal = measurements["thermal"] as? Map<String, Any?>
        val thermalAfter = thermal?.get("after") as? String
        if (thermalAfter.isNullOrBlank()) {
            throw StudyError("STUDY_THROUGHPUT",
                "terminal thermal state missing")
        }
        val collected = root["collected_at_utc"] as? String
        if (collected.isNullOrBlank()) {
            throw StudyError("STUDY_TIME", "collection timestamp missing")
        }
        // Identical cutoff for all arms; equality proves
        // nothing, so strictly-before is required.
        if (!(collected < evidenceCutoffUtc)) {
            throw StudyError("STUDY_TIME",
                "collected $collected is not before cutoff " +
                    evidenceCutoffUtc)
        }
        return FrozenEvidence(
            corpusId = artifact.corpusId,
            runId = runId,
            artifactSha256 = actualSha,
            config = config,
            genTps = gen,
            prefillTps = prefill,
            thermalAfter = thermalAfter,
            collectedAtUtc = collected,
            modelSizeBytes = (model["size_bytes"] as? Number)?.toLong()
                ?: throw StudyError("STUDY_IDENTITY",
                    "model size missing"),
            quantization = model["quantization"] as? String
                ?: throw StudyError("STUDY_IDENTITY",
                    "quantization missing"),
            nativeOpt = (native["native_opt"] as? String).orEmpty()
        )
    }

    private fun tripleOf(config: BenchConfigData): Triple<Int, Int, Int> =
        Triple(config.nCtx, config.nThreads, config.nPredict)

    /** Canonical manifest bytes (without the hash itself). */
    fun manifestBytes(manifest: StudyManifest): ByteArray {
        val map = linkedMapOf<String, Any?>(
            "study_id" to manifest.studyId,
            "manifest_version" to manifest.manifestVersion,
            "frozen_at_utc" to manifest.frozenAtUtc,
            "frozen_at_note" to manifest.frozenAtNote,
            "evidence_cutoff_utc" to manifest.evidenceCutoffUtc,
            "constraints" to linkedMapOf(
                "model_sha256" to manifest.constraints.modelSha256,
                "model_size_bytes" to manifest.constraints.modelSizeBytes,
                "quantization" to manifest.constraints.quantization,
                "workload_id" to manifest.constraints.workloadId,
                "prompt_sha256" to manifest.constraints.promptSha256,
                "runtime_version" to manifest.constraints.runtimeVersion,
                "runtime_abi" to manifest.constraints.runtimeAbi,
                "native_variant" to manifest.constraints.nativeVariant,
                "device_max_threads" to
                    manifest.constraints.deviceMaxThreads,
                "mem_avail_pct" to manifest.constraints.memAvailPct,
                "mem_low_memory" to manifest.constraints.memLowMemory
            ),
            "candidate_a" to SessionBenchBridge.configMap(manifest.candidateA),
            "candidate_b" to SessionBenchBridge.configMap(manifest.candidateB),
            "candidate_hash" to manifest.candidateHash,
            "admitted" to manifest.admitted.map { e ->
                linkedMapOf(
                    "corpus_id" to e.corpusId,
                    "run_id" to e.runId,
                    "artifact_sha256" to e.artifactSha256,
                    "config" to SessionBenchBridge.configMap(e.config),
                    "gen_tps" to e.genTps,
                    "prefill_tps" to e.prefillTps,
                    "thermal_after" to e.thermalAfter,
                    "collected_at_utc" to e.collectedAtUtc,
                    "model_size_bytes" to e.modelSizeBytes,
                    "quantization" to e.quantization,
                    "native_opt" to e.nativeOpt
                )
            },
            "m0_withheld_run_ids" to manifest.m0WithheldRunIds,
            "m0_pack_hash" to manifest.m0PackHash,
            "m1_pack_hash" to manifest.m1PackHash,
            "m1_exclusions" to manifest.m1Exclusions
        )
        return SessionJson.stringify(map).toByteArray(Charsets.UTF_8)
    }

    fun manifestJson(manifest: StudyManifest): String {
        val base = SessionJson.parse(
            manifestBytes(manifest).toString(Charsets.UTF_8))
            as LinkedHashMap<String, Any?>
        base["manifest_hash"] = manifest.manifestHash
        return SessionJson.stringify(base)
    }

    private fun hashManifest(manifest: StudyManifest): String =
        BenchConfig.sha256Hex(manifestBytes(manifest))

    /**
     * Re-parses a persisted manifest and re-verifies its
     * hash. Structural + integrity check only: content
     * trust still requires re-running [freezeStudy] over
     * the artifact bytes.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseManifest(json: String): StudyManifest {
        val root = try {
            SessionJson.parse(json) as? Map<String, Any?>
        } catch (_: SessionJson.JsonError) {
            throw StudyError("STUDY_MANIFEST", "manifest is not valid JSON")
        } ?: throw StudyError("STUDY_MANIFEST", "manifest is not an object")
        if (root["study_id"] != STUDY_ID ||
            (root["manifest_version"] as? Number)?.toInt() !=
            STUDY_MANIFEST_VERSION
        ) {
            throw StudyError("STUDY_MANIFEST",
                "not an $STUDY_ID v$STUDY_MANIFEST_VERSION manifest")
        }
        val c = root["constraints"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_MANIFEST", "constraints missing")
        fun str(key: String): String = (root[key] as? String)
            ?: throw StudyError("STUDY_MANIFEST", "$key missing")
        fun cstr(key: String): String = (c[key] as? String)
            ?: throw StudyError("STUDY_MANIFEST",
                "constraints.$key missing")
        val constraints = EvalArms.ArmConstraints(
            modelSha256 = cstr("model_sha256"),
            modelSizeBytes = (c["model_size_bytes"] as? Number)?.toLong()
                ?: throw StudyError("STUDY_MANIFEST",
                    "constraints.model_size_bytes missing"),
            quantization = cstr("quantization"),
            workloadId = cstr("workload_id"),
            promptSha256 = cstr("prompt_sha256"),
            runtimeVersion = cstr("runtime_version"),
            runtimeAbi = cstr("runtime_abi"),
            nativeVariant = cstr("native_variant"),
            deviceMaxThreads =
                (c["device_max_threads"] as? Number)?.toInt()
                    ?: throw StudyError("STUDY_MANIFEST",
                        "constraints.device_max_threads missing"),
            memAvailPct = (c["mem_avail_pct"] as? Number)?.toDouble(),
            memLowMemory = c["mem_low_memory"] as? Boolean
        )
        val candidateAMap = root["candidate_a"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_MANIFEST", "candidate_a missing")
        val candidateA = SessionBenchBridge.configData(candidateAMap)
            ?: throw StudyError("STUDY_MANIFEST", "candidate_a unparsable")
        val candidateBMap = root["candidate_b"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_MANIFEST", "candidate_b missing")
        val candidateB = SessionBenchBridge.configData(candidateBMap)
            ?: throw StudyError("STUDY_MANIFEST", "candidate_b unparsable")
        val admitted = (root["admitted"] as? List<*>)
            ?: throw StudyError("STUDY_MANIFEST", "admitted missing")
        val frozen = admitted.map { item ->
            val e = item as? Map<String, Any?>
                ?: throw StudyError("STUDY_MANIFEST",
                    "admitted entry unparsable")
            FrozenEvidence(
                corpusId = e["corpus_id"] as? String
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.corpus_id missing"),
                runId = e["run_id"] as? String
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.run_id missing"),
                artifactSha256 = e["artifact_sha256"] as? String
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.artifact_sha256 missing"),
                config = SessionBenchBridge.configData(
                    (e["config"] as? Map<String, Any?>)
                        ?: throw StudyError("STUDY_MANIFEST",
                            "admitted.config missing"))
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.config unparsable"),
                genTps = (e["gen_tps"] as? Number)?.toDouble()
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.gen_tps missing"),
                prefillTps = (e["prefill_tps"] as? Number)?.toDouble()
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.prefill_tps missing"),
                thermalAfter = e["thermal_after"] as? String
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.thermal_after missing"),
                collectedAtUtc = e["collected_at_utc"] as? String
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.collected_at_utc missing"),
                modelSizeBytes =
                    (e["model_size_bytes"] as? Number)?.toLong()
                        ?: throw StudyError("STUDY_MANIFEST",
                            "admitted.model_size_bytes missing"),
                quantization = e["quantization"] as? String
                    ?: throw StudyError("STUDY_MANIFEST",
                        "admitted.quantization missing"),
                nativeOpt = e["native_opt"] as? String ?: ""
            )
        }
        val manifest = StudyManifest(
            studyId = STUDY_ID,
            manifestVersion = STUDY_MANIFEST_VERSION,
            frozenAtUtc = str("frozen_at_utc"),
            frozenAtNote = str("frozen_at_note"),
            evidenceCutoffUtc = str("evidence_cutoff_utc"),
            constraints = constraints,
            candidateA = candidateA,
            candidateB = candidateB,
            candidateHash = str("candidate_hash"),
            admitted = frozen,
            m0WithheldRunIds = ((root["m0_withheld_run_ids"] as? List<*>)
                ?: throw StudyError("STUDY_MANIFEST",
                    "m0_withheld_run_ids missing"))
                .map {
                    it as? String ?: throw StudyError("STUDY_MANIFEST",
                        "m0_withheld_run_ids holds strings")
                },
            m0PackHash = str("m0_pack_hash"),
            m1PackHash = str("m1_pack_hash"),
            m1Exclusions = ((root["m1_exclusions"] as? List<*>)
                ?: throw StudyError("STUDY_MANIFEST",
                    "m1_exclusions missing"))
                .map {
                    it as? String ?: throw StudyError("STUDY_MANIFEST",
                        "m1_exclusions holds strings")
                },
            manifestHash = str("manifest_hash")
        )
        if (!hashManifest(manifest).equals(manifest.manifestHash,
                ignoreCase = true)
        ) {
            throw StudyError("STUDY_MANIFEST",
                "manifest hash mismatch: file changed since freeze")
        }
        return manifest
    }

    // ---- Task 3: executable R/M0/M1 workflow ----

    /**
     * M0 system prompt: constraints-only selection. Names
     * the withheld evidence explicitly so the model knows
     * history exists but is out of scope for this arm.
     */
    fun studySystemM0(withheldCount: Int): String =
        "You are an EdgeFit configuration analyst. Answer with ONLY a " +
            "single JSON object, no prose outside it, with exactly these " +
            "fields: model_sha (string), workload_id (string), selection " +
            "(one of \"A\", \"B\", \"abstain\"), abstain_reason (string " +
            "or null), expected_gen_tps (number or null), " +
            "expected_prefill_tps (number or null), uncertainty (string " +
            "or null), supporting_evidence_ids (string array), " +
            "limitations (string array). Echo model_sha and workload_id " +
            "EXACTLY as given. Select the candidate you expect to " +
            "measure higher generation throughput, or abstain with a " +
            "reason when no defensible choice exists — abstaining is a " +
            "correct answer, never a failure. You have NO historical " +
            "measurements ($withheldCount withheld by design for this " +
            "arm): supporting_evidence_ids must be []. Never invent " +
            "measurements, run ids, or statistics. Estimates are " +
            "finite positive tokens/second when offered."

    /**
     * M1 system prompt: same selection task WITH the
     * manifested compatible measurements below. Citations
     * must resolve to manifested run ids.
     */
    fun studySystemM1(): String =
        "You are an EdgeFit configuration analyst. Answer with ONLY a " +
            "single JSON object, no prose outside it, with exactly these " +
            "fields: model_sha (string), workload_id (string), selection " +
            "(one of \"A\", \"B\", \"abstain\"), abstain_reason (string " +
            "or null), expected_gen_tps (number or null), " +
            "expected_prefill_tps (number or null), uncertainty (string " +
            "or null), supporting_evidence_ids (string array), " +
            "limitations (string array). Echo model_sha and workload_id " +
            "EXACTLY as given. Select the candidate you expect to " +
            "measure higher generation throughput, or abstain with a " +
            "reason when no defensible choice exists — abstaining is a " +
            "correct answer, never a failure. Cite ONLY run ids from " +
            "MANIFESTED_EVIDENCE. Never invent measurements, run ids, " +
            "or statistics. Estimates are finite positive " +
            "tokens/second when offered."

    fun studyUser(manifest: StudyManifest, arm: String): String {
        val c = manifest.constraints
        val lines = mutableListOf(
            "STUDY: $STUDY_ID manifest=${manifest.manifestHash}",
            "MODEL: sha256=${c.modelSha256} size_bytes=" +
                "${c.modelSizeBytes} quantization=${c.quantization}",
            "WORKLOAD: id=${c.workloadId} " +
                "prompt_sha256=${c.promptSha256}",
            "RUNTIME: version=${c.runtimeVersion} abi=${c.runtimeAbi} " +
                "variant=${c.nativeVariant}",
            "DEVICE: max_threads=${c.deviceMaxThreads} " +
                "mem_avail_pct=${c.memAvailPct ?: "unknown"}",
            "CANDIDATE_A: n_ctx=${manifest.candidateA.nCtx} " +
                "n_threads=${manifest.candidateA.nThreads} " +
                "n_predict=${manifest.candidateA.nPredict}",
            "CANDIDATE_B: n_ctx=${manifest.candidateB.nCtx} " +
                "n_threads=${manifest.candidateB.nThreads} " +
                "n_predict=${manifest.candidateB.nPredict}",
            "CANDIDATE_HASH: ${manifest.candidateHash}"
        )
        if (arm == EvalArms.ARM_M1) {
            lines.add("MANIFESTED_EVIDENCE:")
            for (e in manifest.admitted) {
                lines.add("${e.runId} ctx=${e.config.nCtx} " +
                    "threads=${e.config.nThreads} " +
                    "predict=${e.config.nPredict} gen_tps=${e.genTps} " +
                    "prefill_tps=${e.prefillTps} " +
                    "thermal_after=${e.thermalAfter} " +
                    "at=${e.collectedAtUtc}")
            }
        } else {
            lines.add("MANIFESTED_EVIDENCE: withheld by M0 design " +
                "(${manifest.m0WithheldRunIds.size} run(s)); " +
                "select from constraints only.")
        }
        lines.add("Select exactly one of A, B, or abstain.")
        return lines.joinToString("\n")
    }

    /** Provider call record, parsed best-effort (nulls when absent). */
    data class StudyCallRecord(
        val providerRequestId: String? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val latencyMs: Long? = null,
        val costUsd: Double? = null
    )

    interface StudyTransport {
        fun post(apiKey: String, body: String): StudyHttpResult
    }

    data class StudyHttpResult(val status: Int, val body: String)

    /**
     * PREP default: live calls are NOT authorized. A
     * future owner-authorized step wraps the existing
     * [HttpsMetaTransport] with an owner-supplied key.
     */
    object DisabledStudyTransport : StudyTransport {
        override fun post(apiKey: String, body: String): StudyHttpResult =
            throw StudyError("LIVE_NOT_AUTHORIZED",
                "live Muse calls need separate owner authorization")
    }

    /**
     * Test/fixture transport. Every decision recorded
     * through it carries transport=mock-synthetic and
     * must never be mistaken for a live reply.
     */
    class MockStudyTransport(val cannedBody: String) : StudyTransport {
        override fun post(apiKey: String, body: String): StudyHttpResult =
            StudyHttpResult(200, cannedBody)
    }

    @Suppress("UNCHECKED_CAST")
    fun parseUsage(responseBody: String): StudyCallRecord {
        return try {
            val root = SessionJson.parse(responseBody) as? Map<String, Any?>
                ?: return StudyCallRecord()
            val usage = root["usage"] as? Map<String, Any?>
            StudyCallRecord(
                providerRequestId = root["id"] as? String,
                promptTokens =
                    (usage?.get("input_tokens") as? Number)?.toInt(),
                completionTokens =
                    (usage?.get("output_tokens") as? Number)?.toInt()
            )
        } catch (_: SessionJson.JsonError) {
            StudyCallRecord()
        }
    }

    data class PreflightInput(
        val constraints: EvalArms.ArmConstraints,
        val candidateA: BenchConfigData,
        val candidateB: BenchConfigData,
        val artifacts: List<ArtifactInput>,
        val evidenceCutoffUtc: String,
        /** Single clock read for freeze + R decision. */
        val nowUtc: String,
        val memAvailPct: Double? = null,
        val memLowMemory: Boolean? = null
    )

    data class PreflightOutput(
        val verdict: String,
        val problems: List<String>,
        val manifestJson: String? = null,
        val decisionRJson: String? = null,
        val requestM0Json: String? = null,
        val requestM1Json: String? = null,
        /** Human-readable prompts for owner review. */
        val promptsText: String? = null
    )

    /**
     * Offline preflight: freeze -> R decision -> M0/M1
     * request payloads -> READY/BLOCKED. Never throws on
     * study problems: they become BLOCKED with reasons.
     * M0/M1 DECISIONS are not made here (transport
     * disabled in PREP); see [recordArmDecision] for the
     * owner-authorized step.
     */
    fun runPreflight(input: PreflightInput): PreflightOutput {
        val manifest = try {
            freezeStudy(input.constraints, input.candidateA,
                input.candidateB, input.artifacts,
                input.evidenceCutoffUtc, input.nowUtc)
        } catch (e: StudyError) {
            return PreflightOutput(VERDICT_BLOCKED,
                listOf("[${e.code}] ${e.message}"))
        }
        val problems = mutableListOf<String>()
        // R decides offline over the frozen admitted set.
        // One cohort by construction (freeze enforces a
        // single model/workload/runtime/build identity),
        // keyed by the manifest hash.
        val evidence = manifest.admitted.groupBy {
            Triple(it.config.nCtx, it.config.nThreads, it.config.nPredict)
        }.map { (_, records) ->
            EvalBaseline.CandidateEvidence(
                config = records.first().config,
                genSamples = records.map { it.genTps },
                elevatedThermal = records.count {
                    it.thermalAfter != "none"
                },
                cohortKey = manifest.manifestHash
            )
        }
        val r = try {
            EvalBaseline.decide(
                listOf(manifest.candidateA, manifest.candidateB),
                evidence, manifest.constraints.deviceMaxThreads,
                input.memAvailPct, input.memLowMemory)
        } catch (e: IllegalArgumentException) {
            return PreflightOutput(VERDICT_BLOCKED,
                listOf("R baseline failed: ${e.message}"))
        }
        val rDecision = recordRDecision(r, manifest, input.nowUtc)
        val m0Body: String
        val m1Body: String
        try {
            m0Body = MetaApi.requestBody(
                studySystemM0(manifest.m0WithheldRunIds.size),
                studyUser(manifest, EvalArms.ARM_M0))
            m1Body = MetaApi.requestBody(
                studySystemM1(),
                studyUser(manifest, EvalArms.ARM_M1))
        } catch (e: MetaApi.MetaError) {
            return PreflightOutput(VERDICT_BLOCKED,
                listOf("request build failed: ${e.message}"))
        }
        if (r.abstained) {
            // Informational, not blocking: an abstaining R
            // is a valid study outcome (insufficient
            // evidence), recorded in the decision artifact.
            problems.add("note: R abstained (${r.abstainReason})")
        }
        val prompts = "=== M0 SYSTEM ===\n" +
            studySystemM0(manifest.m0WithheldRunIds.size) +
            "\n\n=== M0 USER ===\n" +
            studyUser(manifest, EvalArms.ARM_M0) +
            "\n\n=== M1 SYSTEM ===\n" + studySystemM1() +
            "\n\n=== M1 USER ===\n" +
            studyUser(manifest, EvalArms.ARM_M1)
        return PreflightOutput(
            verdict = VERDICT_READY,
            problems = problems,
            manifestJson = manifestJson(manifest),
            decisionRJson = decisionJson(rDecision),
            requestM0Json = requestJson(EvalArms.ARM_M0,
                m0Body, input.nowUtc),
            requestM1Json = requestJson(EvalArms.ARM_M1,
                m1Body, input.nowUtc),
            promptsText = prompts
        )
    }

    private fun recordRDecision(
        r: EvalBaseline.BaselineDecision,
        manifest: StudyManifest,
        nowUtc: String
    ): StudyDecision {
        val selection = when {
            r.abstained -> SELECT_ABSTAIN
            r.selected == manifest.candidateA -> SELECT_A
            r.selected == manifest.candidateB -> SELECT_B
            else -> throw StudyError("STUDY_R",
                "R selected outside the frozen set")
        }
        val ranked = r.ranked.firstOrNull { it.config == r.selected }
        return StudyDecision(
            arm = EvalArms.ARM_R,
            selection = selection,
            selectedConfig = r.selected,
            abstainReason = r.abstainReason,
            expectedGenTps = ranked?.predictedGen,
            expectedPrefillTps = null,
            uncertainty = null,
            supportingEvidenceIds = emptyList(),
            limitations = if (r.abstained) {
                listOf("R abstained: ${r.abstainReason}")
            } else {
                emptyList()
            },
            notes = r.factors,
            rawJson = SessionJson.stringify(linkedMapOf(
                "arm" to EvalArms.ARM_R,
                "selection" to selection,
                "factors" to r.factors
            )),
            decidedAtUtc = nowUtc,
            transport = TRANSPORT_OFFLINE
        )
    }

    private fun requestJson(arm: String, body: String, nowUtc: String): String =
        SessionJson.stringify(linkedMapOf(
            "study_id" to STUDY_ID,
            "arm" to arm,
            "endpoint" to MetaApi.ENDPOINT,
            "model" to MetaApi.MODEL,
            "built_at_utc" to nowUtc,
            "body_sha256" to BenchConfig.sha256Hex(
                body.toByteArray(Charsets.UTF_8)),
            "body" to body
        ))

    fun decisionJson(decision: StudyDecision): String =
        SessionJson.stringify(linkedMapOf(
            "study_id" to STUDY_ID,
            "arm" to decision.arm,
            "selection" to decision.selection,
            "selected_config" to decision.selectedConfig?.let {
                SessionBenchBridge.configMap(it)
            },
            "abstain_reason" to decision.abstainReason,
            "expected_gen_tps" to decision.expectedGenTps,
            "expected_prefill_tps" to decision.expectedPrefillTps,
            "uncertainty" to decision.uncertainty,
            "supporting_evidence_ids" to decision.supportingEvidenceIds,
            "limitations" to decision.limitations,
            "notes" to decision.notes,
            "raw_json" to decision.rawJson,
            "decided_at_utc" to decision.decidedAtUtc,
            "transport" to decision.transport,
            "provider_request_id" to decision.providerRequestId,
            "prompt_tokens" to decision.promptTokens,
            "completion_tokens" to decision.completionTokens,
            "latency_ms" to decision.latencyMs,
            "cost_usd" to decision.costUsd
        ))

    /**
     * Owner-authorized step: sends one arm request through
     * [transport], validates the reply against the study
     * contract, and returns the immutable decision record.
     * The decision timestamp is read from [clock] AFTER
     * receipt AND validation, so it can never predate the
     * evidence it records. Study problems throw
     * [StudyError]; transport failures propagate (the
     * caller must not retry: one call, one record).
     */
    fun recordArmDecision(
        arm: String,
        requestBody: String,
        expectation: StudyExpectation,
        transport: StudyTransport,
        apiKey: String,
        clock: () -> String,
        transportLabel: String,
        latencyMs: Long? = null
    ): StudyDecision {
        val result = transport.post(apiKey, requestBody)
        if (result.status != 200) {
            throw StudyError("STUDY_PROVIDER",
                "provider returned HTTP ${result.status}")
        }
        val text = MetaApi.extractText(try {
            SessionJson.parse(result.body)
        } catch (_: SessionJson.JsonError) {
            throw StudyError("STUDY_PROVIDER",
                "provider reply is not valid JSON")
        })
        val raw = try {
            MetaApi.rawSlice(text)
        } catch (e: MetaApi.MetaError) {
            throw StudyError("STUDY_REPLY", e.message ?: e.code)
        }
        val payload = MetaApi.extractJsonObject(text)
            ?: throw StudyError("STUDY_REPLY", "no JSON object in reply")
        val usage = parseUsage(result.body)
        val decision = validateStudyDecision(payload, raw, expectation, arm,
            clock(), transportLabel)
        // Timestamp AFTER validation: a decision that
        // failed validation was never decided.
        return decision.copy(
            decidedAtUtc = clock(),
            providerRequestId = usage.providerRequestId,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            latencyMs = latencyMs
        )
    }

    /**
     * FINAL Task 3: the smallest safe live adapter. Wraps
     * the qualified [HttpsMetaTransport] unchanged
     * (fixed allowlisted endpoint, bounded request/reply,
     * no retries, no credential logging) and maps its
     * IO failures to [StudyError] so the study runner
     * fails closed with no silent retry.
     */
    class LiveStudyTransport(
        private val inner: MetaTransport = HttpsMetaTransport()
    ) : StudyTransport {
        override fun post(apiKey: String, body: String): StudyHttpResult {
            if (apiKey.isBlank()) {
                throw StudyError("STUDY_KEY",
                    "no API key supplied for the live call")
            }
            val result = try {
                inner.post(apiKey, body)
            } catch (e: java.io.IOException) {
                throw StudyError("STUDY_PROVIDER",
                    "transport failed: ${e.message}")
            }
            return StudyHttpResult(result.status, result.body)
        }
    }

    data class FrozenRequest(
        val arm: String,
        val endpoint: String,
        val model: String,
        val body: String,
        val bodySha256: String,
        val builtAtUtc: String
    )

    /**
     * Loads one frozen request artifact unchanged and
     * verifies it: body hash recomputed, endpoint and
     * model pinned, body bound to this manifest and
     * candidate set. Any deviation throws.
     */
    @Suppress("UNCHECKED_CAST")
    fun loadFrozenRequest(
        requestJson: String,
        arm: String,
        manifest: StudyManifest
    ): FrozenRequest {
        val req = try {
            SessionJson.parse(requestJson) as? Map<String, Any?>
        } catch (_: SessionJson.JsonError) {
            throw StudyError("STUDY_REQUEST", "request is not valid JSON")
        } ?: throw StudyError("STUDY_REQUEST", "request is not an object")
        if (req["study_id"] != STUDY_ID || req["arm"] != arm) {
            throw StudyError("STUDY_REQUEST",
                "request is not for $STUDY_ID arm $arm")
        }
        if (req["endpoint"] != MetaApi.ENDPOINT) {
            throw StudyError("STUDY_REQUEST",
                "endpoint is not the fixed Meta endpoint")
        }
        if (req["model"] != MetaApi.MODEL) {
            throw StudyError("STUDY_REQUEST", "model is not " + MetaApi.MODEL)
        }
        val body = req["body"] as? String
            ?: throw StudyError("STUDY_REQUEST", "body missing")
        val sha = req["body_sha256"] as? String
        if (sha == null || !BenchConfig.sha256Hex(
                body.toByteArray(Charsets.UTF_8))
                .equals(sha, ignoreCase = true)
        ) {
            throw StudyError("STUDY_REQUEST",
                "frozen body changed since preflight")
        }
        if (!body.contains(manifest.manifestHash) ||
            !body.contains(manifest.candidateHash)
        ) {
            throw StudyError("STUDY_REQUEST",
                "body not bound to this manifest/candidate set")
        }
        return FrozenRequest(
            arm = arm,
            endpoint = MetaApi.ENDPOINT,
            model = MetaApi.MODEL,
            body = body,
            bodySha256 = sha,
            builtAtUtc = req["built_at_utc"] as? String
                ?: throw StudyError("STUDY_REQUEST", "built_at_utc missing")
        )
    }

    /**
     * M1 citation re-check (defense in depth behind the
     * contract gate): every cited id must be a manifested
     * admitted run. M0 must cite nothing.
     */
    fun verifyArmCitations(
        decision: StudyDecision,
        manifest: StudyManifest
    ): List<String> {
        val admitted = manifest.admitted.map { it.runId }.toSet()
        return if (decision.arm == EvalArms.ARM_M0) {
            if (decision.supportingEvidenceIds.isEmpty()) {
                emptyList()
            } else {
                listOf("M0 cites evidence despite empty allowlist: " +
                    decision.supportingEvidenceIds.joinToString(","))
            }
        } else {
            decision.supportingEvidenceIds
                .filter { it !in admitted }
                .map { "M1 cites unmanifested run $it" }
        }
    }

    // ---- Task 5: run START ordering ----

    /**
     * Run-id START stamp: BenchRunner captures the id
     * stamp AND collected_at_utc at run entry, before
     * guards and native execution (BenchRunner.kt), so
     * the id stamp IS the benchmark START time.
     * `BENCH-20261027T170002Z-36227bc0` ->
     * `2026-10-27T17:00:02Z`. Null when unparseable.
     */
    fun runStartUtc(runId: String): String? {
        val match = Regex(
            "^BENCH-(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})Z-" +
                "[0-9a-fA-F]{8}$").matchEntire(runId) ?: return null
        val (y, mo, d, h, mi, s) = match.destructured
        return "$y-$mo-${d}T$h:$mi:${s}Z"
    }

    data class OrderingVerdict(
        val runId: String,
        /** Every arm decision strictly predates this START. */
        val ok: Boolean,
        val detail: String
    )

    /**
     * Verifies every arm decision predates every run
     * START (strict: equal second-precision stamps prove
     * nothing). Also cross-checks each run's
     * collected_at_utc against its START stamp (both are
     * entry-captured; >120s apart means corrupted
     * evidence). Any failure blocks scoring.
     */
    fun verifyRunOrdering(
        decisions: List<StudyDecision>,
        runs: List<NewRun>
    ): List<OrderingVerdict> {
        return runs.map { run ->
            val start = runStartUtc(run.runId)
            if (start == null) {
                OrderingVerdict(run.runId, false,
                    "run id is not a verifiable START stamp")
            } else if (!withinSeconds(run.collectedAtUtc, start, 120)) {
                OrderingVerdict(run.runId, false,
                    "collected_at_utc ${run.collectedAtUtc} is " +
                        "inconsistent with START stamp $start")
            } else {
                val late = decisions.filter {
                    !(it.decidedAtUtc < start)
                }
                if (late.isEmpty()) {
                    OrderingVerdict(run.runId, true,
                        "${decisions.size} decision(s) predate START $start")
                } else {
                    OrderingVerdict(run.runId, false,
                        "decision(s) not before START $start: " +
                            late.joinToString(",") {
                                "${it.arm}@${it.decidedAtUtc}"
                            })
                }
            }
        }
    }

    private fun withinSeconds(a: String, b: String, tolerance: Long): Boolean {
        // Lexicographic ISO-8601 UTC compares
        // chronologically; parse to epoch only for the
        // distance check.
        return try {
            val pa = java.time.Instant.parse(a)
            val pb = java.time.Instant.parse(b)
            kotlin.math.abs(pa.epochSecond - pb.epochSecond) <= tolerance
        } catch (_: Exception) {
            false
        }
    }

    // ---- Task 6: prospective scoring ----

    /**
     * One new physical run offered for scoring, verified
     * like frozen evidence (minus the historical cutoff:
     * new runs must POSTDATE the freeze instead).
     */
    data class NewRun(
        val runId: String,
        val artifactSha256: String,
        val config: BenchConfigData,
        val genTps: Double,
        val prefillTps: Double,
        val thermalAfter: String,
        val collectedAtUtc: String,
        val status: String
    )

    data class ConfigSummary(
        val medianGen: EvalMetrics.MetricValue,
        val minGen: Double?,
        val maxGen: Double?,
        val n: Int
    )

    /**
     * FINAL Task 5: three metric levels, never mixed.
     * - [decisionReports]: ONE case per arm (selection
     *   validity, citation grounding, one cost/latency
     *   record per provider call). Six runs never become
     *   six decisions.
     * - [errorReports]: prediction-error pairs per arm
     *   (no cost/latency/regret inputs here: a single
     *   call's cost is not repeated per run).
     * - [regretByArm]: exactly ONE regret observation per
     *   arm (selection vs tested medians, n=1).
     */
    data class StudyScore(
        val candidateA: ConfigSummary,
        val candidateB: ConfigSummary,
        val decisionReports: Map<String, EvalMetrics.EvalReport>,
        val errorReports: Map<String, EvalMetrics.EvalReport>,
        val regretByArm: Map<String, EvalMetrics.MetricValue>,
        val excludedRuns: List<String>,
        /** Exploratory labeling + metric caveats. */
        val labels: List<String>
    )

    /**
     * Scores frozen decisions against verified new runs.
     * Medians/spread per config are descriptive
     * (exploratory at n<=3); regret and errors reuse
     * [EvalMetrics] semantics; abstention yields
     * NOT_EVALUABLE regret with an explicit reason (an
     * abstention is NOT a failed selection); unmeasured
     * configs and missing estimates yield NOT_EVALUABLE
     * with reasons. Non-COMPLETED runs are excluded with
     * reasons, never scored.
     *
     * FINAL: this unit-level entry takes already-
     * verified runs (tests, analysis). QUALIFIED study
     * scoring must go through [scoreStudy], which
     * ingests source bytes, re-validates decisions, and
     * enforces temporal ordering itself.
     */
    fun scoreProspective(
        manifest: StudyManifest,
        decisions: List<StudyDecision>,
        runs: List<NewRun>
    ): StudyScore {
        val labels = mutableListOf(
            "exploratory: at most 3 trials per config; " +
                "descriptive summaries only, not confirmatory",
            "confirmation requires the EF-SCIENCE-01 " +
                "five-trial-per-config protocol"
        )
        val excluded = mutableListOf<String>()
        val usable = runs.filter { run ->
            val start = runStartUtc(run.runId)
            when {
                start == null -> {
                    excluded.add("${run.runId}: unverifiable START stamp")
                    false
                }
                !(manifest.frozenAtUtc < start) -> {
                    excluded.add("${run.runId}: START $start does not " +
                        "postdate freeze ${manifest.frozenAtUtc}")
                    false
                }
                run.status != BenchContract.STATUS_COMPLETED -> {
                    excluded.add("${run.runId}: status ${run.status}, " +
                        "not COMPLETED")
                    false
                }
                tripleOf(run.config) != tripleOf(manifest.candidateA) &&
                    tripleOf(run.config) != tripleOf(manifest.candidateB) -> {
                    excluded.add("${run.runId}: unregistered config")
                    false
                }
                !run.genTps.isFinite() || run.genTps <= 0 -> {
                    excluded.add("${run.runId}: unusable throughput")
                    false
                }
                else -> true
            }
        }
        fun summarize(triple: Triple<Int, Int, Int>): ConfigSummary {
            val values = usable
                .filter { tripleOf(it.config) == triple }
                .map { it.genTps }
            if (values.isEmpty()) {
                return ConfigSummary(
                    EvalMetrics.MetricValue.missing(
                        "config unmeasured in this study"),
                    null, null, 0)
            }
            return ConfigSummary(
                EvalMetrics.MetricValue.ok(
                    medianOf(values), values.size),
                values.minOrNull(), values.maxOrNull(), values.size)
        }
        val tripleA = tripleOf(manifest.candidateA)
        val tripleB = tripleOf(manifest.candidateB)
        val summaryA = summarize(tripleA)
        val summaryB = summarize(tripleB)
        val medians = mutableMapOf<Triple<Int, Int, Int>, Double>()
        if (summaryA.medianGen.value != null) {
            medians[tripleA] = summaryA.medianGen.value!!
        }
        if (summaryB.medianGen.value != null) {
            medians[tripleB] = summaryB.medianGen.value!!
        }
        val knownIds = manifest.admitted.map { it.runId }.toSet()
        // Decision level: exactly one case per arm. Cost,
        // latency, and citations live HERE and only here.
        val decisionCases = decisions.map { decision ->
            EvalMetrics.ProposalCase(
                arm = decision.arm,
                proposed = decision.selection != SELECT_ABSTAIN,
                valid = if (decision.selection == SELECT_ABSTAIN) {
                    null
                } else {
                    true
                },
                // Study decisions pass no host refusal
                // gate (they authorize nothing), so
                // rejection evidence is N/A, not false.
                rejectedByHost = null,
                abstained = decision.selection == SELECT_ABSTAIN,
                abstainAppropriate = null,
                citedIds = decision.supportingEvidenceIds,
                knownIds = knownIds,
                latencyMs = decision.latencyMs,
                costUsd = decision.costUsd
            )
        }
        // Run level: prediction-error pairs only. No cost,
        // latency, citations, or regret inputs: repeating
        // them per run would multiply one observation.
        val errorCases = mutableListOf<EvalMetrics.ProposalCase>()
        for (decision in decisions) {
            if (decision.selection == SELECT_ABSTAIN) {
                labels.add("${decision.arm} abstained " +
                    "(${decision.abstainReason}): no regret " +
                    "computed; abstention is not a failed selection")
                continue
            }
            val selected = decision.selectedConfig!!
            val inConfig = usable.filter {
                tripleOf(it.config) == tripleOf(selected)
            }
            if (inConfig.isEmpty()) {
                labels.add("${decision.arm} selected " +
                    "${decision.selection}, unmeasured in this study")
            }
            for (run in inConfig) {
                errorCases.add(EvalMetrics.ProposalCase(
                    arm = decision.arm,
                    proposed = true,
                    valid = true,
                    rejectedByHost = null,
                    abstained = false,
                    abstainAppropriate = null,
                    predictedGen = decision.expectedGenTps,
                    predictedAtUtc = decision.decidedAtUtc,
                    measuredGen = run.genTps,
                    measuredAtUtc = runStartUtc(run.runId)
                ))
            }
        }
        val arms = decisions.map { it.arm }.toSet()
        return StudyScore(
            candidateA = summaryA,
            candidateB = summaryB,
            decisionReports = arms.associateWith { arm ->
                EvalMetrics.summarize(decisionCases, arm)
            },
            errorReports = arms.associateWith { arm ->
                EvalMetrics.summarize(errorCases, arm)
            },
            regretByArm = arms.associateWith { arm ->
                val decision = decisions.first { it.arm == arm }
                studyRegret(decision, medians)
            },
            excludedRuns = excluded,
            labels = labels
        )
    }

    /**
     * Study level: ONE regret observation per arm. Best
     * tested median minus selected median, floored at
     * zero; NOT_EVALUABLE with an explicit reason when
     * the arm abstained, the selection is unmeasured, or
     * fewer than two configs measured.
     */
    private fun studyRegret(
        decision: StudyDecision,
        medians: Map<Triple<Int, Int, Int>, Double>
    ): EvalMetrics.MetricValue {
        if (decision.selection == SELECT_ABSTAIN) {
            return EvalMetrics.MetricValue.missing(
                "${decision.arm} abstained; abstention is not " +
                    "a failed selection")
        }
        if (medians.size < 2) {
            return EvalMetrics.MetricValue.missing(
                "fewer than two configs measured")
        }
        val selected = decision.selectedConfig!!
        val selectedValue = medians[tripleOf(selected)]
            ?: return EvalMetrics.MetricValue.missing(
                "${decision.arm} selected ${decision.selection}, " +
                    "unmeasured in this study")
        val best = medians.values.maxOrNull()!!
        return EvalMetrics.MetricValue.ok(
            maxOf(0.0, best - selectedValue), 1)
    }

    // ---- FINAL Task 4: source-backed qualified scoring ----

    /**
     * One exported benchmark JSON offered for scoring:
     * label (for error messages) plus the ACTUAL bytes.
     * Hashes recompute from bytes; nothing is trusted
     * from caller-supplied fields.
     */
    data class StudyRunInput(
        val label: String,
        val bytes: ByteArray
    )

    /**
     * Qualified study scoring. Ingests the SIX exported
     * run artifacts from source bytes (contract,
     * identities, completion, measurements, unique run
     * ids), re-validates the persisted M0/M1 decisions
     * against the study contract, re-derives R, and
     * ENFORCES decision-before-START ordering itself.
     * Missing (< 6), altered, or inconsistent artifacts
     * BLOCK scoring with [StudyError] — they never
     * degrade into silent exclusions. Non-COMPLETED
     * exported runs are legitimate outcomes: recorded in
     * excludedRuns, never scored.
     */
    @Suppress("UNCHECKED_CAST")
    fun scoreStudy(
        manifestJson: String,
        decisionJsons: List<String>,
        runInputs: List<StudyRunInput>
    ): StudyScore {
        val manifest = try {
            parseManifest(manifestJson)
        } catch (e: StudyError) {
            throw StudyError("STUDY_SCORE",
                "manifest invalid: [${e.code}] ${e.message}")
        }
        if (runInputs.size != 6) {
            throw StudyError("STUDY_SCORE",
                "expected 6 exported run artifacts, got " +
                    runInputs.size)
        }
        val decisions = decisionJsons.map {
            revalidateDecision(it, manifest)
        }
        val arms = decisions.map { it.arm }.toSet()
        if (arms != setOf(EvalArms.ARM_R, EvalArms.ARM_M0, EvalArms.ARM_M1)) {
            throw StudyError("STUDY_SCORE",
                "decisions must cover exactly R, M0, M1; got $arms")
        }
        val (usable, excluded) =
            ingestStudyRuns(manifest, decisions, runInputs)
        // Excluded runs ride along so their reasons
        // surface in one place; scoreProspective
        // re-excludes them identically.
        return scoreProspective(manifest, decisions, usable + excluded)
    }

    /**
     * Re-validates one persisted decision artifact: R is
     * re-derived from manifested evidence; M0/M1 reply
     * payloads re-validate against the study contract.
     * Mock-synthetic decisions can never score a
     * qualified study.
     */
    @Suppress("UNCHECKED_CAST")
    fun revalidateDecision(
        decisionJson: String,
        manifest: StudyManifest
    ): StudyDecision {
        val record = try {
            SessionJson.parse(decisionJson) as? Map<String, Any?>
        } catch (_: SessionJson.JsonError) {
            throw StudyError("STUDY_SCORE",
                "decision artifact is not valid JSON")
        } ?: throw StudyError("STUDY_SCORE",
            "decision artifact is not an object")
        if (record["study_id"] != STUDY_ID) {
            throw StudyError("STUDY_SCORE",
                "decision is not for $STUDY_ID")
        }
        val arm = record["arm"] as? String
        if (arm != EvalArms.ARM_R && arm != EvalArms.ARM_M0 &&
            arm != EvalArms.ARM_M1
        ) {
            throw StudyError("STUDY_SCORE", "decision arm $arm unknown")
        }
        val transport = record["transport"] as? String
        if (transport == TRANSPORT_MOCK) {
            throw StudyError("STUDY_SCORE",
                "$arm decision is mock-synthetic; qualified " +
                    "scoring needs the live record")
        }
        val selection = record["selection"] as? String
        if (arm == EvalArms.ARM_R) {
            if (transport != TRANSPORT_OFFLINE) {
                throw StudyError("STUDY_SCORE",
                    "R decision must be offline")
            }
            if (selection != rederiveRSelection(manifest)) {
                throw StudyError("STUDY_SCORE",
                    "R selection $selection != re-derived " +
                        "from manifested evidence")
            }
            if ((record["decided_at_utc"] as? String) !=
                manifest.frozenAtUtc
            ) {
                throw StudyError("STUDY_SCORE",
                    "R decided_at_utc != manifest frozen_at_utc")
            }
            return StudyDecision(
                arm = arm,
                selection = selection!!,
                selectedConfig = when (selection) {
                    SELECT_A -> manifest.candidateA
                    SELECT_B -> manifest.candidateB
                    else -> null
                },
                abstainReason = record["abstain_reason"] as? String,
                expectedGenTps =
                    (record["expected_gen_tps"] as? Number)?.toDouble(),
                expectedPrefillTps =
                    (record["expected_prefill_tps"] as? Number)?.toDouble(),
                uncertainty = record["uncertainty"] as? String,
                supportingEvidenceIds = emptyList(),
                limitations = emptyList(),
                rawJson = record["raw_json"] as? String ?: "{}",
                decidedAtUtc = manifest.frozenAtUtc,
                transport = TRANSPORT_OFFLINE
            )
        }
        if (transport != TRANSPORT_LIVE) {
            throw StudyError("STUDY_SCORE",
                "$arm decision must be a live record")
        }
        val raw = record["raw_json"] as? String
            ?: throw StudyError("STUDY_SCORE",
                "$arm decision raw_json missing")
        val payload = try {
            SessionJson.parse(raw) as? Map<String, Any?>
        } catch (_: SessionJson.JsonError) {
            throw StudyError("STUDY_SCORE",
                "$arm raw_json is not valid JSON")
        } ?: throw StudyError("STUDY_SCORE",
            "$arm raw_json is not an object")
        val expectation = StudyExpectation(
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
        val decidedAt = record["decided_at_utc"] as? String
            ?: throw StudyError("STUDY_SCORE",
                "$arm decided_at_utc missing")
        val revalidated = try {
            validateStudyDecision(payload, raw, expectation, arm,
                decidedAt, TRANSPORT_LIVE)
        } catch (e: StudyError) {
            throw StudyError("STUDY_SCORE",
                "$arm decision fails contract: [${e.code}] ${e.message}")
        }
        if (revalidated.selection != selection) {
            throw StudyError("STUDY_SCORE",
                "$arm recorded selection $selection != " +
                    "re-validated ${revalidated.selection}")
        }
        val citationProblems = verifyArmCitations(revalidated, manifest)
        if (citationProblems.isNotEmpty()) {
            throw StudyError("STUDY_SCORE",
                citationProblems.joinToString("; "))
        }
        return revalidated.copy(
            providerRequestId = record["provider_request_id"] as? String,
            promptTokens = (record["prompt_tokens"] as? Number)?.toInt(),
            completionTokens =
                (record["completion_tokens"] as? Number)?.toInt(),
            latencyMs = (record["latency_ms"] as? Number)?.toLong(),
            costUsd = (record["cost_usd"] as? Number)?.toDouble()
        )
    }

    /**
     * Source-backed run ingestion. Returns usable runs
     * plus excluded (non-COMPLETED) runs for reason
     * reporting. THROWS on anything inconsistent: corrupt
     * bytes, wrong contract, identity mismatch,
     * unregistered config, unusable measurements,
     * duplicate run ids, timestamp inversion vs ANY
     * decision, or collected/START skew. Scoring never
     * sees unverified values.
     */
    fun ingestStudyRuns(
        manifest: StudyManifest,
        decisions: List<StudyDecision>,
        inputs: List<StudyRunInput>
    ): Pair<List<NewRun>, List<NewRun>> {
        val all = mutableListOf<NewRun>()
        val excluded = mutableListOf<NewRun>()
        for (input in inputs) {
            all.add(verifyStudyRun(input, manifest, decisions, excluded))
        }
        // Dup ids across the whole offer block scoring.
        val dup = all.map { it.runId }.groupingBy { it }.eachCount()
            .filter { it.value > 1 }.keys.firstOrNull()
        if (dup != null) {
            throw StudyError("STUDY_RUNS", "duplicate run id $dup")
        }
        return all.filter {
            it.status == BenchContract.STATUS_COMPLETED
        } to excluded
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyStudyRun(
        input: StudyRunInput,
        manifest: StudyManifest,
        decisions: List<StudyDecision>,
        excluded: MutableList<NewRun>
    ): NewRun {
        val sha = BenchConfig.sha256Hex(input.bytes)
        val root = try {
            SessionJson.parse(input.bytes.toString(Charsets.UTF_8))
                as? Map<String, Any?>
        } catch (_: SessionJson.JsonError) {
            throw StudyError("STUDY_RUNS",
                "${input.label}: bytes are not valid JSON")
        } ?: throw StudyError("STUDY_RUNS",
            "${input.label}: artifact is not an object")
        if (root["contract"] != BenchContract.CONTRACT ||
            (root["contract_version"] as? Number)?.toInt() !=
            BenchContract.CONTRACT_VERSION
        ) {
            throw StudyError("STUDY_RUNS",
                "${input.label}: not ${BenchContract.CONTRACT} v" +
                    BenchContract.CONTRACT_VERSION)
        }
        val c = manifest.constraints
        val runId = root["run_id"] as? String
        if (runId.isNullOrBlank()) {
            throw StudyError("STUDY_RUNS", "${input.label}: run id missing")
        }
        val model = root["model"] as? Map<String, Any?>
        if ((model?.get("sha256") as? String)?.lowercase() !=
            c.modelSha256.lowercase()
        ) {
            throw StudyError("STUDY_RUNS", "$runId: model SHA-256 mismatch")
        }
        val workload = root["workload"] as? Map<String, Any?>
        if (workload?.get("id") != c.workloadId ||
            (workload["prompt_sha256"] as? String)?.lowercase() !=
            c.promptSha256.lowercase()
        ) {
            throw StudyError("STUDY_RUNS",
                "$runId: workload/prompt mismatch")
        }
        val runtime = root["runtime"] as? Map<String, Any?>
        if (runtime?.get("impl") != BenchContract.RUNTIME_IMPL ||
            runtime["backend"] != BenchContract.BACKEND ||
            runtime["version"] != c.runtimeVersion ||
            runtime["abi"] != c.runtimeAbi
        ) {
            throw StudyError("STUDY_RUNS",
                "$runId: runtime identity mismatch")
        }
        val variant = (root["x_native_build"] as? Map<String, Any?>)
            ?.get("variant") as? String
        if (variant != c.nativeVariant) {
            throw StudyError("STUDY_RUNS",
                "$runId: native variant $variant, expected " +
                    c.nativeVariant)
        }
        val config = SessionBenchBridge.configData(
            (root["config"] as? Map<String, Any?>)
                ?: throw StudyError("STUDY_RUNS",
                    "$runId: config block missing"))
            ?: throw StudyError("STUDY_RUNS",
                "$runId: config unparsable or out of bounds")
        if (tripleOf(config) != tripleOf(manifest.candidateA) &&
            tripleOf(config) != tripleOf(manifest.candidateB)
        ) {
            throw StudyError("STUDY_RUNS",
                "$runId: config outside frozen {A, B}")
        }
        val status = root["status"] as? String
        if (status != BenchContract.STATUS_COMPLETED) {
            // Legitimate exported outcome (FAILED/TIMEOUT/
            // CANCELLED): same identity + temporal checks,
            // then excluded with its reason. Measurements
            // are absent by definition (NaN), never scored.
            val failed = NewRun(
                runId = runId, artifactSha256 = sha, config = config,
                genTps = Double.NaN, prefillTps = Double.NaN,
                thermalAfter = "unknown",
                collectedAtUtc = (root["collected_at_utc"] as? String)
                    .orEmpty(),
                status = status ?: "UNKNOWN")
            checkRunTiming(failed, manifest, decisions)
            excluded.add(failed)
            return failed
        }
        val measurements = root["measurements"] as? Map<String, Any?>
            ?: throw StudyError("STUDY_RUNS",
                "$runId: measurements block missing")
        val gen = (measurements["tps_gen"] as? Number)?.toDouble()
        val prefill = (measurements["tps_prefill"] as? Number)?.toDouble()
        if (gen == null || !gen.isFinite() || gen <= 0 ||
            prefill == null || !prefill.isFinite() || prefill <= 0
        ) {
            throw StudyError("STUDY_RUNS",
                "$runId: COMPLETED run lacks usable throughput")
        }
        val thermalAfter = (measurements["thermal"] as? Map<String, Any?>)
            ?.get("after") as? String
        if (thermalAfter.isNullOrBlank()) {
            throw StudyError("STUDY_RUNS",
                "$runId: terminal thermal state missing")
        }
        val collected = root["collected_at_utc"] as? String
        if (collected.isNullOrBlank()) {
            throw StudyError("STUDY_RUNS",
                "$runId: collection timestamp missing")
        }
        val run = NewRun(
            runId = runId, artifactSha256 = sha, config = config,
            genTps = gen, prefillTps = prefill,
            thermalAfter = thermalAfter, collectedAtUtc = collected,
            status = BenchContract.STATUS_COMPLETED)
        checkRunTiming(run, manifest, decisions)
        return run
    }

    /**
     * Temporal gate inside ingestion (not a separate
     * optional check): START must postdate the freeze AND
     * every arm decision, strictly; collected must agree
     * with START within 120s.
     */
    private fun checkRunTiming(
        run: NewRun,
        manifest: StudyManifest,
        decisions: List<StudyDecision>
    ) {
        val start = runStartUtc(run.runId)
            ?: throw StudyError("STUDY_RUNS",
                "${run.runId}: unverifiable START stamp")
        if (!(manifest.frozenAtUtc < start)) {
            throw StudyError("STUDY_RUNS",
                "${run.runId}: START $start does not postdate " +
                    "freeze ${manifest.frozenAtUtc}")
        }
        for (decision in decisions) {
            if (!(decision.decidedAtUtc < start)) {
                throw StudyError("STUDY_RUNS",
                    "${run.runId}: ${decision.arm} decision at " +
                        "${decision.decidedAtUtc} does not predate " +
                        "START $start")
            }
        }
        if (!withinSeconds(run.collectedAtUtc, start, 120)) {
            throw StudyError("STUDY_RUNS",
                "${run.runId}: collected_at_utc " +
                    "${run.collectedAtUtc} inconsistent with " +
                    "START stamp $start")
        }
    }

    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /**
     * Serializes a [StudyScore] for the persisted
     * study-score.json artifact (written once by the
     * owner-executed scorer edge).
     */
    fun scoreJson(score: StudyScore, scoredAtUtc: String): String {
        fun metric(m: EvalMetrics.MetricValue): Map<String, Any?> =
            linkedMapOf(
                "value" to m.value,
                "status" to m.status,
                "reason" to m.reason,
                "n" to m.n)
        fun summary(s: ConfigSummary): Map<String, Any?> = linkedMapOf(
            "median_gen" to metric(s.medianGen),
            "min_gen" to s.minGen,
            "max_gen" to s.maxGen,
            "n" to s.n)
        fun report(r: EvalMetrics.EvalReport): Map<String, Any?> =
            linkedMapOf(
                "arm" to r.arm,
                "n" to r.n,
                "valid_proposal_rate" to metric(r.validProposalRate),
                "invalid_proposal_rate" to metric(r.invalidProposalRate),
                "host_rejection_rate" to metric(r.hostRejectionRate),
                "host_rejection" to linkedMapOf(
                    "total_invalid" to r.hostRejection.totalInvalid,
                    "verified_rejected" to
                        r.hostRejection.verifiedRejected,
                    "verified_non_rejected" to
                        r.hostRejection.verifiedNonRejected,
                    "unknown" to r.hostRejection.unknown,
                    "complete_coverage" to
                        r.hostRejection.completeCoverage),
                "fabricated_citation_rate" to
                    metric(r.fabricatedCitationRate),
                "abstention_rate" to metric(r.abstentionRate),
                "appropriate_abstention_rate" to
                    metric(r.appropriateAbstentionRate),
                "mean_signed_error_tps" to
                    metric(r.meanSignedErrorTps),
                "mean_abs_error_tps" to metric(r.meanAbsErrorTps),
                "mean_selection_regret_tps" to
                    metric(r.meanSelectionRegretTps),
                "observed_throughputs" to r.observedThroughputs,
                "mean_latency_ms" to metric(r.meanLatencyMs),
                "total_cost_usd" to metric(r.totalCostUsd),
                "limitations" to r.limitations)
        return SessionJson.stringify(linkedMapOf(
            "study_id" to STUDY_ID,
            "scored_at_utc" to scoredAtUtc,
            "candidate_a" to summary(score.candidateA),
            "candidate_b" to summary(score.candidateB),
            "decision_reports" to score.decisionReports.mapValues {
                report(it.value)
            },
            "error_reports" to score.errorReports.mapValues {
                report(it.value)
            },
            "regret_by_arm" to score.regretByArm.mapValues { metric(it.value) },
            "excluded_runs" to score.excludedRuns,
            "labels" to score.labels
        ))
    }

    // ---- FINAL Task 2: nondestructive freeze verification ----

    /**
     * Contents of a frozen study directory, read (never
     * written) for verification.
     */
    data class FrozenFileSet(
        val manifestJson: String,
        val decisionRJson: String,
        val requestM0Json: String,
        val requestM1Json: String,
        val verdictJson: String,
        val promptsText: String? = null
    )

    /**
     * Read-only verification of frozen artifacts. Re-parses
     * the manifest (hash-checked), re-derives the R
     * decision from the manifested evidence and compares
     * selections, re-hashes both request bodies, checks
     * endpoint/model/candidate identity, enforces M0
     * measurement-freedom, and (when [evidenceBytes] maps
     * run id -> file bytes) re-hashes the evidence files
     * against the admitted artifact SHAs. Returns every
     * problem found; empty means the freeze is intact.
     * Writes nothing, regenerates nothing.
     */
    @Suppress("UNCHECKED_CAST")
    fun verifyFrozenStudy(
        files: FrozenFileSet,
        evidenceBytes: Map<String, ByteArray>? = null
    ): List<String> {
        val problems = mutableListOf<String>()
        val manifest = try {
            parseManifest(files.manifestJson)
        } catch (e: StudyError) {
            return listOf("manifest: [${e.code}] ${e.message}")
        }
        // Verdict artifact hashes vs recomputed content.
        try {
            val verdict = SessionJson.parse(files.verdictJson)
                as? Map<String, Any?>
            val recorded = verdict?.get("artifacts") as? Map<String, Any?>
            if (recorded == null) {
                problems.add("verdict: artifacts map missing")
            } else {
                val contents = mapOf(
                    "study-manifest.json" to files.manifestJson,
                    "study-decision-r.json" to files.decisionRJson,
                    "study-request-m0.json" to files.requestM0Json,
                    "study-request-m1.json" to files.requestM1Json
                ) + (if (files.promptsText != null) {
                    mapOf("study-prompts.txt" to files.promptsText)
                } else {
                    emptyMap()
                })
                for ((name, content) in contents) {
                    val expected = recorded[name] as? String
                    if (expected == null) {
                        problems.add("verdict: no recorded hash for $name")
                    } else if (!BenchConfig.sha256Hex(
                            content.toByteArray(Charsets.UTF_8))
                            .equals(expected, ignoreCase = true)
                    ) {
                        problems.add("verdict: $name changed since freeze")
                    }
                }
            }
        } catch (_: SessionJson.JsonError) {
            problems.add("verdict: not valid JSON")
        }
        // R decision: structural + re-derived selection.
        try {
            val r = SessionJson.parse(files.decisionRJson)
                as? Map<String, Any?>
            if (r == null || r["arm"] != EvalArms.ARM_R ||
                r["transport"] != TRANSPORT_OFFLINE
            ) {
                problems.add("decision-r: not an offline R record")
            } else {
                val recorded = r["selection"] as? String
                val rederived = rederiveRSelection(manifest)
                if (recorded != rederived) {
                    problems.add("decision-r: recorded selection " +
                        "$recorded != re-derived $rederived")
                }
                if ((r["decided_at_utc"] as? String) !=
                    manifest.frozenAtUtc
                ) {
                    problems.add("decision-r: decided_at_utc != " +
                        "manifest frozen_at_utc")
                }
            }
        } catch (_: SessionJson.JsonError) {
            problems.add("decision-r: not valid JSON")
        }
        // Request bodies: hash, endpoint, model, study binding.
        for ((arm, content) in listOf(
            EvalArms.ARM_M0 to files.requestM0Json,
            EvalArms.ARM_M1 to files.requestM1Json
        )) {
            try {
                val req = SessionJson.parse(content)
                    as? Map<String, Any?>
                if (req == null) {
                    problems.add("request-$arm: not a JSON object")
                    continue
                }
                val body = req["body"] as? String
                if (body == null) {
                    problems.add("request-$arm: body missing")
                    continue
                }
                val sha = req["body_sha256"] as? String
                if (sha == null || !BenchConfig.sha256Hex(
                        body.toByteArray(Charsets.UTF_8))
                        .equals(sha, ignoreCase = true)
                ) {
                    problems.add("request-$arm: body hash mismatch")
                }
                if (req["endpoint"] != MetaApi.ENDPOINT) {
                    problems.add("request-$arm: endpoint mismatch")
                }
                if (req["model"] != MetaApi.MODEL) {
                    problems.add("request-$arm: model mismatch")
                }
                if (!body.contains(manifest.manifestHash) ||
                    !body.contains(manifest.candidateHash)
                ) {
                    problems.add("request-$arm: not bound to this " +
                        "manifest/candidate set")
                }
            } catch (_: SessionJson.JsonError) {
                problems.add("request-$arm: not valid JSON")
            }
        }
        // M0/M1 evidence separation on the frozen bodies.
        try {
            val m0body = ((SessionJson.parse(files.requestM0Json)
                as? Map<String, Any?>)?.get("body") as? String).orEmpty()
            val m1body = ((SessionJson.parse(files.requestM1Json)
                as? Map<String, Any?>)?.get("body") as? String).orEmpty()
            problems.addAll(verifyM0Clean(m0body, manifest))
            for (e in manifest.admitted) {
                if (!m1body.contains(e.runId)) {
                    problems.add("request-M1: admitted run ${e.runId} " +
                        "missing from payload")
                }
            }
        } catch (_: SessionJson.JsonError) {
            // Already reported above; no duplicate.
        }
        // Evidence bytes vs admitted SHAs (when supplied).
        if (evidenceBytes != null) {
            for (e in manifest.admitted) {
                val bytes = evidenceBytes[e.runId]
                if (bytes == null) {
                    problems.add("evidence: no bytes for ${e.runId}")
                } else if (!BenchConfig.sha256Hex(bytes)
                        .equals(e.artifactSha256, ignoreCase = true)
                ) {
                    problems.add("evidence: ${e.runId} bytes changed " +
                        "since freeze")
                }
            }
        }
        return problems
    }

    /**
     * M0 contamination sweep: the frozen M0 body must name
     * no admitted run id and carry no admitted throughput
     * figure. Returns problems (empty = clean).
     */
    fun verifyM0Clean(body: String, manifest: StudyManifest): List<String> {
        val problems = mutableListOf<String>()
        for (e in manifest.admitted) {
            if (body.contains(e.runId)) {
                problems.add("request-M0: leaks run id ${e.runId}")
            }
            // Throughput figures render via Double.toString
            // both in packs and here, so exact matching is
            // sound (no rounding layer in between). Guards
            // stop "7.0" matching inside "0.7.0-dev".
            for ((label, fig) in listOf(
                "gen_tps" to e.genTps.toString(),
                "prefill_tps" to e.prefillTps.toString()
            )) {
                val guarded = Regex("(?<![0-9.])" +
                    Regex.escape(fig) + "(?![0-9.])")
                if (guarded.containsMatchIn(body)) {
                    problems.add("request-M0: leaks $label $fig")
                }
            }
        }
        if (!body.contains("withheld")) {
            problems.add("request-M0: withholding notice missing")
        }
        return problems
    }

    /**
     * Output-directory guard for new freezes. A directory
     * holding a study-verdict.json is an existing study
     * and is NEVER written into. Any other non-empty
     * directory needs an explicit new-instance request
     * (unique instance id); a new freeze is a new study,
     * not a continuation. Returns the problem, or null
     * when writing is allowed.
     */
    fun checkOutputDir(
        dir: java.io.File,
        newInstance: Boolean,
        instanceId: String?
    ): String? {
        if (java.io.File(dir, "study-verdict.json").isFile) {
            return "output holds study-verdict.json: an existing " +
                "study is immutable; choose a new directory"
        }
        val existing = dir.isDirectory &&
            (dir.list()?.isNotEmpty() == true)
        if (existing && !newInstance) {
            return "output directory is not empty; set " +
                "EF_STUDY_NEW_INSTANCE=YES with EF_STUDY_INSTANCE_ID " +
                "to start a new study there"
        }
        if (newInstance && instanceId.isNullOrBlank()) {
            return "EF_STUDY_NEW_INSTANCE=YES needs " +
                "EF_STUDY_INSTANCE_ID"
        }
        return null
    }

    /**
     * Re-derives the R selection from manifested evidence
     * (same code path as preflight). Used to detect
     * altered decision artifacts.
     */
    fun rederiveRSelection(manifest: StudyManifest): String {
        val evidence = manifest.admitted.groupBy {
            Triple(it.config.nCtx, it.config.nThreads, it.config.nPredict)
        }.map { (_, records) ->
            EvalBaseline.CandidateEvidence(
                config = records.first().config,
                genSamples = records.map { it.genTps },
                elevatedThermal = records.count {
                    it.thermalAfter != "none"
                },
                cohortKey = manifest.manifestHash
            )
        }
        val r = EvalBaseline.decide(
            listOf(manifest.candidateA, manifest.candidateB),
            evidence, manifest.constraints.deviceMaxThreads)
        return when {
            r.abstained -> SELECT_ABSTAIN
            r.selected == manifest.candidateA -> SELECT_A
            r.selected == manifest.candidateB -> SELECT_B
            else -> "OUT_OF_SET"
        }
    }
}
