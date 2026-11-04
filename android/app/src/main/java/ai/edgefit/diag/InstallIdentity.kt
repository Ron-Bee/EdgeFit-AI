package ai.edgefit.diag

import java.io.File

/**
 * EF-ANDROID-RECOVERY-INSTALL-01 durable install marker.
 *
 * Records the identity of the installation the first time the app
 * launches: stable install UUID, applicationId (base vs `.bench`
 * benchmark variant), first-seen version code, and first-launch UTC.
 * A valid marker is NEVER overwritten — not on upgrade, not on
 * identity mismatch — so a later reader can always distinguish a
 * long-lived updated install from a fresh one. The marker is
 * diagnostic only: a missing or unreadable marker never blocks
 * launch and NEVER deletes, moves, or rewrites session or evidence
 * state.
 *
 * No Android APIs; fully JVM-testable over a temp directory.
 */
object InstallIdentity {
    const val FILE_NAME = "install.json"

    data class Record(
        val installId: String,
        val applicationId: String,
        val versionCode: Int,
        val firstLaunchUtc: String
    )

    /**
     * Returns the stored record, creating it exactly once when absent
     * or unparseable. Identity arguments describe the CURRENT launch;
     * they are persisted only when no valid record exists.
     */
    @Synchronized
    fun ensure(
        rootDir: File,
        applicationId: String,
        versionCode: Int,
        firstLaunchUtc: String,
        newId: () -> String = { java.util.UUID.randomUUID().toString() }
    ): Record {
        val file = File(rootDir, FILE_NAME)
        readRecord(file)?.let { return it }
        val record = Record(
            installId = newId(),
            applicationId = applicationId,
            versionCode = versionCode,
            firstLaunchUtc = firstLaunchUtc)
        try {
            file.writeText(SessionJson.stringify(linkedMapOf(
                "schema_version" to 1,
                "install_id" to record.installId,
                "application_id" to record.applicationId,
                "version_code" to record.versionCode,
                "first_launch_utc" to record.firstLaunchUtc
            )), Charsets.UTF_8)
        } catch (_: Exception) {
            // Fail open: the marker is diagnostic. The launch
            // continues and session/evidence state is untouched.
        }
        return record
    }

    /** Parses a marker file, or null when absent/unreadable/invalid. */
    fun readRecord(file: File): Record? {
        if (!file.isFile) return null
        val root = try {
            SessionJson.parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        } as? Map<*, *> ?: return null
        val installId = root["install_id"] as? String
        val applicationId = root["application_id"] as? String
        val version = root["version_code"] as? Number
        val firstLaunch = root["first_launch_utc"] as? String
        if (installId.isNullOrBlank() || applicationId.isNullOrBlank() ||
            version == null || firstLaunch.isNullOrBlank()
        ) {
            return null
        }
        return Record(installId, applicationId, version.toInt(), firstLaunch)
    }

    /** True when the stored record matches the current launch. */
    fun matches(
        record: Record,
        applicationId: String,
        versionCode: Int
    ): Boolean =
        record.applicationId == applicationId &&
            record.versionCode == versionCode

    /** One-line About rendering; short id only, never a secret. */
    fun statusLine(record: Record): String =
        "${record.applicationId} · first launch " +
            "${record.firstLaunchUtc} · #${record.installId.take(8)}"
}
