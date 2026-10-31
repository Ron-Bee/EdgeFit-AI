package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EF-COMP-01B-PREP: owner-executable file edge for the
 * offline study preflight. The study logic is pure
 * ([EvalStudy.runPreflight]); this runner only moves
 * bytes between the corpus, the freeze, and the output
 * directory. No build.gradle changes: it runs as a unit
 * test because that is the existing runnable JVM
 * surface for this module.
 *
 * Env wiring (all optional; without EF_STUDY_OUT the
 * test self-checks on synthetic fixtures in a temp
 * dir and asserts the same invariants):
 * - EF_STUDY_OUT: output directory for the six study
 *   artifacts (created if missing). REFUSES to write
 *   into a directory holding a study-verdict.json.
 * - EF_STUDY_NEW_INSTANCE=YES + EF_STUDY_INSTANCE_ID:
 *   required to freeze into any other non-empty
 *   directory; a new freeze is a new study.
 * - EF_STUDY_VERIFY_DIR: read-only verification of an
 *   existing frozen study (no writes anywhere).
 * - EF_STUDY_SCORE_DIR + EF_STUDY_RUNS: qualified
 *   offline scoring. EF_STUDY_RUNS is a comma-separated
 *   list of exactly 6 exported run JSON paths.
 *   Writes study-score.json ONCE into the study dir
 *   (refuses if present).
 * - EF_STUDY_CORPUS: corpus manifest path (default:
 *   eval/comp-01a-corpus.json found upward from the
 *   working directory).
 * - EDGEFIT_EVIDENCE: evidence root for
 *   ${EDGEFIT_EVIDENCE} corpus paths.
 * - EF_STUDY_IDS: comma-separated authorized corpus
 *   ids (default: the protocol §3 B/C/D set).
 *
 * Writes: study-manifest.json, study-decision-r.json,
 * study-request-m0.json, study-request-m1.json,
 * study-prompts.txt, study-verdict.json. No live
 * calls, no sessions, no database writes.
 */
class EvalStudyRunnerTest {

    @Test
    fun ownerPreflight() {
        val verifyDir = System.getenv("EF_STUDY_VERIFY_DIR")?.ifBlank { null }
        if (verifyDir != null) {
            verifyOnly(verifyDir)
            return
        }
        val scoreDir = System.getenv("EF_STUDY_SCORE_DIR")?.ifBlank { null }
        if (scoreDir != null) {
            scoreOnly(scoreDir)
            return
        }
        val outDir = System.getenv("EF_STUDY_OUT")?.ifBlank { null }
        if (outDir == null) {
            selfCheck()
            return
        }
        val dir = File(outDir)
        val guard = EvalStudy.checkOutputDir(
            dir,
            System.getenv("EF_STUDY_NEW_INSTANCE") == "YES",
            System.getenv("EF_STUDY_INSTANCE_ID")?.ifBlank { null })
        if (guard != null) throw AssertionError(guard)
        val corpusPath = System.getenv("EF_STUDY_CORPUS")?.ifBlank { null }
            ?: findUp("eval/comp-01a-corpus.json")
            ?: throw AssertionError(
                "EF_STUDY_CORPUS unset and eval/comp-01a-corpus.json " +
                    "not found upward from ${File(".").absolutePath}")
        val evidenceRoot = System.getenv("EDGEFIT_EVIDENCE")?.ifBlank { null }
            ?: throw AssertionError(
                "EDGEFIT_EVIDENCE must name the evidence directory")
        val ids = (System.getenv("EF_STUDY_IDS")?.ifBlank { null }
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: EvalStudy.STUDY_AUTHORIZED_IDS)
        val artifacts = loadCorpusArtifacts(corpusPath, evidenceRoot, ids)
        val now = SessionManager.utcNow()
        val out = EvalStudy.runPreflight(EvalStudy.PreflightInput(
            constraints = EvalStudy.studyConstraints(),
            candidateA = EvalStudy.studyCandidateA(),
            candidateB = EvalStudy.studyCandidateB(),
            artifacts = artifacts,
            evidenceCutoffUtc = now,
            nowUtc = now
        ))
        dir.mkdirs()
        val written = linkedMapOf<String, String>()
        fun write(name: String, content: String?) {
            if (content == null) return
            val file = File(dir, name)
            file.writeText(content, Charsets.UTF_8)
            written[name] = BenchConfig.sha256Hex(
                content.toByteArray(Charsets.UTF_8))
        }
        write("study-manifest.json", out.manifestJson)
        write("study-decision-r.json", out.decisionRJson)
        write("study-request-m0.json", out.requestM0Json)
        write("study-request-m1.json", out.requestM1Json)
        write("study-prompts.txt", out.promptsText)
        val verdict = SessionJson.stringify(linkedMapOf(
            "study_id" to EvalStudy.STUDY_ID,
            "instance_id" to
                (System.getenv("EF_STUDY_INSTANCE_ID")?.ifBlank { null }
                    ?: "original"),
            "verdict" to out.verdict,
            "decided_at_utc" to now,
            "problems" to out.problems,
            "artifacts" to written,
            "note" to "READY means the freeze + R decision + " +
                "M0/M1 payloads are persisted and the study may " +
                "proceed to owner-authorized live calls. M0/M1 " +
                "decisions are NOT made in PREP."
        ))
        File(dir, "study-verdict.json")
            .writeText(verdict, Charsets.UTF_8)
        assertEquals(
            "preflight BLOCKED: ${out.problems.joinToString(" | ")}",
            EvalStudy.VERDICT_READY, out.verdict)
    }

    /**
     * Read-only verification of an existing frozen
     * study. Writes nothing; fails on any problem. When
     * EDGEFIT_EVIDENCE is set, evidence bytes re-hash
     * against the admitted artifact SHAs too.
     */
    private fun verifyOnly(studyDir: String) {
        val dir = File(studyDir)
        fun read(name: String): String {
            val file = File(dir, name)
            if (!file.isFile) throw AssertionError("missing $name")
            return file.readText(Charsets.UTF_8)
        }
        val files = EvalStudy.FrozenFileSet(
            manifestJson = read("study-manifest.json"),
            decisionRJson = read("study-decision-r.json"),
            requestM0Json = read("study-request-m0.json"),
            requestM1Json = read("study-request-m1.json"),
            verdictJson = read("study-verdict.json"),
            promptsText = File(dir, "study-prompts.txt")
                .takeIf { it.isFile }?.readText(Charsets.UTF_8)
        )
        var evidence: Map<String, ByteArray>? = null
        val root = System.getenv("EDGEFIT_EVIDENCE")?.ifBlank { null }
        if (root != null) {
            val corpusPath = System.getenv("EF_STUDY_CORPUS")
                ?.ifBlank { null }
                ?: findUp("eval/comp-01a-corpus.json")
                ?: throw AssertionError("corpus not found")
            val manifest = EvalStudy.parseManifest(files.manifestJson)
            val artifacts = loadCorpusArtifacts(corpusPath, root,
                manifest.admitted.map { it.corpusId })
            evidence = artifacts.mapNotNull { artifact ->
                val runId = try {
                    @Suppress("UNCHECKED_CAST")
                    (SessionJson.parse(
                        artifact.bytes.toString(Charsets.UTF_8))
                        as? Map<String, Any?>)?.get("run_id") as? String
                } catch (_: SessionJson.JsonError) {
                    null
                }
                if (runId == null) null else runId to artifact.bytes
            }.toMap()
        }
        val problems = EvalStudy.verifyFrozenStudy(files, evidence)
        assertTrue(
            "frozen study problems: ${problems.joinToString(" | ")}",
            problems.isEmpty())
    }

    /**
     * Qualified offline scoring over six exported run
     * files. Writes study-score.json exactly once;
     * refuses when it already exists (scores are
     * immutable: re-running needs a new study dir).
     */
    private fun scoreOnly(studyDir: String) {
        val dir = File(studyDir)
        fun read(name: String): String {
            val file = File(dir, name)
            if (!file.isFile) throw AssertionError("missing $name")
            return file.readText(Charsets.UTF_8)
        }
        val scoreFile = File(dir, "study-score.json")
        if (scoreFile.isFile) {
            throw AssertionError(
                "study-score.json already exists; scores are immutable")
        }
        val runs = (System.getenv("EF_STUDY_RUNS")?.ifBlank { null }
            ?: throw AssertionError(
                "EF_STUDY_RUNS must list 6 exported run files"))
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (runs.size != 6) {
            throw AssertionError(
                "EF_STUDY_RUNS must list exactly 6 files, got ${runs.size}")
        }
        val inputs = runs.mapIndexed { i, path ->
            val file = File(path)
            if (!file.isFile) {
                throw AssertionError("run file missing: $path")
            }
            EvalStudy.StudyRunInput("run-$i", file.readBytes())
        }
        val score = EvalStudy.scoreStudy(
            read("study-manifest.json"),
            listOf(
                read("study-decision-r.json"),
                read("study-decision-m0.json"),
                read("study-decision-m1.json")),
            inputs)
        scoreFile.writeText(
            EvalStudy.scoreJson(score, SessionManager.utcNow()),
            Charsets.UTF_8)
        assertTrue(score.labels.any { it.contains("exploratory") })
    }

    /** Same assertions on synthetic bytes; runs in every suite pass. */
    private fun selfCheck() {
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
        // Every artifact must re-parse (the owner run
        // writes these exact strings to disk).
        EvalStudy.parseManifest(out.manifestJson!!)
        SessionJson.parse(out.decisionRJson!!)
        SessionJson.parse(out.requestM0Json!!)
        SessionJson.parse(out.requestM1Json!!)
        assertTrue(out.promptsText!!.contains("CANDIDATE_B"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadCorpusArtifacts(
        corpusPath: String,
        evidenceRoot: String,
        ids: List<String>
    ): List<EvalStudy.ArtifactInput> {
        val corpus = try {
            SessionJson.parse(File(corpusPath).readText(Charsets.UTF_8))
                as? Map<String, Any?>
        } catch (e: Exception) {
            throw AssertionError("cannot parse corpus $corpusPath: $e")
        } ?: throw AssertionError("corpus $corpusPath is not an object")
        val entries = (corpus["entries"] as? List<*>)
            ?: throw AssertionError("corpus has no entries array")
        return ids.map { id ->
            val entry = entries.mapNotNull { it as? Map<String, Any?> }
                .firstOrNull { it["id"] == id }
                ?: throw AssertionError("corpus id $id not found")
            if (entry["role"] != "measurement") {
                throw AssertionError("corpus id $id is not a measurement")
            }
            val raw = entry["path"] as? String
                ?: throw AssertionError("corpus id $id has no path")
            val resolved = if (raw.startsWith("\${EDGEFIT_EVIDENCE}/")) {
                File(evidenceRoot,
                    raw.removePrefix("\${EDGEFIT_EVIDENCE}/")).path
            } else {
                throw AssertionError(
                    "corpus id $id path must use \${EDGEFIT_EVIDENCE}/")
            }
            val file = File(resolved)
            if (!file.isFile) {
                throw AssertionError("evidence file missing: $resolved")
            }
            EvalStudy.ArtifactInput(
                corpusId = id,
                expectedSha256 = entry["sha256"] as? String
                    ?: throw AssertionError(
                        "corpus id $id has no sha256"),
                bytes = file.readBytes()
            )
        }
    }

    private fun findUp(relative: String): String? {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.path
            dir = dir?.parentFile ?: return null
        }
        return null
    }
}
