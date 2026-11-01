package ai.edgefit.diag

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable app-private repository for complete benchmark evidence
 * (EF-GLOBAL-CONTRIBUTE-01). Each run keeps its ORIGINAL serialized
 * bytes plus a small contribution sidecar; nothing depends on the
 * Activity staying alive.
 *
 * Layout under [dir]/evidence/:
 * - `<runId>.bench.json` — exact source bytes (never rewritten with
 *   different content under the same id).
 * - `<runId>.meta.json` — sha, provenance, contribution state,
 *   receipt, consent reference (atomic tmp+fsync+rename writes,
 *   mirroring [SessionStore]).
 */
class EvidenceStore(rootDir: File) {
    class StoreError(message: String) : Exception(message)
    class NotFound(val id: String) : Exception("no evidence: $id")
    class Conflict(id: String) :
        Exception("run id already stored with different bytes: $id")
    class BadId(val id: String) : Exception("bad run id: $id")

    companion object {
        const val PROVENANCE_CAPTURED = "captured"
        const val PROVENANCE_IMPORTED = "imported"
        const val MAX_EVIDENCE_BYTES = 128 * 1024
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:\\-]{0,127}")
    }

    data class Record(
        val summary: BenchResultParse.Summary,
        /** Exact original document bytes. */
        val bytes: ByteArray,
        val provenance: String,
        val storedAtUtc: String,
        val contributionState: String,
        val submissionId: String?,
        val serverEligibility: String?,
        val lastStatusAtUtc: String?,
        val consentPolicyVersion: String?,
        val consentAcceptedAtUtc: String?
    )

    private val dir = File(rootDir, "evidence").apply { mkdirs() }

    fun isValidId(id: String): Boolean = ID.matches(id)

    fun listIds(): List<String> =
        (dir.listFiles { file ->
            file.name.endsWith(".bench.json") &&
                isValidId(file.name.removeSuffix(".bench.json"))
        } ?: emptyArray()).map { it.name.removeSuffix(".bench.json") }
            .sorted()

    fun exists(id: String): Boolean =
        isValidId(id) && File(dir, "$id.bench.json").isFile

    /**
     * Deletes ONE local result (both files). Cloud
     * contributions are NOT withdrawn: callers must
     * say so explicitly. Returns true if a record
     * existed. Invalid ids throw [BadId].
     */
    @Synchronized
    fun delete(id: String): Boolean {
        if (!isValidId(id)) throw BadId(id)
        val bench = File(dir, "$id.bench.json")
        val meta = File(dir, "$id.meta.json")
        val had = bench.isFile || meta.isFile
        bench.delete()
        meta.delete()
        return had
    }

    /**
     * Stores [text] (exact bytes preserved) after validation. Returns
     * the record; identical re-store returns the existing record;
     * different bytes under the same id throw [Conflict].
     */
    @Synchronized
    fun store(
        text: String,
        provenance: String,
        nowMs: Long
    ): Record {
        if (provenance != PROVENANCE_CAPTURED &&
            provenance != PROVENANCE_IMPORTED
        ) throw StoreError("unknown provenance: $provenance")
        val parsed = try {
            BenchResultParse.parse(text, nowMs, MAX_EVIDENCE_BYTES)
        } catch (e: BenchResultParse.InvalidResult) {
            throw StoreError("invalid benchmark JSON: ${e.message}")
        }
        val id = parsed.summary.runId
        val bytes = text.toByteArray(Charsets.UTF_8)
        val target = File(dir, "$id.bench.json")
        if (target.isFile) {
            val prior = target.readBytes()
            if (!prior.contentEquals(bytes)) throw Conflict(id)
            return load(id)
        }
        atomicWrite(target, bytes, id)
        val meta = linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "run_id" to id,
            "sha256" to parsed.summary.sha256,
            "provenance" to provenance,
            "stored_at_utc" to BenchResultParse.formatIsoUtc(nowMs),
            "contribution_state" to GlobalContract.LOCAL_NOT_SHARED,
            "submission_id" to null,
            "server_eligibility" to null,
            "last_status_at_utc" to null,
            "consent_policy_version" to null,
            "consent_accepted_at_utc" to null
        )
        atomicWrite(
            File(dir, "$id.meta.json"),
            SessionJson.stringify(meta).toByteArray(Charsets.UTF_8), id)
        return load(id)
    }

    @Synchronized
    fun load(id: String): Record {
        if (!isValidId(id)) throw BadId(id)
        val bench = File(dir, "$id.bench.json")
        val metaFile = File(dir, "$id.meta.json")
        if (!bench.isFile || !metaFile.isFile) throw NotFound(id)
        if (bench.length() > MAX_EVIDENCE_BYTES) {
            throw StoreError("evidence oversize: $id")
        }
        val bytes = bench.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        val parsed = try {
            // Stored records were valid at capture; re-validate
            // structurally with a generous clock (timestamps in
            // the past stay valid; future-tolerance unchanged).
            BenchResultParse.parse(
                text, System.currentTimeMillis(), MAX_EVIDENCE_BYTES)
        } catch (e: BenchResultParse.InvalidResult) {
            throw StoreError("stored evidence invalid: ${e.message}")
        }
        val meta = try {
            SessionJson.parse(
                metaFile.readText(Charsets.UTF_8)) as? Map<*, *>
        } catch (e: SessionJson.JsonError) {
            throw StoreError("stored meta invalid: ${e.message}")
        } ?: throw StoreError("stored meta invalid for $id")
        if (meta["sha256"] != parsed.summary.sha256) {
            throw StoreError("evidence/meta hash mismatch: $id")
        }
        return Record(
            summary = parsed.summary, bytes = bytes,
            provenance = meta["provenance"] as? String ?: "unknown",
            storedAtUtc = meta["stored_at_utc"] as? String ?: "",
            contributionState = meta["contribution_state"] as? String
                ?: GlobalContract.LOCAL_NOT_SHARED,
            submissionId = meta["submission_id"] as? String,
            serverEligibility = meta["server_eligibility"] as? String,
            lastStatusAtUtc = meta["last_status_at_utc"] as? String,
            consentPolicyVersion =
                meta["consent_policy_version"] as? String,
            consentAcceptedAtUtc =
                meta["consent_accepted_at_utc"] as? String
        )
    }

    /**
     * Updates contribution bookkeeping only (never the evidence
     * bytes). Null arguments leave the field unchanged; [clearReceipt]
     * drops a saved receipt (used only before any send completes).
     */
    @Synchronized
    fun updateContribution(
        id: String,
        state: String? = null,
        submissionId: String? = null,
        serverEligibility: String? = null,
        statusAtUtc: String? = null,
        consentPolicyVersion: String? = null,
        consentAcceptedAtUtc: String? = null,
        clearReceipt: Boolean = false
    ): Record {
        if (!isValidId(id)) throw BadId(id)
        val metaFile = File(dir, "$id.meta.json")
        if (!metaFile.isFile) throw NotFound(id)
        val parsed = try {
            SessionJson.parse(metaFile.readText(Charsets.UTF_8))
        } catch (e: SessionJson.JsonError) {
            throw StoreError("stored meta invalid: ${e.message}")
        }
        if (parsed !is Map<*, *>) throw StoreError("stored meta invalid: $id")
        @Suppress("UNCHECKED_CAST")
        val meta = LinkedHashMap(parsed as Map<String, Any?>)
        if (state != null) meta["contribution_state"] = state
        if (clearReceipt) {
            meta["submission_id"] = null
            meta["server_eligibility"] = null
        } else {
            if (submissionId != null) meta["submission_id"] = submissionId
            if (serverEligibility != null) {
                meta["server_eligibility"] = serverEligibility
            }
        }
        if (statusAtUtc != null) meta["last_status_at_utc"] = statusAtUtc
        if (consentPolicyVersion != null) {
            meta["consent_policy_version"] = consentPolicyVersion
        }
        if (consentAcceptedAtUtc != null) {
            meta["consent_accepted_at_utc"] = consentAcceptedAtUtc
        }
        atomicWrite(
            metaFile,
            SessionJson.stringify(meta).toByteArray(Charsets.UTF_8), id)
        return load(id)
    }

    private fun atomicWrite(target: File, bytes: ByteArray, id: String) {
        val tmp = File(dir, "${target.name}.tmp-${System.nanoTime()}")
        try {
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                throw StoreError(
                    "evidence write failed for $id: ${e.message}")
            }
        } finally {
            tmp.delete()
        }
    }
}
