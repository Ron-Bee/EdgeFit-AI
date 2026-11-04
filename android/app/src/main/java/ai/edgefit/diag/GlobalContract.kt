package ai.edgefit.diag

/**
 * APP-CLOUD-CONTRACT v1: the Android client's record of the EdgeFit
 * Global backend agreement (EF-GLOBAL-CONTRIBUTE-01).
 *
 * Source evidence: `cloud/api.js` + `cloud/validate.js` at EF-CLOUD-01A
 * (commit a6bd12e, undeployed) plus `docs/ef-cloud-01-api-contract.md`.
 * Where the prose doc and live source conflict, the client follows
 * live source and the discrepancy is recorded in
 * docs/ef-global-contribute-app-handoff.md.
 *
 * Verified against source:
 * - POST {origin}/v1/submissions, Bearer install token, 128 KiB cap.
 * - 201 new {submission_id, eligibility: SUBMITTED, duplicate: false};
 *   200 accepted-duplicate {submission_id, eligibility, duplicate: true}.
 * - 401 unauthorized, 400 malformed_json, 413 body_too_large,
 *   422 invalid_submission + itemized rejections, 429 quota_exceeded.
 * - GET/DELETE {origin}/v1/submissions/{id}: 200 receipt/state,
 *   401 unauthorized, 404 not_found (foreign and missing ids share
 *   one 404: no cross-install oracle).
 * - Error envelope is {error: {code, message, details?}} (source has
 *   NO api_version/request_id wrapper despite the prose doc).
 * - Envelope is exactly {result, consent}; NO install_id field (the
 *   doc's install_id is superseded: identity comes from the token).
 * - consent = {policy_version 1..32 chars, accepted_at ISO-8601,
 *   scopes non-empty subset of ["aggregates"]}; "runs" disabled.
 * - Retry-After: honored when present (429 quota / 503 busy emit
 *   it); stays correct if absent. v1.1 rate_limited rides the same
 *   header discipline (bridge forward rule, behavior-safe).
 * - No self-registration endpoint exists: tokens are owner-issued.
 */
object GlobalContract {
    const val APP_CONTRACT_VERSION = 1
    const val CONSENT_POLICY_VERSION = "ef-consent-01"
    val CONSENT_SCOPES = listOf("aggregates")

    /**
     * Upload enablement (EF-ANDROID-CONSENT-ACTIVATION-01).
     * Collection A APPROVED by the owner on 2026-11-01 for
     * policy ef-consent-01, SHA-256
     * B83E78FBF78AD0FBE4E77CFBBCF37709519A4305A45D86FFCCFF6C72E78CDB21
     * (backend implement/ef-global-01-2026-10-30, approval
     * commit 6e09295, docs/ef-consent-01-approval.md).
     * Per-batch uploads stamping this policy are enabled.
     * Publication B remains NOT approved (server-side
     * allowlist; this client sends no publication fields).
     * Withdrawal (revoke) and status refresh were never
     * gated by this flag.
     */
    const val CONSENT_POLICY_APPROVED = true

    const val PATH_SUBMISSIONS = "/v1/submissions"

    const val CONNECT_TIMEOUT_MS = 15000
    const val READ_TIMEOUT_MS = 30000
    const val MAX_REQUEST_BYTES = 128 * 1024
    const val MAX_RESPONSE_BYTES = 64 * 1024
    const val MAX_TOKEN_BYTES = 4096
    const val MAX_ORIGIN_BYTES = 256

    // Server eligibility states (verbatim from backend).
    const val SUBMITTED = "SUBMITTED"
    const val VALIDATED = "VALIDATED"
    const val QUALIFIED = "QUALIFIED"
    const val PUBLISHED = "PUBLISHED"
    const val REVOKED = "REVOKED"
    const val REJECTED = "REJECTED"
    val SERVER_STATES = setOf(
        SUBMITTED, VALIDATED, QUALIFIED, PUBLISHED, REVOKED, REJECTED)

    // Local contribution states (never confused with server states).
    const val LOCAL_NOT_SHARED = "NOT_SHARED"
    const val LOCAL_AWAITING_CONSENT = "AWAITING_CONSENT"
    const val LOCAL_SENDING = "SENDING"
    const val LOCAL_UNCERTAIN = "SUBMISSION_UNCERTAIN"
    const val LOCAL_SHARED = "SHARED"
    const val LOCAL_RETRY = "RETRY_AVAILABLE"
    const val LOCAL_NO_CONNECTION = "CONNECTION_UNAVAILABLE"

    /** Bearer token shape the backend accepts (api.js bearerToken). */
    private val TOKEN = Regex("[A-Za-z0-9._~+/-]{8,256}=*")

    fun tokenValid(token: String): Boolean = TOKEN.matches(token.trim())

    /**
     * Owner-configured staging origin. Must be plain https without
     * userinfo; path prefix allowed (e.g. https://host/prefix trims
     * to host + prefix). Returns the normalized base or null.
     */
    fun normalizeOrigin(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() ||
            trimmed.toByteArray(Charsets.UTF_8).size > MAX_ORIGIN_BYTES
        ) return null
        if (!trimmed.startsWith("https://")) return null
        val rest = trimmed.removePrefix("https://")
        if (rest.isEmpty() || rest.contains("@") || rest.contains(" ") ||
            rest.contains("\\")
        ) return null
        val host = rest.substringBefore("/")
        if (host.isEmpty() || host.contains(":") &&
            !host.substringAfter(":").all { it.isDigit() }
        ) return null
        return "https://" + rest.trimEnd('/')
    }

    fun submissionsUrl(origin: String): String =
        normalizeOrigin(origin)!! + PATH_SUBMISSIONS

    fun submissionUrl(origin: String, submissionId: String): String? {
        val base = normalizeOrigin(origin) ?: return null
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_:.\\-]{0,127}")
                .matches(submissionId)
        ) return null
        return base + PATH_SUBMISSIONS + "/" + submissionId
    }

    /** User-readable server state; unknown states are never upgraded. */
    fun displayServerState(state: String?): String = when (state) {
        SUBMITTED -> "Submitted"
        VALIDATED -> "Validated"
        QUALIFIED -> "Qualified"
        PUBLISHED -> "Published"
        REVOKED -> "Revoked"
        REJECTED -> "Rejected"
        null -> "Not shared"
        else -> "Unknown state ($state)"
    }
}
