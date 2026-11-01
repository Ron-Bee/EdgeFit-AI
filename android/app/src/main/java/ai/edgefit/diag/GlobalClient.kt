package ai.edgefit.diag

/**
 * Contribution orchestration for EdgeFit Global (APP-CLOUD-CONTRACT
 * v1). Pure JVM except the injected [GlobalTransport]; host tests use
 * [FakeGlobalTransport]. No automatic sends: every network call here
 * answers one explicit UI action.
 *
 * Safety rules:
 * - A receipt is saved only from a validated 200/201 body. Anything
 *   else leaves the record unshared (or SUBMISSION_UNCERTAIN when the
 *   request may have reached the server).
 * - UNCERTAIN recovery is resubmission of the SAME bytes: the server
 *   dedups by content hash and returns the original submission id, so
 *   no disguised retry id is ever minted.
 * - Double-tap/concurrency is refused via an in-memory in-flight
 *   guard; a persisted SENDING state (process death) is recoverable,
 *   never auto-retried.
 * - Error text never carries tokens or raw benchmark JSON.
 */
class GlobalClient(
    private val store: EvidenceStore,
    private val transport: GlobalTransport,
    private val vault: GlobalTokenVault,
    private val getOrigin: () -> String?,
    private val nowMs: () -> Long
) {
    class GlobalError(val code: String, message: String) : Exception(message)

    sealed interface SubmitOutcome {
        data class Shared(
            val submissionId: String,
            val eligibility: String,
            val duplicate: Boolean
        ) : SubmitOutcome

        data class Rejected(
            val code: String,
            val message: String,
            val details: List<String>
        ) : SubmitOutcome

        data class AuthFailed(val code: String, val message: String) :
            SubmitOutcome

        data class Quota(val retryAfterSec: Long?) : SubmitOutcome
        data class Uncertain(val reason: String) : SubmitOutcome
        data class Blocked(val reason: String) : SubmitOutcome
    }

    sealed interface StatusOutcome {
        data class Updated(
            val eligibility: String,
            val observedAtUtc: String
        ) : StatusOutcome

        data class Failed(val code: String, val message: String) :
            StatusOutcome
    }

    sealed interface RevokeOutcome {
        data object Revoked : RevokeOutcome
        data class Failed(val code: String, val message: String) :
            RevokeOutcome
    }

    @Volatile private var inFlight = false

    /** Human-review model for the consent screen. No network. */
    data class Review(
        val runId: String,
        val provenance: String,
        val deviceModel: String,
        val modelDisplay: String,
        val modelSha256: String,
        val modelSizeBytes: Long,
        val quantization: String,
        val configText: String,
        val status: String,
        val tpsGen: Double,
        val tpsPrefill: Double,
        val thermalText: String,
        val memAvailPctStart: Double?,
        val collectedAtUtc: String,
        val artifactSha256: String,
        val scopes: List<String>,
        val consentPolicyVersion: String,
        val origin: String?,
        val tokenSuffix: String?
    )

    fun review(runId: String): Review {
        val record = try {
            store.load(runId)
        } catch (e: Exception) {
            throw GlobalError("NO_RECORD", "no local evidence: $runId")
        }
        val s = record.summary
        return Review(
            runId = s.runId, provenance = record.provenance,
            deviceModel = s.deviceModel, modelDisplay = s.modelDisplay,
            modelSha256 = s.modelSha256, modelSizeBytes = s.modelSizeBytes,
            quantization = s.quantization,
            configText = "ctx=${s.nCtx} threads=${s.nThreads} " +
                "predict=${s.nPredict} ${s.sampler}",
            status = s.status, tpsGen = s.tpsGen,
            tpsPrefill = s.tpsPrefill,
            thermalText = "${s.thermalBefore}/${s.thermalMid}/${s.thermalAfter}",
            memAvailPctStart = s.memAvailPctStart,
            collectedAtUtc = s.collectedAtUtc,
            artifactSha256 = s.sha256,
            scopes = GlobalContract.CONSENT_SCOPES,
            consentPolicyVersion = GlobalContract.CONSENT_POLICY_VERSION,
            origin = getOrigin(),
            tokenSuffix = vault.suffix()
        )
    }

    /**
     * Submits [runId] after the user approved consent in the UI.
     * Records the consent event, then sends exactly one request.
     */
    fun submit(runId: String): SubmitOutcome {
        val record = try {
            store.load(runId)
        } catch (e: Exception) {
            return SubmitOutcome.Blocked("no local evidence: $runId")
        }
        val origin = getOrigin()
        if (origin == null || GlobalContract.normalizeOrigin(origin) == null) {
            return SubmitOutcome.Blocked("Cloud connection not configured")
        }
        val token = try {
            vault.getToken()
        } catch (e: Exception) {
            return SubmitOutcome.Blocked("upload credential unavailable")
        }
        if (token == null) {
            return SubmitOutcome.Blocked("no upload token provisioned")
        }
        if (!GlobalContract.tokenValid(token)) {
            return SubmitOutcome.Blocked("upload token has invalid shape")
        }
        if (record.contributionState == GlobalContract.LOCAL_SHARED &&
            record.submissionId != null
        ) {
            return SubmitOutcome.Blocked("already shared; use status refresh")
        }
        synchronized(this) {
            if (inFlight) return SubmitOutcome.Blocked("request in flight")
            inFlight = true
        }
        try {
            val acceptedAt =
                BenchResultParse.formatIsoUtc(nowMs())
            store.updateContribution(
                runId, state = GlobalContract.LOCAL_SENDING,
                consentPolicyVersion = GlobalContract.CONSENT_POLICY_VERSION,
                consentAcceptedAtUtc = acceptedAt)
            val envelope = try {
                buildEnvelope(record, acceptedAt)
            } catch (e: GlobalError) {
                store.updateContribution(
                    runId, state = GlobalContract.LOCAL_RETRY)
                return SubmitOutcome.Blocked(e.message ?: e.code)
            }
            val url = GlobalContract.submissionsUrl(origin)
            val reply = try {
                transport.request("POST", url, token, envelope)
            } catch (e: Exception) {
                store.updateContribution(
                    runId, state = GlobalContract.LOCAL_UNCERTAIN)
                return SubmitOutcome.Uncertain(
                    "connection ended before a receipt was saved")
            }
            return handleSubmitReply(runId, reply)
        } finally {
            synchronized(this) { inFlight = false }
        }
    }

    private fun buildEnvelope(record: EvidenceStore.Record, acceptedAt: String): String {
        val raw = try {
            SessionJson.parse(record.bytes.toString(Charsets.UTF_8))
        } catch (e: SessionJson.JsonError) {
            throw GlobalError("BAD_RECORD", "stored evidence unreadable")
        }
        if (raw !is Map<*, *>) {
            throw GlobalError("BAD_RECORD", "stored evidence unreadable")
        }
        @Suppress("UNCHECKED_CAST")
        val envelope = linkedMapOf<String, Any?>(
            "result" to (raw as Map<String, Any?>),
            "consent" to linkedMapOf(
                "policy_version" to GlobalContract.CONSENT_POLICY_VERSION,
                "accepted_at" to acceptedAt,
                "scopes" to GlobalContract.CONSENT_SCOPES))
        val text = try {
            SessionJson.stringify(envelope)
        } catch (e: SessionJson.JsonError) {
            throw GlobalError("BAD_RECORD", "envelope unserializable")
        }
        if (text.toByteArray(Charsets.UTF_8).size >
            GlobalContract.MAX_REQUEST_BYTES
        ) {
            throw GlobalError("TOO_LARGE", "submission exceeds 128 KiB")
        }
        return text
    }

    private fun handleSubmitReply(
        runId: String,
        reply: GlobalHttpResult
    ): SubmitOutcome {
        when (reply.status) {
            201, 200 -> {
                val receipt = parseReceipt(reply.body)
                if (receipt == null) {
                    store.updateContribution(
                        runId, state = GlobalContract.LOCAL_UNCERTAIN)
                    return SubmitOutcome.Uncertain(
                        "server answered but the receipt was unreadable")
                }
                store.updateContribution(
                    runId, state = GlobalContract.LOCAL_SHARED,
                    submissionId = receipt.first,
                    serverEligibility = receipt.second,
                    statusAtUtc = BenchResultParse.formatIsoUtc(nowMs()))
                return SubmitOutcome.Shared(
                    receipt.first, receipt.second,
                    reply.status == 200)
            }
            400, 413, 422 -> {
                val (code, message, details) = parseError(reply.body)
                store.updateContribution(
                    runId, state = GlobalContract.LOCAL_RETRY)
                return SubmitOutcome.Rejected(code, message, details)
            }
            401, 403 -> {
                val (code, message, _) = parseError(reply.body)
                store.updateContribution(
                    runId, state = GlobalContract.LOCAL_RETRY)
                return SubmitOutcome.AuthFailed(code, message)
            }
            429 -> {
                store.updateContribution(
                    runId, state = GlobalContract.LOCAL_RETRY)
                return SubmitOutcome.Quota(reply.retryAfterSec)
            }
            else -> {
                // 5xx and anything unexpected: the request may have
                // been stored server-side; never claim failure.
                store.updateContribution(
                    runId, state = GlobalContract.LOCAL_UNCERTAIN)
                return SubmitOutcome.Uncertain(
                    "server status ${reply.status}; receipt unknown")
            }
        }
    }

    /** Returns (submissionId, eligibility) or null when invalid. */
    private fun parseReceipt(body: String): Pair<String, String>? {
        val root = try {
            SessionJson.parse(body)
        } catch (_: Exception) {
            return null
        } as? Map<*, *> ?: return null
        val id = root["submission_id"] as? String ?: return null
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_:.\\-]{0,127}").matches(id)) {
            return null
        }
        val eligibility = root["eligibility"] as? String ?: return null
        if (eligibility !in GlobalContract.SERVER_STATES) return null
        return id to eligibility
    }

    /** Returns (code, message, details); never throws. */
    private fun parseError(body: String): Triple<String, String, List<String>> {
        val root = try {
            SessionJson.parse(body)
        } catch (_: Exception) {
            return Triple("bad_response", "unreadable error body", emptyList())
        } as? Map<*, *> ?: return Triple(
            "bad_response", "unreadable error body", emptyList())
        val error = root["error"] as? Map<*, *>
        val code = (error?.get("code") as? String) ?: "server_error"
        val message = (error?.get("message") as? String)
            ?.take(500) ?: "request rejected"
        val details = (error?.get("details") as? List<*>)
            ?.mapNotNull { item ->
                when (item) {
                    is String -> item.take(200)
                    is Map<*, *> -> {
                        val field = item["field"] as? String ?: "?"
                        val reason = item["reason"] as? String ?: "?"
                        "$field: $reason".take(200)
                    }
                    else -> null
                }
            }?.take(20) ?: emptyList()
        return Triple(code, message, details)
    }

    /**
     * Refreshes server eligibility for a shared record. Sends no
     * benchmark data. Never downgrades local evidence on failure.
     */
    fun refreshStatus(runId: String): StatusOutcome {
        val record = try {
            store.load(runId)
        } catch (e: Exception) {
            return StatusOutcome.Failed("NO_RECORD", "no local evidence")
        }
        val submissionId = record.submissionId
        if (submissionId == null) {
            return StatusOutcome.Failed("NOT_SHARED", "no submission receipt")
        }
        val origin = getOrigin()
        val url = if (origin == null) null
        else GlobalContract.submissionUrl(origin, submissionId)
        if (url == null) {
            return StatusOutcome.Failed(
                "NO_CONNECTION", "Cloud connection not configured")
        }
        val token = try {
            vault.getToken()
        } catch (e: Exception) {
            return StatusOutcome.Failed(
                "NO_TOKEN", "upload credential unavailable")
        }
        if (token == null || !GlobalContract.tokenValid(token)) {
            return StatusOutcome.Failed("NO_TOKEN", "no upload token")
        }
        synchronized(this) {
            if (inFlight) {
                return StatusOutcome.Failed("BUSY", "request in flight")
            }
            inFlight = true
        }
        try {
            val reply = try {
                transport.request("GET", url, token, null)
            } catch (e: Exception) {
                return StatusOutcome.Failed(
                    "NO_CONNECTION", "backend unreachable")
            }
            if (reply.status != 200) {
                val (code, message, _) = parseError(reply.body)
                return StatusOutcome.Failed(code, message)
            }
            val root = try {
                SessionJson.parse(reply.body)
            } catch (_: Exception) {
                return StatusOutcome.Failed(
                    "BAD_RESPONSE", "unreadable status body")
            } as? Map<*, *> ?: return StatusOutcome.Failed(
                "BAD_RESPONSE", "unreadable status body")
            if (root["submission_id"] != submissionId) {
                return StatusOutcome.Failed(
                    "MISMATCH", "server answered for another submission")
            }
            val eligibility = root["eligibility"] as? String
            if (eligibility !in GlobalContract.SERVER_STATES) {
                return StatusOutcome.Failed(
                    "BAD_RESPONSE", "unknown eligibility state")
            }
            val observed = BenchResultParse.formatIsoUtc(nowMs())
            store.updateContribution(
                runId, serverEligibility = eligibility,
                statusAtUtc = observed)
            return StatusOutcome.Updated(eligibility!!, observed)
        } finally {
            synchronized(this) { inFlight = false }
        }
    }

    /** Withdraws consent server-side for a shared record. */
    fun revoke(runId: String): RevokeOutcome {
        val record = try {
            store.load(runId)
        } catch (e: Exception) {
            return RevokeOutcome.Failed("NO_RECORD", "no local evidence")
        }
        val submissionId = record.submissionId
        if (submissionId == null) {
            return RevokeOutcome.Failed("NOT_SHARED", "nothing to withdraw")
        }
        val origin = getOrigin()
        val url = if (origin == null) null
        else GlobalContract.submissionUrl(origin, submissionId)
        if (url == null) {
            return RevokeOutcome.Failed(
                "NO_CONNECTION", "Cloud connection not configured")
        }
        val token = try {
            vault.getToken()
        } catch (e: Exception) {
            return RevokeOutcome.Failed(
                "NO_TOKEN", "upload credential unavailable")
        }
        if (token == null || !GlobalContract.tokenValid(token)) {
            return RevokeOutcome.Failed("NO_TOKEN", "no upload token")
        }
        synchronized(this) {
            if (inFlight) return RevokeOutcome.Failed("BUSY", "request busy")
            inFlight = true
        }
        try {
            val reply = try {
                transport.request("DELETE", url, token, null)
            } catch (e: Exception) {
                return RevokeOutcome.Failed(
                    "NO_CONNECTION", "backend unreachable")
            }
            if (reply.status != 200) {
                val (code, message, _) = parseError(reply.body)
                return RevokeOutcome.Failed(code, message)
            }
            val root = try {
                SessionJson.parse(reply.body)
            } catch (_: Exception) {
                return RevokeOutcome.Failed(
                    "BAD_RESPONSE", "unreadable revocation body")
            } as? Map<*, *> ?: return RevokeOutcome.Failed(
                "BAD_RESPONSE", "unreadable revocation body")
            if (root["submission_id"] != submissionId ||
                root["eligibility"] != GlobalContract.REVOKED
            ) {
                return RevokeOutcome.Failed(
                    "MISMATCH", "server did not confirm revocation")
            }
            store.updateContribution(
                runId, serverEligibility = GlobalContract.REVOKED,
                statusAtUtc = BenchResultParse.formatIsoUtc(nowMs()))
            return RevokeOutcome.Revoked
        } finally {
            synchronized(this) { inFlight = false }
        }
    }
}
