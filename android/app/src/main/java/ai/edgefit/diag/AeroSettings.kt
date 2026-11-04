package ai.edgefit.diag

/**
 * EF-UX-AERO-01 Step 5 settings model. Pure group/row
 * descriptors for Appearance, Benchmark, Meta Muse,
 * EdgeFit Global, Privacy, and About. Only REAL
 * controls appear: every row is either read-only
 * information (action null) or opens an existing,
 * working handler (action non-null). There are no
 * toggles, no placeholder switches, and no secrets —
 * at most a key/token suffix renders. Meta BYOK and
 * the Global upload token stay in separate groups and
 * are never mixed. The activity maps [Action] to its
 * existing dialogs; this object owns no navigation.
 */
object AeroSettings {

    enum class Group {
        APPEARANCE,
        BENCHMARK,
        META,
        GLOBAL,
        PRIVACY,
        ABOUT
    }

    /**
     * Real destinations only. Each id maps to an
     * existing MainActivity handler; no id may be
     * introduced without wiring it.
     */
    enum class Action {
        META_KEY,
        GLOBAL_CONNECTION,
        CONTRIBUTION_HISTORY,
        WORKLOAD,
        MEM_DIAG,
        DEVICE_DETAILS,
        MODEL_CATALOG,
        EXPORT_PROFILE,
        HELP_GUIDE
    }

    /** Dynamic values the activity reads from real state. */
    data class Context(
        val metaKeyStatus: String,
        val globalOrigin: String?,
        val globalTokenSuffix: String?,
        val globalTokenPersisted: Boolean,
        val versionName: String,
        val packageName: String,
        val workloadId: String,
        val installLine: String? = null
    )

    data class Row(
        val label: String,
        val detail: String?,
        val action: Action?
    )

    fun groups(): List<Group> = listOf(
        Group.APPEARANCE, Group.BENCHMARK, Group.META,
        Group.GLOBAL, Group.PRIVACY, Group.ABOUT
    )

    fun title(group: Group): String = when (group) {
        Group.APPEARANCE -> "Appearance"
        Group.BENCHMARK -> "Benchmark"
        Group.META -> "Meta Muse"
        Group.GLOBAL -> "EdgeFit Global"
        Group.PRIVACY -> "Privacy"
        Group.ABOUT -> "About"
    }

    /**
     * One-line helper under each group title: a
     * beginner cue for what the group holds.
     */
    fun helper(group: Group): String = when (group) {
        Group.APPEARANCE ->
            "How the app looks. No setup needed."
        Group.BENCHMARK ->
            "Workload, models, and measurement checks."
        Group.META ->
            "Your own Meta key, used only when you ask."
        Group.GLOBAL ->
            "Optional sharing of results. Off until " +
                "you configure it."
        Group.PRIVACY ->
            "What stays on this device, and what " +
                "leaves it."
        Group.ABOUT ->
            "Version and the built-in guide."
    }

    fun rows(group: Group, ctx: Context): List<Row> = when (group) {
        Group.APPEARANCE -> listOf(
            // No appearance control exists: the shell is
            // fixed dark Aero. An honest label, never a
            // fake theme toggle.
            Row("Theme", "Aero dark shell. There are no " +
                "appearance options to change.", null)
        )
        Group.BENCHMARK -> listOf(
            Row("Workload",
                "The standard prompt and config a run " +
                    "executes. Opens a read-only summary.",
                Action.WORKLOAD),
            Row("Recommended Models",
                "Offline candidate list with suitability " +
                    "notes. Catalog notes are not " +
                    "benchmark proof.",
                Action.MODEL_CATALOG),
            Row("Memory Diagnostics",
                "Advanced: sampling checkpoints around " +
                    "runs. Opens the diagnostics dialog.",
                Action.MEM_DIAG),
            Row("Device Details",
                "Advanced: live profile, telemetry, and " +
                    "the full collected snapshot.",
                Action.DEVICE_DETAILS)
        )
        Group.META -> listOf(
            Row("Meta API key", ctx.metaKeyStatus, null),
            Row("Manage key",
                "BYOK: set a session-only key or a " +
                    "securely persisted one, forget it, or " +
                    "start a prediction. The key decrypts " +
                    "only when you authorize a request.",
                Action.META_KEY)
        )
        Group.GLOBAL -> listOf(
            Row("Connection",
                AeroContribute.connectionLine(
                    ctx.globalOrigin, ctx.globalTokenSuffix) +
                    if (ctx.globalTokenPersisted &&
                        ctx.globalTokenSuffix != null
                    ) " Token is persisted in Keystore."
                    else "",
                null),
            Row("EdgeFit Global Connection",
                "Set the staging origin and the " +
                    "owner-issued upload token. Separate " +
                    "from your Meta key.",
                Action.GLOBAL_CONNECTION),
            Row("Contribution History",
                "Local evidence, per-run states, status " +
                    "refresh, and consent withdrawal.",
                Action.CONTRIBUTION_HISTORY)
        )
        Group.PRIVACY -> listOf(
            Row("On-device first",
                "Sessions and evidence stay on this " +
                    "device until you tap Contribute.",
                null),
            Row("No automatic uploads",
                "EdgeFit never uploads on its own. " +
                    "Every share is an explicit tap with " +
                    "a consent record.",
                null),
            Row("Export device profile",
                "Saves the collected profile JSON " +
                    "through the system file picker. " +
                    "Explicit tap only.",
                Action.EXPORT_PROFILE),
            Row("Review or withdraw uploads",
                "Open the history to refresh a server " +
                    "state or withdraw consent for a " +
                    "shared row.",
                Action.CONTRIBUTION_HISTORY)
        )
        Group.ABOUT -> listOf(
            Row("Version",
                "EdgeFit AI ${ctx.versionName} " +
                    "(${ctx.packageName})", null),
            Row("Installation",
                ctx.installLine
                    ?: "Install marker unavailable.",
                null),
            Row("Workload",
                "${ctx.workloadId} · Runtime ABI: arm64-v8a",
                null),
            Row("Show introduction",
                "Replay the three-screen guide any " +
                    "time, even after it retires.",
                Action.HELP_GUIDE)
        )
    }
}
