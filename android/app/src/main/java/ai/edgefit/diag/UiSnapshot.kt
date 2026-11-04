package ai.edgefit.diag

/**
 * Rotation/process-death UI snapshot
 * (EF-ANDROID-LIFECYCLE-PERSISTENCE-P0-01). Pure JVM: the
 * activity encodes this to a string map at
 * `onSaveInstanceState` and decodes it in `onCreate`. The
 * live model bytes and benchmark runner are NOT in here —
 * [ModelStore] owns the model and the retained instance
 * holder owns the runner. Every field fails soft on
 * corrupt input: a bad entry degrades to its default,
 * never a crash and never a fabricated run.
 */
data class UiSnapshot(
    val tab: String = "HOME",
    val activeSessionId: String? = null,
    val pendingJson: String? = null,
    val pendingFilename: String? = null,
    val standaloneOverride: BenchConfigData? = null,
    val benchJson: String? = null,
    /** A benchmark was in flight when state was saved. */
    val runInFlight: Boolean = false,
    /** The bench overlay covered the tab shell. */
    val benchOverlay: Boolean = false,
    val openSheet: String? = null,
    val openSheetArg: String? = null,
    val pendingRepeatPrepId: String? = null,
    val onboardingPage: Int = 0,
    /** Unapplied manual-config dialog selection. */
    val manualChecked: Int? = null,
    /** Non-sensitive dialog drafts (never secrets). */
    val draftOrigin: String? = null,
    val draftRememberGlobal: Boolean = false,
    val draftRememberMeta: Boolean = false,
    /** "nCtx,nThreads,nPredict" modify-proposal draft. */
    val draftModify: String? = null,
    /** "gen,prefill" session-create draft. */
    val draftCreate: String? = null,
    val statusText: String? = null,
    val pickerActive: Boolean = false
)

/** JSON codec for [BenchConfigData] (validated on decode). */
object BenchConfigCodec {
    fun encode(config: BenchConfigData): String =
        SessionJson.stringify(linkedMapOf(
            "n_ctx" to config.nCtx,
            "n_threads" to config.nThreads,
            "n_predict" to config.nPredict,
            "temperature" to config.temperature,
            "sampler" to config.sampler,
            "seed" to config.seed,
            "timeout_ms" to config.timeoutMs
        ))

    /**
     * Decodes an override, or null when the text is corrupt
     * or fails production validation. Corrupt input never
     * yields a half-config.
     */
    fun decode(text: String?): BenchConfigData? {
        if (text.isNullOrEmpty()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val root = SessionJson.parse(text) as? Map<String, Any?>
                ?: return null
            val config = BenchConfigData(
                nCtx = (root["n_ctx"] as? Number)?.toInt()
                    ?: return null,
                nThreads = (root["n_threads"] as? Number)?.toInt()
                    ?: return null,
                nPredict = (root["n_predict"] as? Number)?.toInt()
                    ?: return null,
                temperature = (root["temperature"] as? Number)
                    ?.toDouble() ?: return null,
                sampler = root["sampler"] as? String ?: return null,
                seed = (root["seed"] as? Number)?.toLong()
                    ?: return null,
                timeoutMs = (root["timeout_ms"] as? Number)?.toLong()
                    ?: return null
            )
            if (BenchConfig.validate(config).isNotEmpty()) null
            else config
        } catch (_: Exception) {
            null
        }
    }
}

/** String-map codec for [UiSnapshot] (Bundle-safe values). */
object UiSnapshotCodec {
    const val K_TAB = "uis.tab"
    const val K_ACTIVE_SESSION = "uis.active_session"
    const val K_PENDING_JSON = "uis.pending_json"
    const val K_PENDING_FILENAME = "uis.pending_filename"
    const val K_OVERRIDE = "uis.override"
    const val K_BENCH_JSON = "uis.bench_json"
    const val K_RUN_IN_FLIGHT = "uis.run_in_flight"
    const val K_BENCH_OVERLAY = "uis.bench_overlay"
    const val K_SHEET = "uis.sheet"
    const val K_SHEET_ARG = "uis.sheet_arg"
    const val K_REPEAT_PREP = "uis.repeat_prep"
    const val K_ONBOARDING_PAGE = "uis.onboarding_page"
    const val K_MANUAL_CHECKED = "uis.manual_checked"
    const val K_DRAFT_ORIGIN = "uis.draft_origin"
    const val K_DRAFT_REMEMBER = "uis.draft_remember"
    const val K_DRAFT_REMEMBER_META = "uis.draft_remember_meta"
    const val K_DRAFT_MODIFY = "uis.draft_modify"
    const val K_DRAFT_CREATE = "uis.draft_create"
    const val K_STATUS = "uis.status"
    const val K_PICKER = "uis.picker"

    fun encode(snapshot: UiSnapshot): Map<String, String> {
        val out = linkedMapOf<String, String>()
        out[K_TAB] = snapshot.tab
        snapshot.activeSessionId?.let { out[K_ACTIVE_SESSION] = it }
        snapshot.pendingJson?.let { out[K_PENDING_JSON] = it }
        snapshot.pendingFilename?.let {
            out[K_PENDING_FILENAME] = it
        }
        snapshot.standaloneOverride?.let {
            out[K_OVERRIDE] = BenchConfigCodec.encode(it)
        }
        snapshot.benchJson?.let { out[K_BENCH_JSON] = it }
        if (snapshot.runInFlight) out[K_RUN_IN_FLIGHT] = "1"
        if (snapshot.benchOverlay) out[K_BENCH_OVERLAY] = "1"
        snapshot.openSheet?.let { out[K_SHEET] = it }
        snapshot.openSheetArg?.let { out[K_SHEET_ARG] = it }
        snapshot.pendingRepeatPrepId?.let {
            out[K_REPEAT_PREP] = it
        }
        out[K_ONBOARDING_PAGE] = snapshot.onboardingPage.toString()
        snapshot.manualChecked?.let {
            out[K_MANUAL_CHECKED] = it.toString()
        }
        snapshot.draftOrigin?.let { out[K_DRAFT_ORIGIN] = it }
        if (snapshot.draftRememberGlobal) out[K_DRAFT_REMEMBER] = "1"
        if (snapshot.draftRememberMeta) out[K_DRAFT_REMEMBER_META] = "1"
        snapshot.draftModify?.let { out[K_DRAFT_MODIFY] = it }
        snapshot.draftCreate?.let { out[K_DRAFT_CREATE] = it }
        snapshot.statusText?.let { out[K_STATUS] = it }
        if (snapshot.pickerActive) out[K_PICKER] = "1"
        return out
    }

    fun decode(map: Map<String, String>): UiSnapshot {
        return UiSnapshot(
            tab = map[K_TAB] ?: "HOME",
            activeSessionId = map[K_ACTIVE_SESSION],
            pendingJson = map[K_PENDING_JSON],
            pendingFilename = map[K_PENDING_FILENAME],
            standaloneOverride =
                BenchConfigCodec.decode(map[K_OVERRIDE]),
            benchJson = map[K_BENCH_JSON],
            runInFlight = map[K_RUN_IN_FLIGHT] == "1",
            benchOverlay = map[K_BENCH_OVERLAY] == "1",
            openSheet = map[K_SHEET],
            openSheetArg = map[K_SHEET_ARG],
            pendingRepeatPrepId = map[K_REPEAT_PREP],
            onboardingPage =
                map[K_ONBOARDING_PAGE]?.toIntOrNull() ?: 0,
            manualChecked = map[K_MANUAL_CHECKED]?.toIntOrNull(),
            draftOrigin = map[K_DRAFT_ORIGIN],
            draftRememberGlobal = map[K_DRAFT_REMEMBER] == "1",
            draftRememberMeta = map[K_DRAFT_REMEMBER_META] == "1",
            draftModify = map[K_DRAFT_MODIFY],
            draftCreate = map[K_DRAFT_CREATE],
            statusText = map[K_STATUS],
            pickerActive = map[K_PICKER] == "1"
        )
    }
}
