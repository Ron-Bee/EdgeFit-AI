package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Presentation layer: device summary + benchmark console. Profile export
 * and benchmark-result saving use the Storage Access Framework on explicit
 * tap only. Rotation or backgrounding cancels a running benchmark
 * (fail-safe: no uncontrolled workload survives lifecycle changes).
 */
class MainActivity : Activity() {

    private lateinit var deviceTitleView: TextView
    private lateinit var deviceUpdatedView: TextView
    private val deviceTileValues = mutableMapOf<String, TextView>()
    private lateinit var modelReadinessView: TextView
    private lateinit var runHintView: TextView
    private lateinit var benchAuthView: TextView
    private lateinit var statusView: TextView
    private lateinit var modelView: TextView
    private lateinit var benchView: TextView
    private lateinit var runButton: Button
    private lateinit var cancelButton: Button
    private lateinit var saveButton: Button
    private lateinit var memReadinessView: TextView
    private lateinit var benchConfigView: TextView
    private lateinit var memDiagButton: Button
    private var pendingJson: String? = null
    private var pendingFilename: String? = null
    private var staged: StagedModel? = null
    /**
     * EF-MEM-03 standalone config override (process-local,
     * cleared on staging a new model). Applies ONLY to
     * unbound runs; a bound session's config always wins.
     * Set explicitly through the recovery assistant; the
     * benchmark tab always shows it while active.
     */
    private var standaloneOverride: BenchConfigData? = null
    private var benchJson: String? = null
    private var lastResult: BenchResult? = null
    private lateinit var runner: BenchRunner
    private lateinit var shellRoot: View
    private lateinit var contentFrame: FrameLayout
    private lateinit var benchScreen: BenchScreenView
    private val destinations = mutableMapOf<NavTab, View>()
    private lateinit var topBar: AeroTopBar
    private lateinit var dock: AeroDockView
    private var currentTab: NavTab = NavTab.HOME
    private lateinit var nextActionView: TextView
    private lateinit var nextActionButton: Button
    private lateinit var modelCardView: TextView
    private lateinit var lastRunView: TextView
    private lateinit var metaLineView: TextView
    private var pendingNextAction: NextAction = NextAction.SELECT_MODEL
    private var lastProfileDetails: String = ""
    // UX-01E live telemetry: cached static profile plus
    // visibility-gated sampling. No polling while hidden.
    private var lastProfile: CollectedProfile? = null
    private var activityStarted = false
    private var detailsOpen = false
    private var telemetryReceiver: BroadcastReceiver? = null
    private var homeTicker: DeviceTelemetry.Ticker? = null
    private var detailsTicker: DeviceTelemetry.Ticker? = null
    private var detailsView: TextView? = null
    private lateinit var sessionsSummaryView: TextView
    // EF-UX-AERO-01 Step 4 inline evidence library:
    // bounded recent cards, rebuilt on every refresh.
    private lateinit var recentSessionsList: LinearLayout
    private lateinit var sessionsMoreView: TextView
    // EF-UX-AERO-01 Step 4 result hero: this launch's
    // run; Details opens the full sections (raw IDs).
    private lateinit var resultHeroBox: LinearLayout
    private lateinit var resultDetailsButton: Button
    private lateinit var agentSummaryView: TextView
    // EF-UX-AERO-01 Step 5 agent workspace: bounded
    // inline parent cards, rebuilt on every refresh.
    private lateinit var agentWorkspaceList: LinearLayout
    private lateinit var agentMoreView: TextView
    // EF-UX-AERO-01 Step 5 contribute preview + settings
    // status lines (read-only; actions stay explicit).
    private lateinit var contributePreviewView: TextView
    private lateinit var settingsMetaView: TextView
    private lateinit var settingsGlobalView: TextView
    private var benchActive: Boolean = false
    private val requestTracker = RequestTracker()
    private lateinit var sessionManager: SessionManager
    private lateinit var sessionLine: TextView
    private var activeSessionId: String? = null
    /** Repeat-prep screen to reopen after model staging. */
    private var pendingRepeatPrepId: String? = null
    /** EF-UX-01D onboarding state (process-local by design). */
    private var onboardingDismissedThisLaunch = false
    private var onboardingOpen = false
    private var onboardingPage = 0
    private var agentDialogOpen = false
    private var pickerActive = false
    private var justPicked = false
    private lateinit var metaVault: MetaKeyVault
    private lateinit var metaClient: MetaSessionClient
    private lateinit var agentManager: AgentManager
    // EF-GLOBAL-CONTRIBUTE-01: durable evidence + upload client.
    private lateinit var evidenceStore: EvidenceStore
    private lateinit var globalVault: GlobalTokenVault
    private lateinit var globalClient: GlobalClient
    private lateinit var evidenceLine: TextView
    private var globalOrigin: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingJson = savedInstanceState?.getString(KEY_PENDING_JSON)
        pendingFilename = savedInstanceState?.getString(KEY_PENDING_FILENAME)
        activeSessionId = savedInstanceState?.getString(KEY_ACTIVE_SESSION)
        onboardingPage = savedInstanceState?.getInt(KEY_ONBOARDING_PAGE, 0) ?: 0
        runner = BenchRunner(this)
        sessionManager = SessionManager(filesDir)
        metaVault = MetaKeyVault(filesDir, AndroidKeySeal())
        metaClient = MetaSessionClient(
            sessionManager, HttpsMetaTransport(), metaVault)
        agentManager = AgentManager(sessionManager)
        evidenceStore = EvidenceStore(filesDir)
        globalVault = GlobalTokenVault(filesDir, GlobalKeySeal())
        globalOrigin = readGlobalOrigin()
        globalClient = GlobalClient(
            evidenceStore, HttpsGlobalTransport(), globalVault,
            { globalOrigin }, { System.currentTimeMillis() })
        Thread({
            val actions = try {
                val found = sessionManager.recoverOnLaunch().toMutableList()
                try {
                    for (parent in sessionManager.listSessions()) {
                        if (parent.mode == "agent") {
                            found.addAll(agentManager.reconcile(parent.sessionId))
                        }
                    }
                } catch (e: Exception) {
                    found.add("agent reconcile failed: ${e.message}")
                }
                found
            } catch (e: Exception) {
                listOf("recovery failed: ${e.message}")
            }
            runOnUiThread {
                refreshSessionLine()
                if (actions.isNotEmpty()) {
                    statusText("Session recovery: ${actions.joinToString("; ")}")
                }
            }
        }, "edgefit-session-recover").start()
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val basePadding = dp(16)
        deviceTitleView = TextView(this).apply {
            text = "…"
            textSize = 16f
            setTypeface(typeface,
                android.graphics.Typeface.BOLD)
        }
        deviceUpdatedView = TextView(this).apply {
            text = "…"
            textSize = 12f
        }
        statusView = TextView(this)
        modelView = TextView(this).apply {
            text = BenchWorkflow.modelLine(null, 0L, null, null)
        }
        benchView = TextView(this).apply { text = "Benchmark: idle" }
        sessionLine = TextView(this).apply {
            text = SessionView.buildActiveLine(null, null)
        }
        val export = Button(this).apply { text = "Export Profile" }
        runButton = Button(this).apply {
            text = "Run Benchmark"
            // Enabled once a model is staged (UX polish: no dead-end
            // error tap). Native guards still enforce all safety.
            isEnabled = false
        }
        AeroUi.stylePrimary(this, runButton)
        cancelButton = Button(this).apply { text = "Cancel"; isEnabled = false }
        saveButton = Button(this).apply { text = "Save Result"; isEnabled = false }
        val select = Button(this).apply { text = "Select Model File" }
        val workloadButton = Button(this).apply { text = "View Workload" }
        val sessionsButton = Button(this).apply {
            text = "Open session browser"
        }
        val metaButton = Button(this).apply { text = "Meta API" }
        val agentButton = Button(this).apply {
            text = "Open agent workspace"
        }
        val runRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(runButton, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancelButton, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun sectionHeader(title: String): TextView =
            AeroUi.sectionHeader(this, title)
        // UX-01A: the same widget instances, re-homed into five
        // tab destinations. No handler or behavior changes here.
        nextActionView = TextView(this).apply {
            textSize = 14f
            setTextColor(accentColor())
        }
        sessionsSummaryView = TextView(this)
        recentSessionsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        sessionsMoreView = TextView(this).apply {
            textSize = 13f
            setTextColor(secondaryColor())
        }
        agentSummaryView = TextView(this).apply {
            textSize = 13f
            setTextColor(secondaryColor())
        }
        agentWorkspaceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        agentMoreView = TextView(this).apply {
            textSize = 13f
            setTextColor(secondaryColor())
        }
        val agentHintView = TextView(this).apply {
            text = AeroAgent.roleLines().joinToString("\n")
        }
        val aboutView = TextView(this).apply {
            text = "EdgeFit AI ${BuildConfig.VERSION_NAME} " +
                "($packageName)\nWorkload: " +
                "${BenchContract.WORKLOAD_ID}\nRuntime ABI: arm64-v8a"
        }
        for (view in listOf(deviceTitleView, modelView, benchView,
            sessionLine, sessionsSummaryView, agentHintView,
            aboutView)) {
            view.setTextColor(inkColor())
        }
        deviceUpdatedView.setTextColor(secondaryColor())
        statusView.apply {
            textSize = 12f
            setTextColor(secondaryColor())
            setPadding(basePadding, dp(4), basePadding, dp(4))
        }
        // Card group: related rows on one raised surface,
        // 8dp between rows. Headers stay flat above cards.
        fun cardGroup(vararg views: View): LinearLayout =
            AeroUi.card(this).apply {
                views.forEachIndexed { index, child ->
                    addView(child, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply {
                            if (index > 0) topMargin = dp(8)
                        })
                }
            }
        fun destination(vararg views: View): View =
            ScrollView(this).apply {
                val column = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(basePadding, basePadding,
                        basePadding, basePadding)
                    for (child in views) {
                        addView(child, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                            .apply {
                                bottomMargin = dp(12)
                            })
                    }
                }
                addView(column)
                visibility = View.GONE
            }
        // EF-UX-AERO-01 Step 3 Home dashboard: hero,
        // one contextual primary action, device metric
        // tiles, model readiness, the last real
        // measurement (or a designed empty state), and
        // quick-access links. Detail stays one tap away;
        // nothing here runs work.
        nextActionButton = Button(this).apply {
            text = HomeDashboard.primaryAction(pendingNextAction)
        }
        AeroUi.stylePrimary(this, nextActionButton)
        nextActionButton.setOnClickListener {
            selectTab(HomeDashboard.actionTab(pendingNextAction))
        }
        modelReadinessView = AeroUi.cardTitle(this, "No model")
        modelCardView = TextView(this).apply {
            setTextColor(inkColor())
        }
        lastRunView = TextView(this).apply {
            setTextColor(inkColor())
        }
        metaLineView = TextView(this).apply {
            setTextColor(secondaryColor())
        }
        val deviceDetailsButton = Button(this).apply {
            text = "Device Details"
        }
        deviceDetailsButton.setOnClickListener {
            showLiveDeviceDetails()
        }
        // Metric tile shells; values refresh live via
        // refreshHomeHealth. Labels come from the same
        // pure list as the values, so order cannot drift.
        val tileRoots = HomeDashboard.metricLabels.map { label ->
            AeroUi.metricTile(this, label, "…").also {
                deviceTileValues[label] = it.valueView
            }.root
        }
        val tileRowA = AeroUi.tileRow(this,
            tileRoots[0], tileRoots[1])
        val tileRowB = AeroUi.tileRow(this,
            tileRoots[2], tileRoots[3])
        val tileRowC = AeroUi.tileRow(this,
            tileRoots[4], tileRoots[5])
        // EF-MODEL-01A: offline catalog, no key needed.
        // Quick access owns all Home navigation now; a
        // null tab opens the catalog dialog instead.
        val quickLinks = HomeDashboard.quickAccess()
        val quickButtons = quickLinks.map { link ->
            Button(this).apply {
                text = link.label
                contentDescription =
                    "${link.label} — ${link.detail}"
            }
        }
        quickButtons.forEachIndexed { index, button ->
            val tab = quickLinks[index].tab
            button.setOnClickListener {
                if (tab == null) showModelCatalog()
                else selectTab(tab)
            }
        }
        val quickRowA = AeroUi.tileRow(this,
            quickButtons[0], quickButtons[1])
        val quickRowB = AeroUi.tileRow(this,
            quickButtons[2], quickButtons[3])
        val metaGoButton = Button(this).apply { text = "Meta Settings" }
        metaGoButton.setOnClickListener {
            selectTab(NavTab.SETTINGS)
        }
        val hero = HomeDashboard.hero()
        destinations[NavTab.HOME] = destination(
            AeroUi.heroCard(this, hero.overline, hero.title,
                hero.subtitle),
            sectionHeader("Next Up"),
            cardGroup(nextActionView, nextActionButton),
            sectionHeader("This Device"),
            cardGroup(deviceTitleView, tileRowA, tileRowB,
                tileRowC, tileRoots[6], deviceUpdatedView,
                deviceDetailsButton),
            sectionHeader("Model"),
            cardGroup(modelReadinessView, modelCardView,
                sessionLine),
            sectionHeader("Last Measurement"),
            cardGroup(lastRunView),
            sectionHeader("Quick Access"),
            cardGroup(quickRowA, quickRowB),
            sectionHeader("Meta Muse"),
            cardGroup(metaLineView, metaGoButton))
        val benchModelsButton = Button(this).apply {
            text = "Recommended Models"
        }
        benchModelsButton.setOnClickListener {
            showModelCatalog()
        }
        memReadinessView = TextView(this).apply {
            setTextColor(secondaryColor())
        }
        val memCheckButton = Button(this).apply {
            text = "Check Memory Readiness"
        }
        memCheckButton.setOnClickListener {
            showMemReadiness()
        }
        // EF-COMP-01B-ROUTE: explicit manual config for
        // unbound runs; bound sessions always win verbatim.
        benchConfigView = TextView(this).apply {
            setTextColor(secondaryColor())
        }
        val configButton = Button(this).apply {
            text = "Select Config"
        }
        configButton.setOnClickListener {
            showManualBenchConfig()
        }
        // EF-GLOBAL-CONTRIBUTE-01: durable evidence + contribution.
        evidenceLine = TextView(this).apply {
            setTextColor(secondaryColor())
        }
        // Step 5 inline preview: device/model/result
        // plus connection state; read-only, no gating.
        contributePreviewView = TextView(this).apply {
            textSize = 13f
            setTextColor(inkColor())
        }
        val contributeButton = Button(this).apply {
            text = "Contribute to EdgeFit Global"
        }
        contributeButton.setOnClickListener { showContributeLatest() }
        val importButton = Button(this).apply {
            text = "Import Result JSON"
        }
        importButton.setOnClickListener { launchBenchImport() }
        val historyButton = Button(this).apply {
            text = "Contribution History"
        }
        historyButton.setOnClickListener { showContributionHistory() }
        // Step 3 guided workflow: Model, Configure, Run,
        // then Results and Contribute LAST, so sharing
        // reads as an optional follow-up — never a
        // required pre-step. Import, history, and
        // Contribute stay reachable with no staged model
        // and no new run (always enabled, no gating).
        benchAuthView = TextView(this).apply {
            setTextColor(secondaryColor())
        }
        runHintView = AeroUi.captionText(this,
            BenchWorkflow.runHint(false, false))
        resultHeroBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        resultDetailsButton = Button(this).apply {
            text = "Result Details"
        }
        resultDetailsButton.setOnClickListener {
            showResultDetails()
        }
        // Screen titles live in the top bar; destinations
        // keep section headers only (no "Benchmark" /
        // "Sessions" echo and no bare launcher buttons —
        // each tab shows live inline content).
        destinations[NavTab.BENCHMARK] = destination(
            sectionHeader("1 · Model"),
            cardGroup(modelView, select, benchModelsButton),
            sectionHeader("2 · Configure"),
            cardGroup(benchConfigView, configButton),
            sectionHeader("3 · Run"),
            cardGroup(benchAuthView, benchView,
                memReadinessView, memCheckButton, runRow,
                runHintView),
            sectionHeader("4 · Results and Contribute"),
            cardGroup(resultHeroBox, resultDetailsButton,
                saveButton, evidenceLine,
                contributePreviewView,
                contributeButton, importButton,
                historyButton))
        destinations[NavTab.SESSIONS] = destination(
            sectionHeader("Session history"),
            cardGroup(sessionsSummaryView, recentSessionsList,
                sessionsMoreView, sessionsButton))
        // Step 5 workspace: role contract, live
        // summary, bounded inline parent cards with
        // recommendation/experiment kinds, and the
        // explicit workspace entry. Nothing inline
        // approves, requests, or runs.
        destinations[NavTab.AGENT] = destination(
            sectionHeader("Meta Muse Workspace"),
            cardGroup(agentHintView),
            sectionHeader("Sessions"),
            cardGroup(agentSummaryView, agentWorkspaceList,
                agentMoreView, agentButton))
        val helpButton = Button(this).apply { text = "Help" }
        helpButton.setOnClickListener { showHelp() }
        memDiagButton = Button(this).apply {
            text = memDiagLabel()
        }
        memDiagButton.setOnClickListener { showMemDiag() }
        val globalButton = Button(this).apply {
            text = "EdgeFit Global Connection"
        }
        globalButton.setOnClickListener { showGlobalConnection() }
        // Step 5 settings: six groups from the
        // AeroSettings model — only real controls, no
        // fake toggles, no secrets, BYOK separate from
        // the Global token. Copy renders from the model
        // so the tested spec and the screen agree.
        fun cap(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(secondaryColor())
        }
        fun info(group: AeroSettings.Group): TextView =
            TextView(this).apply {
                text = AeroSettings.rows(group, settingsCtx())
                    .filter { it.action == null }
                    .joinToString("\n\n") {
                        "${it.label}: ${it.detail}"
                    }
                textSize = 13f
                setTextColor(inkColor())
            }
        fun detail(
            group: AeroSettings.Group,
            action: AeroSettings.Action
        ): String = settingDetail(group, action) ?: ""
        settingsMetaView = TextView(this).apply {
            textSize = 13f
            setTextColor(secondaryColor())
        }
        settingsGlobalView = TextView(this).apply {
            textSize = 13f
            setTextColor(secondaryColor())
        }
        val settingsCatalogButton = Button(this).apply {
            text = "Recommended Models"
        }
        settingsCatalogButton.setOnClickListener {
            showModelCatalog()
        }
        val settingsDeviceButton = Button(this).apply {
            text = "Device Details"
        }
        settingsDeviceButton.setOnClickListener {
            showLiveDeviceDetails()
        }
        val settingsHistoryButton = Button(this).apply {
            text = "Contribution History"
        }
        settingsHistoryButton.setOnClickListener {
            showContributionHistory()
        }
        val settingsReviewButton = Button(this).apply {
            text = "Review or withdraw uploads"
        }
        settingsReviewButton.setOnClickListener {
            showContributionHistory()
        }
        destinations[NavTab.SETTINGS] = destination(
            sectionHeader(AeroSettings.title(
                AeroSettings.Group.APPEARANCE)),
            cap(AeroSettings.helper(
                AeroSettings.Group.APPEARANCE)),
            cardGroup(info(AeroSettings.Group.APPEARANCE)),
            sectionHeader(AeroSettings.title(
                AeroSettings.Group.BENCHMARK)),
            cap(AeroSettings.helper(
                AeroSettings.Group.BENCHMARK)),
            cardGroup(workloadButton,
                cap(detail(AeroSettings.Group.BENCHMARK,
                    AeroSettings.Action.WORKLOAD)),
                settingsCatalogButton,
                cap(detail(AeroSettings.Group.BENCHMARK,
                    AeroSettings.Action.MODEL_CATALOG)),
                memDiagButton,
                cap(detail(AeroSettings.Group.BENCHMARK,
                    AeroSettings.Action.MEM_DIAG)),
                settingsDeviceButton,
                cap(detail(AeroSettings.Group.BENCHMARK,
                    AeroSettings.Action.DEVICE_DETAILS))),
            sectionHeader(AeroSettings.title(
                AeroSettings.Group.META)),
            cap(AeroSettings.helper(AeroSettings.Group.META)),
            cardGroup(settingsMetaView, metaButton,
                cap(detail(AeroSettings.Group.META,
                    AeroSettings.Action.META_KEY))),
            sectionHeader(AeroSettings.title(
                AeroSettings.Group.GLOBAL)),
            cap(AeroSettings.helper(
                AeroSettings.Group.GLOBAL)),
            cardGroup(settingsGlobalView, globalButton,
                cap(detail(AeroSettings.Group.GLOBAL,
                    AeroSettings.Action.GLOBAL_CONNECTION)),
                settingsHistoryButton,
                cap(detail(AeroSettings.Group.GLOBAL,
                    AeroSettings.Action.CONTRIBUTION_HISTORY))),
            sectionHeader(AeroSettings.title(
                AeroSettings.Group.PRIVACY)),
            cap(AeroSettings.helper(
                AeroSettings.Group.PRIVACY)),
            cardGroup(info(AeroSettings.Group.PRIVACY),
                export,
                cap(detail(AeroSettings.Group.PRIVACY,
                    AeroSettings.Action.EXPORT_PROFILE)),
                settingsReviewButton),
            sectionHeader(AeroSettings.title(
                AeroSettings.Group.ABOUT)),
            cap(AeroSettings.helper(AeroSettings.Group.ABOUT)),
            cardGroup(aboutView, helpButton,
                cap(detail(AeroSettings.Group.ABOUT,
                    AeroSettings.Action.HELP_GUIDE))))
        // Aero button pass: primaries were styled at
        // creation; everything else gets the secondary
        // treatment for the dark shell.
        for (button in listOf(export, cancelButton, saveButton,
            select, workloadButton, sessionsButton, metaButton,
            agentButton, deviceDetailsButton, metaGoButton,
            benchModelsButton, memCheckButton, configButton,
            resultDetailsButton, contributeButton, importButton,
            historyButton, helpButton, memDiagButton,
            globalButton, settingsCatalogButton,
            settingsDeviceButton, settingsHistoryButton,
            settingsReviewButton) +
            quickButtons) {
            AeroUi.styleSecondary(this, button)
        }
        topBar = AeroTopBar(this)
        contentFrame = FrameLayout(this).apply {
            setBackgroundColor(paperColor())
            for ((_, view) in destinations) {
                addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
        // Aero floating dock: detached rounded navy bar
        // with 16dp side margins, in normal flow so content
        // never slides underneath it on small screens.
        dock = AeroDockView(this).apply {
            onSelect = { selectTab(it) }
        }
        val dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(paperColor())
            setPadding(dp(16), dp(4), dp(16), dp(8))
            addView(dock, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paperColor())
            addView(topBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(contentFrame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(statusView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(dockRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(bars[0], bars[1], bars[2], bars[3])
            insets
        }
        shellRoot = shell
        benchScreen = BenchScreenView(this).apply {
            visibility = View.GONE
            onCancel = {
                setRunning(false)
                runner.cancel()
            }
            onSave = {
                val json = benchJson
                if (json == null) {
                    statusText("No benchmark result to save.")
                } else {
                    pendingJson = json
                    pendingFilename = resultFilename()
                    launchSaver()
                }
            }
            // During a run Back stays put (Cancel or completion
            // first); otherwise the bench overlay returns to tabs.
            onBack = { if (!benchActive) showMain() }
            onWorkload = {
                val result = lastResult
                if (result == null) {
                    statusText("No benchmark result yet.")
                } else {
                    BenchWorkloadView.show(
                        this@MainActivity, result.config,
                        "%s (%d MB)".format(
                            result.model.displayName,
                            result.model.sizeBytes / 1048576),
                        result.measurements.prefillTokens
                    )
                }
            }
            // EF-MEM-03 recovery: assistance, catalog,
            // and evidence only. None of these starts
            // inference.
            onRecover = { showRecoveryAssistant(null) }
            onModels = { showModelCatalog() }
            onFailureDetails = {
                val result = lastResult
                if (result == null) {
                    statusText("No benchmark result yet.")
                } else {
                    CardDialog.show(this@MainActivity,
                        "Failure Details",
                        InsightCards.failureDetailSections(result))
                }
            }
        }
        benchScreen.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(bars[0], bars[1], bars[2], bars[3])
            insets
        }
        val root = FrameLayout(this).apply {
            addView(shellRoot, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            addView(benchScreen, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        // Aero chrome: dark system bars + window backdrop
        // so the shell never flashes light, and the system
        // action bar stays hidden behind the Aero top bar.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(paperColor()))
        window.statusBarColor = paperColor()
        window.navigationBarColor = paperColor()
        applyBenchChrome(dark = true)
        currentTab = NavTab.fromName(savedInstanceState?.getString(KEY_TAB))
        selectTab(currentTab)
        refresh()
        refreshSessionLine()
        // EF-MEM-02 checkpoint A: UI built, pre-interaction.
        diagNote(MemDiag.Checkpoint.APP_IDLE)
        export.setOnClickListener {
            val json = refresh()
            if (json == null) {
                statusText("Collection failed; nothing exported.")
                return@setOnClickListener
            }
            pendingJson = json
            pendingFilename = profileFilename()
            launchSaver()
        }
        select.setOnClickListener { launchPicker() }
        workloadButton.setOnClickListener {
            val model = staged
            BenchWorkloadView.show(
                this, currentBenchConfig(),
                model?.let {
                    "%s (%d MB)".format(it.name, it.bytes / 1048576)
                },
                lastResult?.measurements?.prefillTokens
            )
        }
        runButton.setOnClickListener { startBench() }
        sessionsButton.setOnClickListener { showSessions() }
        metaButton.setOnClickListener { showMetaSettings() }
        agentButton.setOnClickListener { showAgent(null) }
        cancelButton.setOnClickListener {
            runner.cancel()
            statusText("Cancellation requested.")
        }
        saveButton.setOnClickListener {
            val json = benchJson
            if (json == null) {
                statusText("No benchmark result to save.")
                return@setOnClickListener
            }
            pendingJson = json
            pendingFilename = resultFilename()
            launchSaver()
        }
        // EF-UX-01D cold-start evaluation (design E gating).
        maybeShowOnboarding()
        refreshEvidenceLine()
    }

    override fun onResume() {
        super.onResume()
        // A picker result consumes this resume: the user
        // just chose a file, not asked for the guide.
        if (justPicked) justPicked = false
        else maybeShowOnboarding()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        updateTelemetrySubscriptions()
    }

    override fun onStop() {
        activityStarted = false
        updateTelemetrySubscriptions()
        runner.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        runner.cancel()
        runner.setProgressListener(null)
        try {
            runner.joinGracefully(1000)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun showBenchScreen() {
        applyBenchChrome(dark = true)
        benchScreen.visibility = View.VISIBLE
        shellRoot.visibility = View.GONE
    }

    private fun showMain() {
        // Aero main shell is dark like the bench screen.
        applyBenchChrome(dark = true)
        benchScreen.visibility = View.GONE
        shellRoot.visibility = View.VISIBLE
        selectTab(currentTab)
    }

    // ---- navigation shell (EF-UX-01A) ----

    private fun selectTab(tab: NavTab) {
        currentTab = tab
        for ((candidate, view) in destinations) {
            view.visibility =
                if (candidate == tab) View.VISIBLE else View.GONE
        }
        dock.setSelected(tab)
        topBar.setTab(tab)
        refreshNextAction()
        updateTelemetrySubscriptions()
        // EF-MEM-02 lifecycle checkpoints (transition-only,
        // never periodic): Home display and config-ready.
        if (tab == NavTab.HOME) {
            diagNote(MemDiag.Checkpoint.HOME_SHOWN)
        }
        if (tab == NavTab.BENCHMARK) {
            diagNote(MemDiag.Checkpoint.CONFIG_READY)
            updateMemReadinessLine()
            refreshBenchConfigLine()
            refreshBenchAuthLine()
        }
    }

    /**
     * EF-COMP-01B-ROUTE: exact pre-run config line. Bound
     * sessions name the session (manual selection is
     * ignored there); unbound runs show the effective
     * triple + its source. Mirrors startBench resolution.
     */
    private fun refreshBenchConfigLine() {
        if (!::benchConfigView.isInitialized) return
        benchConfigView.text = BenchWorkflow.configLine(
            activeSessionId, standaloneOverride,
            currentBenchConfig())
    }

    /**
     * Step 3 Run-step authorization line. Honest about
     * NOT AUTHORIZED; never authorizes or binds — the
     * bind gate and the state machine still own that.
     */
    private fun refreshBenchAuthLine() {
        if (!::benchAuthView.isInitialized) return
        val id = activeSessionId
        val record = try {
            id?.let { sessionManager.get(it) }
        } catch (_: Exception) {
            null
        }
        benchAuthView.text = BenchWorkflow.authLine(id, record)
    }

    /**
     * EF-COMP-01B-ROUTE: explicit manual config dialog.
     * Offers device default + bounded A/B; validates
     * through production rules; writes standaloneOverride
     * (or clears it). Never starts a run.
     */
    private fun showManualBenchConfig() {
        val logical = try {
            DeviceCapabilityCollector(this).collect()
                .device.logicalProcessors
        } catch (_: Exception) {
            BenchConfig.N_THREADS
        }
        val options = ManualBenchConfig.options(logical)
        val current = standaloneOverride
        val checked = options.indexOfFirst { option ->
            option.config == current
        }.coerceAtLeast(0)
        val items = options.map { option ->
            (if (option.supported) "" else "[unavailable] ") +
                option.label + "\n" + option.detail
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Benchmark config (unbound runs)" +
                if (activeSessionId == null) "" else
                    " — session bound, selection parked")
            .setSingleChoiceItems(items, checked, null)
            .setPositiveButton("Apply") { dialog, _ ->
                val list =
                    (dialog as AlertDialog).listView
                val position = list.checkedItemPosition
                if (position < 0 || position >= options.size) return@setPositiveButton
                val chosen = options[position]
                when (val result = ManualBenchConfig.choose(
                    chosen.id, logical)
                ) {
                    is ManualBenchConfig.Choice.Selected -> {
                        standaloneOverride = result.config
                        refreshBenchConfigLine()
                        refreshModelLine()
                        statusText(
                            if (result.config == null) {
                                "Config: device default."
                            } else {
                                "Config: manual " +
                                    "${result.config.nCtx}/" +
                                    "${result.config.nThreads}/" +
                                    "${result.config.nPredict}."
                            })
                    }
                    is ManualBenchConfig.Choice.Refused ->
                        statusText(
                            "Config refused: ${result.reason}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (benchScreen.visibility == View.VISIBLE) {
            // Matches the bench screen's own Back control.
            if (!benchActive) showMain()
            return
        }
        val target = NavTab.backTarget(currentTab)
        if (target == null) super.onBackPressed()
        else selectTab(target)
    }

    // Aero shell palette (Step 2): the main shell is
    // dark; dialogs keep their light styling untouched.
    private fun inkColor(): Int =
        resources.getColor(R.color.aero_text_primary, theme)

    private fun secondaryColor(): Int =
        resources.getColor(R.color.aero_text_secondary, theme)

    private fun accentColor(): Int =
        resources.getColor(R.color.aero_sky, theme)

    private fun errorColor(): Int =
        resources.getColor(R.color.aero_error, theme)

    private fun paperColor(): Int =
        resources.getColor(R.color.aero_page, theme)

    /**
     * Advisory next-action line. Recomputed at navigation and
     * key state changes (never per keystroke); store scans are
     * small and fail closed to all-false.
     */
    private fun refreshNextAction() {
        refreshDashboard()
    }

    /**
     * UX-01BC Home dashboard content: model card, last
     * durable measurement, Meta state, and the derived
     * next action. Durable history (not just this
     * process's last run) feeds the completion check so a
     * relaunch does not suggest re-measuring blindly.
     */
    private fun refreshDashboard() {
        var pendingProposal = false
        var awaitingFollowUp = false
        var lastRun: HomeDashboard.LastRun? = null
        var records: List<SessionRecord> = emptyList()
        try {
            records = sessionManager.listSessions()
            for (record in records) {
                @Suppress("UNCHECKED_CAST")
                val agent =
                    record.agent as? Map<String, Any?>
                if (record.mode == "agent" &&
                    agent?.get("pending_proposal") != null) {
                    pendingProposal = true
                }
                if (record.followup == null &&
                    record.state == SessionState.COMPARISON_READY) {
                    awaitingFollowUp = true
                }
            }
            lastRun = HomeDashboard.lastMeasured(records)
        } catch (_: Exception) {
        }
        val state = NextActionState(
            modelStaged = staged != null,
            hasCompletedResult =
                lastResult?.status == BenchContract.STATUS_COMPLETED ||
                    lastRun != null,
            pendingProposalReview = pendingProposal,
            comparisonAwaitingFollowUp = awaitingFollowUp
        )
        pendingNextAction = nextAction(state)
        nextActionView.text = NextAction.label(pendingNextAction)
        nextActionButton.text =
            HomeDashboard.primaryAction(pendingNextAction)
        val model = staged
        val readiness = HomeDashboard.modelReadiness(
            model?.name, model?.sha256, model?.bytes ?: 0L,
            records)
        modelReadinessView.text =
            HomeDashboard.readinessLabel(readiness.state)
        modelCardView.text = readiness.lines.joinToString("\n")
        val modelInk = readiness.state !=
            HomeDashboard.ModelReadiness.NONE
        modelReadinessView.setTextColor(
            if (modelInk) inkColor() else secondaryColor())
        modelCardView.setTextColor(
            if (modelInk) inkColor() else secondaryColor())
        lastRunView.text =
            HomeDashboard.lastRunLines(lastRun).joinToString("\n")
        lastRunView.setTextColor(
            if (lastRun == null) secondaryColor() else inkColor())
        lastProfile?.let {
            deviceTitleView.text = HomeDashboard.deviceTitle(it)
        }
        metaLineView.text = MetaView.keyStatusLine(
            metaVault.hasSession(), metaVault.sessionSuffix(),
            metaVault.hasPersisted())
        val head = AeroSessions.headerLine(records.size)
        val activeLine = try {
            val bound = activeSessionId
            if (bound == null) SessionView.buildActiveLine(null, null)
            else SessionView.buildActiveLine(bound, sessionManager.get(bound))
        } catch (_: Exception) {
            SessionView.buildActiveLine(null, null)
        }
        sessionsSummaryView.text = "$head\n$activeLine"
        refreshResultHero(records)
        refreshSessionsList(records)
        // Step 5 workspace: honest headline (real
        // pending count, newest active budget) plus
        // bounded inline cards. Read-only: approval,
        // budgets, and gates still live in the dialog.
        val workspace = AeroAgent.summarize(records)
        agentSummaryView.text = workspace.headline +
            (workspace.budgetLine?.let { "\n$it" } ?: "")
        refreshAgentWorkspace(records)
        refreshSettingsLines()
    }

    /**
     * EF-UX-AERO-01 Step 4 result hero: this launch's run
     * with measured/predicted/unavailable discipline
     * (AeroResult). Rebuilt on every dashboard refresh
     * from lastResult plus the already-loaded records
     * (no extra I/O). The live chart stays on the bench
     * screen, fed only by real sampler pairs — no chart
     * is invented here.
     */
    private fun refreshResultHero(records: List<SessionRecord>) {
        resultHeroBox.removeAllViews()
        val result = lastResult
        if (result == null) {
            resultDetailsButton.isEnabled = false
            resultHeroBox.addView(AeroUi.bodyText(this,
                "No benchmark result this launch."))
            resultHeroBox.addView(AeroUi.captionText(this,
                "Past measurements live in Sessions."))
            return
        }
        resultDetailsButton.isEnabled = true
        val hero = AeroResult.hero(result)
        val session =
            AeroResult.attachedSession(records, result.runId)
        resultHeroBox.addView(TextView(this).apply {
            text = hero.genValue
            textSize = 22f
            setTypeface(typeface,
                android.graphics.Typeface.BOLD)
            setTextColor(AeroUi.originColor(
                this@MainActivity, hero.genOrigin))
            contentDescription =
                "${hero.genValue}, ${hero.genCaption}"
        })
        resultHeroBox.addView(AeroUi.captionText(this,
            hero.genCaption))
        resultHeroBox.addView(TextView(this).apply {
            text = "Prefill ${hero.prefillValue}"
            textSize = 16f
            setTextColor(AeroUi.originColor(
                this@MainActivity, hero.prefillOrigin))
            contentDescription = "Prefill " +
                "${hero.prefillValue}, ${hero.prefillCaption}"
        })
        resultHeroBox.addView(AeroUi.captionText(this,
            hero.prefillCaption))
        resultHeroBox.addView(TextView(this).apply {
            text = hero.statusLine
            textSize = 14f
            setTextColor(if (result.status ==
                BenchContract.STATUS_FAILED ||
                result.status == BenchContract.STATUS_TIMEOUT
            ) {
                errorColor()
            } else {
                inkColor()
            })
        })
        resultHeroBox.addView(AeroUi.bodyText(this,
            AeroResult.bindingChip(result.runId, session)))
        AeroResult.deltaLine(session)?.let { delta ->
            resultHeroBox.addView(AeroUi.bodyText(this, delta))
        }
        for (row in AeroResult.specRows(result)) {
            resultHeroBox.addView(TextView(this).apply {
                text = "${row.label}: ${row.value}"
                textSize = 13f
                setTextColor(if (row.origin ==
                    AeroResult.Origin.MEASURED
                ) {
                    inkColor()
                } else {
                    secondaryColor()
                })
            })
        }
    }

    /**
     * EF-UX-AERO-01 Step 4 inline evidence library: the
     * newest sessions as bounded cards (timestamps,
     * model, measured rate, prediction delta, evidence
     * and contribution state). At most INLINE_MAX cards
     * inflate, so 0/1/62/large histories all render
     * without jank; the browser dialog holds the rest
     * with its tested filters. Evidence resolves
     * best-effort per card: one missing artifact never
     * blanks the list.
     */
    private fun refreshSessionsList(records: List<SessionRecord>) {
        recentSessionsList.removeAllViews()
        if (records.isEmpty()) {
            for (line in AeroSessions.emptyLines()) {
                recentSessionsList.addView(
                    AeroUi.captionText(this, line))
            }
            sessionsMoreView.text = ""
            sessionsMoreView.visibility = View.GONE
            return
        }
        val evidenceFor:
            (SessionRecord) -> AeroSessions.EvidenceInfo? =
            { record ->
                val runId = AeroSessions.runIdOf(record)
                if (runId == null) {
                    null
                } else {
                    try {
                        AeroSessions.evidenceInfo(
                            evidenceStore.load(runId))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        val cards = AeroSessions.visibleCards(records,
            SessionListUi.latestId(records), activeSessionId,
            evidenceFor)
        val density = resources.displayMetrics.density
        val gap = (12 * density + 0.5f).toInt()
        for ((index, card) in cards.withIndex()) {
            recentSessionsList.addView(sessionCardView(card),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply {
                        if (index > 0) topMargin = gap
                    })
        }
        val more = AeroSessions.showMoreLine(
            records.size, cards.size)
        if (more == null) {
            sessionsMoreView.text = ""
            sessionsMoreView.visibility = View.GONE
        } else {
            sessionsMoreView.text = more
            sessionsMoreView.visibility = View.VISIBLE
        }
    }

    /**
     * One inline session card with details navigation.
     * Details opens the session browser focused on this
     * record; bind/predict/follow-up stay in the browser
     * (no new behavior inline).
     */
    private fun sessionCardView(card: AeroSessions.Card): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(TextView(this).apply {
            text = card.title
            textSize = 14f
            setTypeface(typeface,
                android.graphics.Typeface.BOLD)
            setTextColor(if (card.isLatest) accentColor()
            else inkColor())
        })
        column.addView(AeroUi.captionText(this,
            card.createdLocal))
        column.addView(AeroUi.bodyText(this, card.modelLine))
        column.addView(AeroUi.bodyText(this, card.stateLine))
        column.addView(TextView(this).apply {
            text = card.measuredLine
            textSize = 14f
            setTypeface(typeface,
                android.graphics.Typeface.BOLD)
            setTextColor(if (card.measuredPresent) {
                AeroUi.originColor(this@MainActivity,
                    AeroResult.Origin.MEASURED)
            } else {
                secondaryColor()
            })
        })
        card.deltaLine?.let { delta ->
            column.addView(AeroUi.bodyText(this, delta))
        }
        column.addView(AeroUi.captionText(this,
            card.evidenceLine))
        val details = Button(this).apply {
            text = "Details"
            contentDescription =
                AeroSessions.detailsDescription(card)
        }
        AeroUi.styleGhost(this, details)
        details.setOnClickListener {
            showSessions(card.sessionId)
        }
        column.addView(details)
        return column
    }

    /**
     * EF-UX-AERO-01 Step 5 inline agent workspace: at
     * most INLINE_MAX parents, each with status/budget,
     * the pending recommendation (if any), the newest
     * experiment card, and the abstention line (if any).
     * Read-only: request/approve/modify/reject/finish
     * stay behind the explicit workspace entry, and the
     * gate line mirrors the dialog without enforcing.
     */
    private fun refreshAgentWorkspace(records: List<SessionRecord>) {
        agentWorkspaceList.removeAllViews()
        val agents = records.filter { it.mode == "agent" }
        if (agents.isEmpty()) {
            // Headline already says none; show the
            // designed follow-ups only.
            for (line in AeroAgent.emptyLines().drop(1)) {
                agentWorkspaceList.addView(
                    AeroUi.captionText(this, line))
            }
            agentMoreView.text = ""
            agentMoreView.visibility = View.GONE
            return
        }
        val childrenByParent = records.groupBy { it.parentSessionId }
        val density = resources.displayMetrics.density
        val gap = (12 * density + 0.5f).toInt()
        val visible = agents.sortedByDescending { it.updatedAtUtc }
            .take(AeroAgent.INLINE_MAX)
        for ((index, parent) in visible.withIndex()) {
            agentWorkspaceList.addView(
                agentParentView(parent,
                    childrenByParent[parent.sessionId]
                        ?: emptyList()),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply {
                        if (index > 0) topMargin = gap
                    })
        }
        val more = AeroAgent.showMoreLine(
            agents.size, visible.size)
        if (more == null) {
            agentMoreView.text = ""
            agentMoreView.visibility = View.GONE
        } else {
            agentMoreView.text = more
            agentMoreView.visibility = View.VISIBLE
        }
    }

    /** One inline parent block with its newest cards. */
    private fun agentParentView(
        parent: SessionRecord,
        children: List<SessionRecord>
    ): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(TextView(this).apply {
            text = "Agent ${parent.sessionId}"
            textSize = 14f
            setTypeface(typeface,
                android.graphics.Typeface.BOLD)
            setTextColor(inkColor())
        })
        for (line in AeroAgent.sessionLines(
            parent, children.size)) {
            column.addView(AeroUi.bodyText(this, line))
        }
        // Active without pending: mirror the gate so a
        // blocked prerequisite is visible inline. The
        // dialog still owns enforcement; a read failure
        // reads as unavailable, never as open.
        if (AeroAgent.isActive(parent) &&
            !AeroAgent.hasPending(parent)
        ) {
            val gate = try {
                agentManager.proposalGate(parent.sessionId)
            } catch (e: Exception) {
                null
            }
            if (gate == null) {
                column.addView(AeroUi.captionText(this,
                    "Proposal gate unavailable."))
            } else {
                AeroAgent.gateLine(gate.first, gate.second)
                    ?.let { column.addView(
                        AeroUi.captionText(this, it)) }
            }
        }
        AeroAgent.proposalCard(parent)?.let { card ->
            column.addView(agentCardView(card))
        }
        for (card in AeroAgent.recentCards(children, max = 1)) {
            column.addView(agentCardView(card))
        }
        AeroAgent.abstentionLine(parent, children)?.let {
            column.addView(AeroUi.captionText(this, it))
        }
        return column
    }

    /** One kind-labeled workspace card (inline). */
    private fun agentCardView(card: AeroAgent.Card): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            setPadding(0, (6 * density + 0.5f).toInt(), 0, 0)
        }
        column.addView(TextView(this).apply {
            text = AeroAgent.kindLabel(card.kind)
            textSize = 12f
            letterSpacing = 0.08f
            isSingleLine = true
            setTextColor(AeroUi.agentKindColor(
                this@MainActivity, card.kind))
        })
        column.addView(TextView(this).apply {
            text = card.title
            textSize = 14f
            setTypeface(typeface,
                android.graphics.Typeface.BOLD)
            setTextColor(inkColor())
        })
        for (line in card.lines) {
            column.addView(AeroUi.bodyText(this, line))
        }
        return column
    }

    /**
     * Step 5 settings status lines from real state: the
     * Meta key line mirrors Home, and the Global line
     * mirrors the connection dialog's honest states.
     * Vault reads fail open to unconfigured text.
     */
    private fun refreshSettingsLines() {
        if (!::settingsMetaView.isInitialized) return
        // Rendered from the model so the tested spec
        // and the screen share one copy source.
        val meta = AeroSettings.rows(
            AeroSettings.Group.META, settingsCtx())
            .firstOrNull { it.action == null }
        settingsMetaView.text = if (meta == null) {
            "Meta API key: status unavailable."
        } else {
            "${meta.label}: ${meta.detail}"
        }
        val global = AeroSettings.rows(
            AeroSettings.Group.GLOBAL, settingsCtx())
            .firstOrNull { it.action == null }
        settingsGlobalView.text = if (global == null) {
            AeroContribute.connectionLine(globalOrigin, null)
        } else {
            "${global.label}: ${global.detail}"
        }
    }

    /** Real-state context for the AeroSettings model. */
    private fun settingsCtx(): AeroSettings.Context {
        val suffix = try {
            globalVault.suffix()
        } catch (_: Exception) {
            null
        }
        val persisted = try {
            globalVault.hasPersisted()
        } catch (_: Exception) {
            false
        }
        val keyStatus = try {
            MetaView.keyStatusLine(metaVault.hasSession(),
                metaVault.sessionSuffix(), metaVault.hasPersisted())
        } catch (_: Exception) {
            "status unavailable."
        }
        return AeroSettings.Context(
            metaKeyStatus = keyStatus,
            globalOrigin = globalOrigin,
            globalTokenSuffix = suffix,
            globalTokenPersisted = persisted,
            versionName = BuildConfig.VERSION_NAME,
            packageName = packageName,
            workloadId = BenchContract.WORKLOAD_ID)
    }

    /** Static caption detail for one settings action row. */
    private fun settingDetail(
        group: AeroSettings.Group,
        action: AeroSettings.Action
    ): String? = AeroSettings.rows(group, settingsCtx())
        .firstOrNull { it.action == action }?.detail

    /**
     * Full result sections: measured rows, raw IDs,
     * binding, and the stored prediction/comparison.
     */
    private fun showResultDetails() {
        val result = lastResult
        if (result == null) {
            statusText("No benchmark result yet.")
            return
        }
        val session = try {
            AeroResult.attachedSession(
                sessionManager.listSessions(), result.runId)
        } catch (_: Exception) {
            null
        }
        CardDialog.show(this, "Result Details",
            AeroResult.detailSections(result, session))
    }

    /**
     * Hides the system action-bar header (the Aero top bar
     * owns branding now) and keeps status/navigation icons
     * legible for dark surfaces. Both the main shell and
     * the bench screen are dark, so callers always pass
     * dark = true; the restoration branch stays for safety.
     */
    private fun applyBenchChrome(dark: Boolean) {
        if (dark) actionBar?.hide() else actionBar?.show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            val current = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (dark) current and flags.inv() else current or flags
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_JSON, pendingJson)
        outState.putString(KEY_PENDING_FILENAME, pendingFilename)
        outState.putString(KEY_ACTIVE_SESSION, activeSessionId)
        outState.putString(KEY_TAB, currentTab.name)
        outState.putInt(KEY_ONBOARDING_PAGE, onboardingPage)
    }

    private fun systemBarInsets(insets: WindowInsets): IntArray {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            val bars = insets.systemWindowInsets
            intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
        }
    }

    private fun refresh(): String? {
        return try {
            val profile = DeviceCapabilityCollector(this).collect()
            val json = ProfileSerializer.serialize(profile)
            lastProfile = profile
            lastProfileDetails = humanSummary(profile)
            statusView.text = "Ready."
            refreshHomeHealth()
            refreshDashboard()
            json
        } catch (e: Exception) {
            deviceTitleView.text = "Collection failed."
            statusView.text = "Error: ${e.message}"
            null
        }
    }

    // ---- live telemetry (EF-UX-01E) ----

    private fun mainScheduler(): DeviceTelemetry.Scheduler {
        val handler = Handler(Looper.getMainLooper())
        return object : DeviceTelemetry.Scheduler {
            override fun postDelayed(
                task: Runnable,
                delayMs: Long
            ): DeviceTelemetry.Cancellable {
                handler.postDelayed(task, delayMs)
                return DeviceTelemetry.Cancellable {
                    handler.removeCallbacks(task)
                }
            }
        }
    }

    /**
     * Visibility gating: sampling runs only while the
     * activity is started AND Home or Device Details is
     * visible. Called from selectTab, onStart, onStop,
     * and dialog open/dismiss. Idempotent.
     */
    private fun updateTelemetrySubscriptions() {
        val want =
            activityStarted && (currentTab == NavTab.HOME || detailsOpen)
        if (want && homeTicker == null) {
            homeTicker = DeviceTelemetry.Ticker(
                DeviceTelemetry.HOME_MS, mainScheduler()) {
                refreshHomeHealth()
            }.also { it.start() }
            Log.d("EdgeFitTelemetry", "home ticker started")
        } else if (!want && homeTicker != null) {
            homeTicker?.stop()
            homeTicker = null
            Log.d("EdgeFitTelemetry", "home ticker stopped")
        }
        if (want && telemetryReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?
                ) {
                    // Battery changed: re-sample now
                    // rather than waiting for the tick.
                    refreshHomeHealth()
                    refreshDetailsView()
                }
            }
            telemetryReceiver = receiver
            registerReceiver(receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            Log.d("EdgeFitTelemetry", "battery receiver registered")
        } else if (!want && telemetryReceiver != null) {
            try {
                unregisterReceiver(telemetryReceiver)
            } catch (_: Exception) {
            }
            telemetryReceiver = null
            Log.d("EdgeFitTelemetry", "battery receiver unregistered")
        }
        if (want) {
            refreshHomeHealth()
            refreshDetailsView()
        }
    }

    /** Static identity plus a fresh live-health sample. */
    private fun refreshHomeHealth() {
        val profile = lastProfile ?: return
        if (currentTab != NavTab.HOME) return
        val sample = try {
            DeviceTelemetry.sample(this)
        } catch (_: Exception) {
            return
        }
        deviceTitleView.text =
            HomeDashboard.deviceTitle(profile)
        val tiles = HomeDashboard.metricTiles(profile, sample)
        for (tile in tiles) {
            deviceTileValues[tile.label]?.apply {
                text = tile.value
                contentDescription = HomeDashboard
                    .tileDescription(tile.label, tile.value)
            }
        }
        val updated = "Updated: " +
            SessionTimeline.formatClock(
                System.currentTimeMillis())
        deviceUpdatedView.text = if (sample.lowMemory) {
            "$updated · Low-memory signal active"
        } else {
            updated
        }
    }

    private fun showLiveDeviceDetails() {
        val profile = lastProfile
        if (profile == null) {
            MetaView.showResultDialog(this, "Device Details",
                "No profile collected.")
            return
        }
        detailsOpen = true
        val view = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density + 0.5f)
                .toInt()
            setPadding(pad, pad, pad, pad)
        }
        detailsView = view
        detailsTicker = DeviceTelemetry.Ticker(
            DeviceTelemetry.DETAILS_MS, mainScheduler()) {
            refreshDetailsView()
        }.also { it.start() }
        Log.d("EdgeFitTelemetry", "details ticker started")
        refreshDetailsView()
        updateTelemetrySubscriptions()
        AlertDialog.Builder(this)
            .setTitle("Device Details (live)")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null)
            .setOnDismissListener {
                detailsOpen = false
                detailsView = null
                detailsTicker?.stop()
                detailsTicker = null
                Log.d("EdgeFitTelemetry", "details ticker stopped")
                updateTelemetrySubscriptions()
            }
            .show()
    }

    private fun refreshDetailsView() {
        val view = detailsView ?: return
        val profile = lastProfile ?: return
        val sample = try {
            DeviceTelemetry.sample(this)
        } catch (_: Exception) {
            return
        }
        val device = profile.device
        val lines = mutableListOf(
            "Model: ${device.manufacturer} ${device.model} " +
                "(${device.deviceCodename})",
            "Android: ${device.androidRelease} (API ${device.sdkInt})",
            "ABIs: ${device.supportedAbis.joinToString(", ")}",
            "Logical processors: ${device.logicalProcessors}",
            "RAM total: ${DeviceTelemetry.formatRam(device.totalRamBytes)}",
            "",
            "Live (refreshes while open):",
            "Battery: " +
                "${DeviceTelemetry.batteryPctLabel(sample.batteryPct)} · " +
                DeviceTelemetry.chargingLabel(
                    sample.batteryStatus, sample.batteryPlugged),
            "Battery temp: " +
                DeviceTelemetry.batteryTempLabel(sample.batteryTempC),
            "Available RAM: " +
                DeviceTelemetry.formatRam(sample.availRamBytes),
            "Low-memory signal: " +
                if (sample.lowMemory) "yes" else "no",
            "Thermal: " +
                "${DeviceTelemetry.thermalLabel(sample.thermalName)}" +
                (sample.thermalName?.let { " ($it)" } ?: ""),
            "Updated: " +
                SessionTimeline.formatClock(System.currentTimeMillis())
        )
        if (profile.unavailable.isNotEmpty()) {
            lines.add("Unavailable at collection: " +
                profile.unavailable.joinToString(", "))
        }
        view.text = lines.joinToString("\n")
    }

    private fun startBench() {
        val model = staged
        if (model == null) {
            statusText("Select a model file first.")
            return
        }
        if (!LlamaBridge.available) {
            statusText("Benchmark runtime unavailable: ${LlamaBridge.loadError}")
            return
        }
        runner.reset()
        // A bound session dictates the executed config (agent-approved
        // or offline-recorded); standalone runs use the live default.
        // Derivation is identical, so legacy behavior is unchanged.
        val boundId = activeSessionId
        val boundConfig = boundId?.let {
            try {
                SessionBenchBridge.configData(sessionManager.get(it).config)
            } catch (_: Exception) {
                null
            }
        }
        if (boundId != null && boundConfig == null) {
            statusText("Bound session config unreadable; run refused.")
            return
        }
        benchView.text = "Benchmark: running…"
        runButton.isEnabled = false
        cancelButton.isEnabled = true
        saveButton.isEnabled = false
        runHintView.text = BenchWorkflow.runHint(true, true)
        runner.setProgressListener { update ->
            runOnUiThread { benchScreen.render(update) }
        }
        // EF-MEM-03: bound session wins; otherwise an
        // explicit standalone override; otherwise the live
        // default. Display (below) resolves identically.
        val effectiveOverride = boundConfig ?: standaloneOverride
        val started = runner.startWithConfig(
            model.file, model.sha256, model.name,
            effectiveOverride) { result ->
            runOnUiThread { onBenchDone(result) }
        }
        if (!started) {
            runner.setProgressListener(null)
            runButton.isEnabled = true
            cancelButton.isEnabled = false
            statusText("A benchmark is already running.")
            return
        }
        benchActive = true
        bindSessionToRun()
        // AUD-03: show the config the runner executes (bound
        // override wins, then standalone override), never the
        // live default over an approval.
        val config = BenchConfig.displayConfig(
            effectiveOverride, currentBenchConfig())
        val profile = try {
            DeviceCapabilityCollector(this).collect()
        } catch (_: Exception) {
            null
        }
        benchScreen.showRun(
            model.name, model.bytes, model.sha256, config,
            runtimeVersionLabel(), runtimeAbiLabel(profile))
        selectTab(NavTab.BENCHMARK)
        showBenchScreen()
    }

    /** Binds the active authorized session to this run, or unbinds. */
    private fun bindSessionToRun() {
        val id = activeSessionId ?: return
        try {
            sessionManager.noteRunStarted(id)
            statusText("Benchmark running for session $id.")
        } catch (e: Exception) {
            activeSessionId = null
            refreshSessionLine()
            statusText("Session binding failed (${e.message}); standalone run.")
        }
    }

    /**
     * The exact config derivation the runner uses, so pre-run display
     * and execution agree. Post-run display uses result.config.
     */
    private fun currentBenchConfig(): BenchConfigData {
        val profile = try {
            DeviceCapabilityCollector(this).collect()
        } catch (_: Exception) {
            null
        }
        val threads = BenchConfig.effectiveThreads(
            profile?.device?.logicalProcessors ?: BenchConfig.N_THREADS)
        return BenchConfig.default(threads)
    }

    private fun runtimeVersionLabel(): String {
        return try {
            if (LlamaBridge.available) LlamaBridge.nativeVersion()
            else "unavailable"
        } catch (_: Exception) {
            "unavailable"
        }
    }

    private fun runtimeAbiLabel(profile: CollectedProfile?): String {
        return profile?.device?.supportedAbis?.firstOrNull() ?: "unknown"
    }

    private fun onBenchDone(result: BenchResult) {
        runner.setProgressListener(null)
        benchActive = false
        lastResult = result
        updateMemReadinessLine()
        refreshNextAction()
        runButton.isEnabled = true
        cancelButton.isEnabled = false
        runHintView.text = BenchWorkflow.runHint(
            staged != null, false)
        val measurements = result.measurements
        benchView.text = "Benchmark: ${result.status}\n" +
            "Generated ${measurements.generatedTokens} tokens in " +
            "${measurements.genMs} ms " +
            "(${"%.1f".format(measurements.tpsGen)} tok/s)"
        if (result.error != null) {
            benchView.append("\n${result.error}")
        }
        benchScreen.showResult(result)
        try {
            benchJson = BenchResultJson.serialize(result)
            saveButton.isEnabled = true
            statusText("Benchmark ${result.status}. Result ready to save.")
            persistEvidenceAsync(
                benchJson!!, EvidenceStore.PROVENANCE_CAPTURED)
        } catch (e: Exception) {
            benchJson = null
            statusText("Result serialize failed: ${e.message}")
        }
        attachSessionToResult(result)
    }

    /**
     * Attaches the finished run to the bound session off the UI thread.
     * Standalone runs are labeled as such; no prediction is invented.
     */
    private fun attachSessionToResult(result: BenchResult) {
        val id = activeSessionId
        if (id == null) {
            // UX-D02: a COMPLETED unbound run persists as a
            // standalone measurement record (no prediction
            // invented); anything else leaves no record.
            if (result.status == BenchContract.STATUS_COMPLETED) {
                persistStandaloneRun(result)
            } else {
                benchView.append(
                    "\nStandalone physical measurement (no session).")
            }
            return
        }
        activeSessionId = null
        refreshSessionLine()
        SessionBenchBridge.attachAsync(sessionManager, id, result) { outcome ->
            // IO thread here: agent accounting scans the store, so it
            // stays off the UI thread; only the text crosses over.
            val agentNote = outcome.fold(
                onSuccess = { record ->
                    record.parentSessionId?.let { parentId ->
                        try {
                            agentManager.afterExperiment(parentId)?.let {
                                " Agent $it."
                            } ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                    } ?: ""
                },
                onFailure = { "" }
            )
            runOnUiThread {
                outcome.fold(
                    onSuccess = { record ->
                        @Suppress("UNCHECKED_CAST")
                        val comparison =
                            record.comparison as? Map<String, Any?>
                        val detail = if (comparison == null) record.state
                        else SessionView.comparisonLine(comparison)
                        statusText(
                            "Session ${record.sessionId}: $detail.$agentNote")
                        refreshSessionLine()
                        if (comparison != null) {
                            showResultComparison(record, comparison)
                        }
                    },
                    onFailure = { e ->
                        // The bound run will never attach; record the
                        // failure now instead of waiting for recovery.
                        Thread({
                            try {
                                sessionManager.markBenchmarkFailed(
                                    id, "attach failed: ${e.message}")
                            } catch (_: Exception) {
                            }
                            runOnUiThread {
                                statusText(
                                    "Session attach failed: ${e.message}")
                                refreshSessionLine()
                            }
                        }, "edgefit-session-fail").start()
                    }
                )
            }
        }
    }

    /** UX-D02 standalone persist off the UI thread. */
    private fun persistStandaloneRun(result: BenchResult) {
        SessionBenchBridge.recordStandaloneAsync(
            sessionManager, result) { outcome ->
            runOnUiThread {
                outcome.fold(
                    onSuccess = { record ->
                        benchView.append(
                            "\nStandalone measurement recorded " +
                                "as ${record.sessionId}.")
                        refreshSessionLine()
                    },
                    onFailure = { e ->
                        benchView.append(
                            "\nStandalone record failed: ${e.message}")
                    }
                )
            }
        }
    }

    /**
     * EF-UX-02 completion continuity: after a run attaches,
     * bring the user to the result comparison for that same
     * session, offering follow-up only when eligible. The
     * benchmark screen stays underneath; nothing auto-runs
     * and no Meta request fires without its own
     * authorization.
     */
    private fun showResultComparison(
        record: SessionRecord,
        comparison: Map<String, Any?>
    ) {
        val sections = listOf(InsightCards.Section(
            "Prediction vs measured",
            InsightCards.comparisonRows(comparison)))
        val actions = if (SessionListUi.canFollowUp(record)) {
            listOf(CardDialog.Action("Request Follow-Up",
                primary = true) {
                startLiveFollowUp(record.sessionId)
            })
        } else {
            emptyList()
        }
        CardDialog.show(this, "Benchmark Result", sections, actions)
    }

    private fun stageModel(uri: Uri) {
        modelView.text = "Staging model…"
        Thread({
            try {
                val name = queryDisplayName(uri) ?: "model.gguf"
                val out = File(cacheDir, "bench-model.gguf")
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            bytes += n
                            if (bytes > BenchGuards.MAX_MODEL_BYTES) {
                                throw IllegalStateException("model exceeds bound")
                            }
                        }
                    }
                } ?: throw IllegalStateException("cannot open model")
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                staged = StagedModel(out, sha, bytes, name)
                runOnUiThread {
                    // A new artifact invalidates any prior
                    // standalone config override.
                    standaloneOverride = null
                    runButton.isEnabled = true
                    refreshModelLine()
                    refreshBenchConfigLine()
                    diagNote(MemDiag.Checkpoint.MODEL_STAGED)
                    updateMemReadinessLine()
                    refreshNextAction()
                    // Return to the repeat-prep screen that
                    // requested a model, re-evaluated against
                    // the newly staged artifact.
                    val prep = pendingRepeatPrepId
                    pendingRepeatPrepId = null
                    if (prep != null) showRepeatPrep(prep)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    modelView.text = "Stage failed: ${e.message}"
                    pendingRepeatPrepId = null
                }
            }
        }, "edgefit-stage").start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * EF-MEM-03 model line with the standalone override
     * marker. The marker is the ONLY override indicator,
     * so it must never be bypassed by direct assignment.
     */
    private fun refreshModelLine() {
        val model = staged
        modelView.text = BenchWorkflow.modelLine(
            model?.name, model?.bytes ?: 0L,
            standaloneOverride, activeSessionId)
        if (::runHintView.isInitialized) {
            runHintView.text = BenchWorkflow.runHint(
                model != null, benchActive)
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            pickerActive = true
            startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
        } catch (e: Exception) {
            pickerActive = false
            statusText("No document picker available: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun launchSaver() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE,
                pendingFilename ?: profileFilename())
        }
        try {
            pickerActive = true
            startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
        } catch (e: Exception) {
            pickerActive = false
            pendingJson = null
            pendingFilename = null
            statusText("No document picker available: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Picker (or saver) returned: clear the modal flag
        // and consume the follow-up resume so the guide
        // never pops over a just-chosen file.
        pickerActive = false
        justPicked = true
        if (requestCode == REQUEST_OPEN_DOCUMENT) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null) {
                statusText("Model selection cancelled.")
                val prep = pendingRepeatPrepId
                pendingRepeatPrepId = null
                if (prep != null) showRepeatPrep(prep)
            } else {
                stageModel(uri)
            }
            return
        }
        if (requestCode == REQUEST_IMPORT_BENCH) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null) {
                statusText("Result import cancelled.")
            } else {
                importBenchFile(uri)
            }
            return
        }
        if (requestCode != REQUEST_CREATE_DOCUMENT) return
        val json = pendingJson
        pendingJson = null
        pendingFilename = null
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null || json == null) {
            statusText("Export cancelled; nothing written.")
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("cannot open destination")
            val bytes = json.toByteArray(Charsets.UTF_8).size
            saveButton.isEnabled = false
            statusText("Exported $bytes bytes.")
        } catch (e: Exception) {
            statusText("Export failed: ${e.message}")
        }
    }

    private fun profileFilename(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "edgefit-profile-$stamp.json"
    }

    private fun resultFilename(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "edgefit-bench-$stamp.json"
    }

    // ---- EdgeFit Global contribution (EF-GLOBAL-CONTRIBUTE-01) ----

    private fun readGlobalOrigin(): String? {
        return try {
            val file = File(filesDir, "global_origin.txt")
            if (!file.isFile) null
            else GlobalContract.normalizeOrigin(
                file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun saveGlobalOrigin(raw: String): Boolean {
        val origin = GlobalContract.normalizeOrigin(raw) ?: return false
        return try {
            File(filesDir, "global_origin.txt")
                .writeText(origin, Charsets.UTF_8)
            globalOrigin = origin
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun persistEvidenceAsync(json: String, provenance: String) {
        Thread({
            val line = try {
                val record = evidenceStore.store(
                    json, provenance, System.currentTimeMillis())
                GlobalView.readinessLine(record) +
                    " (${record.summary.runId.takeLast(8)})"
            } catch (e: Exception) {
                "Evidence not preserved: ${e.message}"
            }
            runOnUiThread { evidenceLine.text = line }
        }, "edgefit-evidence").start()
    }

    private fun refreshEvidenceLine() {
        Thread({
            // Step 5 preview: readiness plus the
            // device/model/result preview and the honest
            // connection state. Read-only; the review
            // dialog still owns consent and upload.
            val suffix = try {
                globalVault.suffix()
            } catch (_: Exception) {
                null
            }
            val connection =
                AeroContribute.connectionLine(globalOrigin, suffix)
            val pair = try {
                val latest = latestEvidenceId()
                if (latest == null) {
                    AeroContribute.emptyLines()[0] to
                        AeroContribute.emptyLines()[1]
                } else {
                    val record = evidenceStore.load(latest)
                    AeroContribute.evidenceLine(record) to
                        (AeroContribute.previewLines(record) +
                            connection).joinToString("\n")
                }
            } catch (e: Exception) {
                "Evidence unavailable: ${e.message}" to ""
            }
            runOnUiThread {
                evidenceLine.text = pair.first
                contributePreviewView.text = pair.second
            }
        }, "edgefit-evidence").start()
    }

    private fun latestEvidenceId(): String? {
        var best: String? = null
        var bestAt = ""
        for (id in evidenceStore.listIds()) {
            val record = try {
                evidenceStore.load(id)
            } catch (_: Exception) {
                continue
            }
            if (record.storedAtUtc >= bestAt) {
                bestAt = record.storedAtUtc
                best = id
            }
        }
        return best
    }

    private fun launchBenchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            @Suppress("DEPRECATION")
            pickerActive = true
            startActivityForResult(intent, REQUEST_IMPORT_BENCH)
        } catch (e: Exception) {
            pickerActive = false
            statusText("No document picker available: ${e.message}")
        }
    }

    private fun importBenchFile(uri: Uri) {
        Thread({
            val message = try {
                val bytes = ByteArrayOutputStreamBounded(
                    EvidenceStore.MAX_EVIDENCE_BYTES + 1)
                contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes.write(buf, 0, n)
                    }
                } ?: throw IllegalStateException("cannot open file")
                val record = evidenceStore.store(
                    bytes.toByteArray().toString(Charsets.UTF_8),
                    EvidenceStore.PROVENANCE_IMPORTED,
                    System.currentTimeMillis())
                "Imported ${record.summary.runId} " +
                    "(marked imported, not this install)."
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
            runOnUiThread {
                statusText(message)
                refreshEvidenceLine()
            }
        }, "edgefit-evidence").start()
    }

    private fun showContributeLatest() {
        val id = try {
            latestEvidenceId()
        } catch (e: Exception) {
            statusText("Evidence unavailable: ${e.message}")
            return
        }
        if (id == null) {
            statusText("No preserved result: run a benchmark or import one.")
            return
        }
        openContributionRecord(id)
    }

    private fun openContributionRecord(id: String) {
        val record = try {
            evidenceStore.load(id)
        } catch (e: Exception) {
            statusText("Evidence unavailable: ${e.message}")
            return
        }
        if (record.contributionState == GlobalContract.LOCAL_SHARED &&
            record.submissionId != null
        ) {
            showSharedRecord(record)
            return
        }
        val review = try {
            globalClient.review(id)
        } catch (e: Exception) {
            statusText("Review unavailable: ${e.message}")
            return
        }
        val connected = review.origin != null && review.tokenSuffix != null &&
            GlobalContract.CONSENT_POLICY_APPROVED
        GlobalView.showReview(this, review, connected) {
            Thread({
                val outcome = try {
                    globalClient.submit(id)
                } catch (e: Exception) {
                    GlobalClient.SubmitOutcome.Uncertain(
                        "unexpected client error")
                }
                runOnUiThread {
                    showSubmitOutcome(outcome)
                    refreshEvidenceLine()
                }
            }, "edgefit-global").start()
        }
        if (!connected) {
            statusText(
                if (review.origin == null) "Cloud connection not configured."
                else if (review.tokenSuffix == null) "No upload token: open EdgeFit Global Connection."
                else "Contributions disabled until the owner approves consent policy " + GlobalContract.CONSENT_POLICY_VERSION + "."
            )
        }
    }

    private fun showSubmitOutcome(outcome: GlobalClient.SubmitOutcome) {
        when (outcome) {
            is GlobalClient.SubmitOutcome.Shared -> GlobalView.showReceipt(
                this, outcome.submissionId, outcome.eligibility,
                outcome.duplicate)
            is GlobalClient.SubmitOutcome.Rejected -> GlobalView.showOutcome(
                this, "Submission rejected",
                "${outcome.code}: ${outcome.message}", outcome.details)
            is GlobalClient.SubmitOutcome.AuthFailed -> GlobalView.showOutcome(
                this, "Not authorized",
                "${outcome.code}: ${outcome.message}. " +
                    "Check the upload token in EdgeFit Global Connection.")
            is GlobalClient.SubmitOutcome.Quota -> GlobalView.showOutcome(
                this, "Daily quota exceeded",
                "The backend refused another upload today." +
                    (outcome.retryAfterSec?.let {
                        " Retry after $it seconds."
                    } ?: " Try again tomorrow."))
            is GlobalClient.SubmitOutcome.Uncertain -> GlobalView.showOutcome(
                this, "Submission uncertain",
                outcome.reason + ". Nothing was marked shared. " +
                    "Resubmitting the same bytes is safe: the " +
                    "server dedups by content hash.")
            is GlobalClient.SubmitOutcome.Blocked -> statusText(outcome.reason)
        }
    }

    private fun showSharedRecord(record: EvidenceStore.Record) {
        val id = record.summary.runId
        val eligibility = GlobalContract.displayServerState(
            record.serverEligibility)
        GlobalView.showOutcome(
            this, "Shared record",
            "$id · server state: $eligibility" +
                (record.lastStatusAtUtc?.let { " (observed $it)" } ?: "") +
                (record.submissionId?.let { " · $it" } ?: ""))
        // Follow-up actions live in the history view to keep this
        // dialog read-only; refresh/revoke run from there.
    }

    private fun showContributionHistory() {
        Thread({
            val lines = try {
                evidenceStore.listIds().mapNotNull { id ->
                    try {
                        id to GlobalView.historyLine(evidenceStore.load(id))
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText("History unavailable: ${e.message}")
                }
                return@Thread
            }
            runOnUiThread {
                GlobalView.showHistory(this, lines) { id ->
                    showHistoryActions(id)
                }
            }
        }, "edgefit-evidence").start()
    }

    /** Deletes ONE local result. Never touches the cloud: a
     * retained server contribution must be removed with
     * Withdraw consent, and the status line says so. */
    private fun deleteLocalResult(id: String, wasShared: Boolean) {
        Thread({
            val deleted = try {
                evidenceStore.delete(id)
            } catch (e: Exception) {
                runOnUiThread {
                    statusText("Local delete failed: " + e.message)
                }
                return@Thread
            }
            runOnUiThread {
                if (!deleted) {
                    statusText("Local result already gone.")
                } else if (wasShared) {
                    statusText("Local result deleted. Cloud contribution retained: use Withdraw consent to remove it server-side.")
                } else {
                    statusText("Local result deleted.")
                }
                refreshEvidenceLine()
            }
        }, "edgefit-evidence").start()
    }

    private fun showHistoryActions(id: String) {
        val record = try {
            evidenceStore.load(id)
        } catch (e: Exception) {
            statusText("Evidence unavailable: ${e.message}")
            return
        }
        if (record.submissionId == null) {
            CardDialog.show(
                this, "Local result $id",
                listOf(InsightCards.Section(
                    "Not shared",
                    listOf(InsightCards.Row(
                        "Run", id)))),
                listOf(
                    CardDialog.Action("Contribute to EdgeFit Global") {
                        openContributionRecord(id)
                    },
                    CardDialog.Action("Delete local result") {
                        deleteLocalResult(id, wasShared = false)
                    }))
            return
        }
        CardDialog.show(
            this, "Shared record $id",
            listOf(InsightCards.Section(
                GlobalContract.displayServerState(
                    record.serverEligibility),
                listOf(
                    InsightCards.Row(
                        "Submission", record.submissionId ?: "—"),
                    InsightCards.Row(
                        "Observed", record.lastStatusAtUtc ?: "—")
                ))),
            listOf(
                CardDialog.Action("Refresh status") {
                    Thread({
                        val outcome = globalClient.refreshStatus(id)
                        runOnUiThread {
                            when (outcome) {
                                is GlobalClient.StatusOutcome.Updated ->
                                    statusText(
                                        "Server state: " +
                                            GlobalContract.displayServerState(
                                                outcome.eligibility))
                                is GlobalClient.StatusOutcome.Failed ->
                                    statusText(
                                        "Refresh failed " +
                                            "(${outcome.code}): " +
                                            outcome.message)
                            }
                            refreshEvidenceLine()
                        }
                    }, "edgefit-global").start()
                },
                CardDialog.Action("Withdraw consent") {
                    Thread({
                        val outcome = globalClient.revoke(id)
                        runOnUiThread {
                            when (outcome) {
                                GlobalClient.RevokeOutcome.Revoked ->
                                    statusText(
                                        "Consent withdrawn; server " +
                                            "marked the row revoked.")
                                is GlobalClient.RevokeOutcome.Failed ->
                                    statusText(
                                        "Withdraw failed " +
                                            "(${outcome.code}): " +
                                            outcome.message)
                            }
                            refreshEvidenceLine()
                        }
                    }, "edgefit-global").start(),
                CardDialog.Action("Delete local result") {
                    deleteLocalResult(id, wasShared = true)
                }
                }))
    }

    @Suppress("DEPRECATION")
    private fun showGlobalConnection() {
        val originInput = EditText(this).apply {
            hint = "https://staging-host.example"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            globalOrigin?.let { setText(it) }
        }
        val tokenInput = EditText(this).apply {
            hint = "Paste owner-issued upload token"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val remember = android.widget.CheckBox(this).apply {
            text = "Persist token in Keystore (otherwise session-only)"
            isChecked = false
        }
        val suffix = try {
            globalVault.suffix()
        } catch (_: Exception) {
            null
        }
        val state = TextView(this).apply {
            text = "Token: " + (suffix ?: "none") +
                (if (globalVault.hasPersisted()) " (persisted)" else "") +
                "\nOrigin: " + (globalOrigin
                    ?: "Cloud connection not configured") +
                "\nTokens never leave this screen except to the " +
                "configured backend over HTTPS."
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(state)
            addView(originInput)
            addView(tokenInput)
            addView(remember)
        }
        AlertDialog.Builder(this)
            .setTitle("EdgeFit Global Connection")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val originText = originInput.text.toString()
                if (originText.isNotBlank() &&
                    !saveGlobalOrigin(originText)
                ) {
                    statusText("Origin rejected: use plain https URL.")
                    return@setPositiveButton
                }
                val tokenText = tokenInput.text.toString()
                if (tokenText.isNotBlank()) {
                    try {
                        globalVault.setSessionToken(tokenText)
                        if (remember.isChecked) {
                            globalVault.persistSessionToken()
                        }
                        statusText("Upload token set " +
                            (if (remember.isChecked) "(persisted)."
                            else "(session-only)."))
                    } catch (e: Exception) {
                        statusText("Token rejected: ${e.message}")
                    }
                } else if (originText.isNotBlank()) {
                    statusText("Origin saved.")
                }
                refreshEvidenceLine()
            }
            .setNeutralButton("Forget token") { _, _ ->
                if (globalVault.forget()) {
                    statusText("Upload token forgotten everywhere.")
                } else {
                    statusText("No upload token was stored.")
                }
                refreshEvidenceLine()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Bounded sink: refuses input past [cap] bytes. */
    private class ByteArrayOutputStreamBounded(val cap: Int) {
        private val out = java.io.ByteArrayOutputStream()
        @Throws(IllegalStateException::class)
        fun write(buf: ByteArray, offset: Int, length: Int) {
            if (out.size() + length > cap) {
                throw IllegalStateException("file exceeds bound")
            }
            out.write(buf, offset, length)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun statusText(message: String) {
        statusView.text = message
    }

    // ---- sessions (EF-SESSION-01) ----

    private fun showSessions(focusId: String? = null) {
        diagNote(MemDiag.Checkpoint.SESSIONS_OPENED)
        SessionView.show(
            this,
            load = { sessionManager.listSessions() },
            activeId = { activeSessionId },
            onCreate = { gen, prefill -> createOfflineSession(gen, prefill) },
            onBind = { id -> bindSession(id) },
            onDelete = { id -> deleteSession(id) },
            onPredict = { id -> startLivePrediction(id) },
            onFollowUp = { id -> startLiveFollowUp(id) },
            onRepeat = { id -> repeatStandaloneSession(id) },
            onCompare = { id -> showSeriesComparison(id) },
            focusId = focusId,
            onOpenRepeat = { newId -> showRepeatPrep(newId) },
            onClose = { diagNote(MemDiag.Checkpoint.SESSIONS_CLOSED) },
            onRecover = { id -> showRecoveryAssistant(id) },
            // Genuine capacity signal only: free app-private
            // bytes. No session-count ceiling exists anymore.
            storageNote = try {
                "${MemDiag.formatMb(filesDir.usableSpace)} free"
            } catch (_: Exception) {
                null
            }
        )
    }

    /**
     * Creates an offline-test session from the staged model and the
     * exact config the next run will use, then authorizes and binds it.
     * Labeled offline_test throughout; never a Meta prediction.
     */
    private fun createOfflineSession(
        gen: Double,
        prefill: Double
    ): SessionListUi.CreateOutcome {
        val model = staged
            ?: return SessionListUi.CreateOutcome(
                "Stage a model file first.", null)
        return try {
            val profile = try {
                DeviceCapabilityCollector(this).collect()
            } catch (_: Exception) {
                null
            }
            val config = currentBenchConfig()
            val created = sessionManager.createSession(
                mode = "offline_test",
                deviceModel = profile?.device?.model,
                profileSha256 = profile?.let {
                    BenchConfig.sha256Hex(ProfileSerializer.serialize(it)
                        .toByteArray(Charsets.UTF_8))
                },
                modelSha256 = model.sha256,
                modelSizeBytes = model.bytes,
                modelDisplay = model.name,
                workloadId = BenchContract.WORKLOAD_ID,
                promptSha256 = BenchConfig.workload().promptSha256,
                config = SessionBenchBridge.configMap(config)
            )
            sessionManager.requestPrediction(created.sessionId)
            sessionManager.recordPrediction(
                created.sessionId, gen, prefill,
                """{"predicted_gen_tps":$gen,"predicted_prefill_tps":$prefill,"source":"offline_test"}""",
                SessionManager.SOURCE_OFFLINE_TEST, emptyList())
            sessionManager.authorizeBenchmark(
                created.sessionId, "offline test created")
            activeSessionId = created.sessionId
            refreshSessionLine()
            SessionListUi.CreateOutcome(
                "Created ${created.sessionId}, authorized and " +
                    "bound to next run.", created.sessionId)
        } catch (e: Exception) {
            SessionListUi.CreateOutcome(
                "Session creation failed: ${e.message}", null)
        }
    }

    private fun bindSession(id: String): String {
        return try {
            val record = sessionManager.get(id)
            if (record.state != SessionState.BENCHMARK_AUTHORIZED) {
                return "Only authorized sessions bind (state ${record.state})."
            }
            activeSessionId = id
            refreshSessionLine()
            "Bound $id to the next run."
        } catch (e: Exception) {
            "Bind failed: ${e.message}"
        }
    }

    /**
     * EF-FOLLOWUP-02 standalone repeat: new linked session
     * carrying the source prediction, authorized and bound.
     * Refuses when a DIFFERENT model is staged (the repeat
     * re-measures the source artifact); unstaged is fine —
     * the user stages before running.
     */
    private fun repeatStandaloneSession(
        id: String
    ): SessionListUi.CreateOutcome {
        return try {
            val source = sessionManager.get(id)
            val model = staged
            if (model != null &&
                model.sha256.lowercase() !=
                    source.modelSha256.lowercase()
            ) {
                return SessionListUi.CreateOutcome(
                    "Repeat refused: staged model differs from " +
                        "the source run (${source.modelDisplay}).",
                    null)
            }
            val profile = try {
                DeviceCapabilityCollector(this).collect()
            } catch (_: Exception) {
                null
            }
            val created = sessionManager.createRepeat(
                id,
                profile?.device?.model,
                profile?.let {
                    BenchConfig.sha256Hex(ProfileSerializer.serialize(it)
                        .toByteArray(Charsets.UTF_8))
                }
            )
            sessionManager.authorizeBenchmark(
                created.sessionId, "standalone repeat authorized")
            activeSessionId = created.sessionId
            refreshSessionLine()
            SessionListUi.CreateOutcome(
                "Repeat ${created.sessionId} created, authorized " +
                    "and bound; earlier results preserved.",
                created.sessionId)
        } catch (e: Exception) {
            SessionListUi.CreateOutcome(
                "Repeat failed: ${e.message}", null)
        }
    }

    /**
     * EF-UX-02 repeat continuity: the prep screen for a newly
     * created repeat. Shows the source relationship, required
     * model/config, and staging status, then offers exactly
     * one next step — run when the verified model is staged,
     * otherwise select it. Never auto-runs or auto-requests.
     */
    private fun showRepeatPrep(newId: String) {
        val created: SessionRecord
        val source: SessionRecord
        try {
            created = sessionManager.get(newId)
            val sourceId = created.repeatOfSessionId
                ?: throw IllegalStateException("not a repeat")
            source = sessionManager.get(sourceId)
        } catch (e: Exception) {
            statusText("Repeat prep unavailable: ${e.message}")
            showSessions(newId)
            return
        }
        val model = staged
        val stagedMatches = model != null &&
            model.sha256.lowercase() ==
                source.modelSha256.lowercase()
        val actions = if (stagedMatches) {
            listOf(CardDialog.Action("Run Repeat Benchmark",
                primary = true) {
                if (benchActive) {
                    statusText("A benchmark is already running.")
                    selectTab(NavTab.BENCHMARK)
                    return@Action
                }
                if (activeSessionId != newId) {
                    statusText("Repeat is no longer bound to the " +
                        "next run; re-bind it in Sessions.")
                    showSessions(newId)
                    return@Action
                }
                startBench()
            })
        } else {
            listOf(CardDialog.Action("Select Required Model",
                primary = true) {
                pendingRepeatPrepId = newId
                launchPicker()
            })
        }
        CardDialog.show(this, "Repeat Experiment",
            InsightCards.repeatPrepSections(
                source, created, model?.name, stagedMatches),
            actions)
    }

    /**
     * EF-MODEL-01B contextual catalog: parses + validates
     * the shipped JSON, builds the current-model context
     * (staged + last valid measurement), assesses and
     * recommends per entry, renders cards. No network,
     * no key, no downloads.
     */
    private fun showModelCatalog() {
        try {
            val text = resources.openRawResource(R.raw.model_catalog)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val entries = ModelCatalog.parse(text)
            val sessions = try {
                sessionManager.listSessions()
            } catch (_: Exception) {
                emptyList()
            }
            val current = ModelContext.CurrentModel(
                staged = staged?.let {
                    ModelContext.StagedRef(it.name, it.sha256, it.bytes)
                },
                lastMeasurement =
                    ModelContext.lastCompletedMeasurement(sessions))
            val sample = DeviceTelemetry.sample(this)
            val avail = sample.availRamBytes.takeIf { it > 0 }
            val stamped =
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val device = ModelSuitability.DeviceInput(
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                availBytes = avail,
                availSampledAt = "$stamped local",
                measuredArtifactShas = measuredArtifactShas())
            val cards = entries.map { entry ->
                val assessment = ModelSuitability.assess(entry, device)
                val evidence =
                    ModelRecommend.localEvidenceFor(entry, sessions)
                val sizeDiff = ModelContext.sizeVsCurrent(
                    assessment.sizeBoundBytes, assessment.sizeBasis,
                    current)
                ModelCatalogView.Card(
                    entry = entry,
                    assessment = assessment,
                    recommendation = ModelRecommend.recommend(
                        entry, assessment, evidence, current,
                        sizeDiff),
                    evidence = evidence,
                    sizeDiff = sizeDiff)
            }
            ModelCatalogView.show(this, current, cards,
                ::openVerifiedSource, ::showEvidenceSession,
                { selectTab(NavTab.BENCHMARK) })
        } catch (e: Exception) {
            statusText("Recommended Models unavailable: ${e.message}")
        }
    }

    /** Opens the full detail of an evidence-matched session. */
    private fun showEvidenceSession(sessionId: String) {
        try {
            val record = sessionManager.get(sessionId)
            CardDialog.show(this, "Local Evidence",
                InsightCards.sessionSections(record))
        } catch (e: Exception) {
            statusText("Evidence unavailable: ${e.message}")
        }
    }

    /** Lowercase artifact hashes with a local COMPLETED run. */
    @Suppress("UNCHECKED_CAST")
    private fun measuredArtifactShas(): Set<String> {
        return try {
            sessionManager.listSessions().mapNotNull { record ->
                val bench = record.benchmark as? Map<String, Any?>
                    ?: return@mapNotNull null
                if (bench["status"] != BenchContract.STATUS_COMPLETED) {
                    return@mapNotNull null
                }
                record.modelSha256.lowercase()
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * External-browser handoff for a catalog source URL.
     * Explicit tap only; the host allowlist is re-checked
     * here so a corrupt resource can never aim the
     * browser elsewhere.
     */
    private fun openVerifiedSource(url: String) {
        try {
            if (!url.startsWith("https://")) {
                throw IllegalArgumentException("not an https page url")
            }
            val rest = url.removePrefix("https://")
            if ("/" !in rest ||
                rest.substringBefore("/") !in ModelCatalog.ALLOWED_HOSTS
            ) {
                throw IllegalArgumentException("host not allowed")
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            statusText("Could not open source: ${e.message}")
        }
    }

    /** EF-FOLLOWUP-02: host-computed repeat-series dialog. */
    /**
     * EF-MEM-01 fresh guard evaluation. Samples current
     * memory and evaluates the SAME preflight the runner
     * uses, then reports. It NEVER starts inference: a new
     * benchmark still needs its explicit run action and
     * receives a new run identity.
     */
    /**
     * EF-MEM-02 gated diagnostic note. Default-off cost is
     * one boolean; sampling runs only after the developer
     * explicitly enables diagnostics in Settings.
     */
    private fun diagNote(checkpoint: MemDiag.Checkpoint) {
        if (!MemDiag.Store.isEnabled()) return
        try {
            MemDiag.Store.note(MemDiag.sampleNow(this, checkpoint))
        } catch (_: Exception) {
        }
    }

    private fun memDiagLabel(): String =
        if (MemDiag.Store.isEnabled()) "Memory Diagnostics: On"
        else "Memory Diagnostics: Off"

    /**
     * One-line Benchmark-tab readiness. Fresh cheap sample
     * per call; invoked on tab entry, staging, and run end
     * — never periodic. Red only for a predicted refusal.
     */
    private fun updateMemReadinessLine() {
        val model = staged
        val sample = try {
            DeviceTelemetry.sample(this)
        } catch (_: Exception) {
            null
        }
        val assessment = MemPolicy.assess(
            sample?.availRamBytes?.takeIf { it >= 0 },
            sample?.totalRamBytes?.takeIf { it > 0 },
            sample?.lowMemory)
        memReadinessView.text = MemReadiness.statusLine(
            model?.name, assessment)
        // Red only for the severe band; an advisory is
        // guidance, and Run stays available regardless.
        memReadinessView.setTextColor(if (
            model != null &&
            assessment.level == MemPolicy.Level.SEVERE
        ) {
            errorColor()
        } else {
            secondaryColor()
        })
    }

    /**
     * Preflight readiness dialog: fresh sample, current
     * guard requirement, config, shadow budget. Read-only;
     * nothing here starts inference.
     */
    private fun showMemReadiness() {
        val model = staged
        val sample = try {
            DeviceTelemetry.sample(this)
        } catch (_: Exception) {
            statusText("Memory status unavailable.")
            return
        }
        val assessment = MemPolicy.assess(
            sample.availRamBytes.takeIf { it >= 0 },
            sample.totalRamBytes.takeIf { it > 0 },
            sample.lowMemory)
        val config = currentBenchConfig()
        val configLabel = "ctx ${config.nCtx}, " +
            "${config.nThreads} threads, ${config.nPredict} " +
            "tokens, ${config.sampler}"
        val sections = MemReadiness.sections(
            model?.name, configLabel, assessment,
            sample.thresholdBytes.takeIf { it > 0 }
        ).toMutableList()
        if (model != null) {
            sections += MemBudget.sections(MemBudget.shadowEstimate(
                model.bytes, model.sha256, null,
                config.nCtx, config.nThreads,
                runtimeVersionLabel()))
        }
        CardDialog.show(this, "Memory Readiness", sections,
            listOf(
                CardDialog.Action("Recheck") { showMemReadiness() },
                CardDialog.Action("Memory Details") {
                    showMemSamples()
                },
                CardDialog.Action("Recommended Models") {
                    showModelCatalog()
                }
            ))
    }

    /** Settings → diagnostics toggle + sample access. */
    private fun showMemDiag() {
        val on = MemDiag.Store.isEnabled()
        val count = MemDiag.Store.snapshot().size
        val sections = listOf(InsightCards.Section(
            "Memory Diagnostics",
            listOf(
                InsightCards.Row("State",
                    if (on) "Enabled" else "Disabled"),
                InsightCards.Row("Samples held",
                    "$count of ${MemDiag.Store.MAX_SAMPLES}"),
                InsightCards.Row("Scope",
                    "This app's process + system free RAM only")
            ),
            "Developer mode, off by default and reset each " +
                "launch. Samples EdgeFit's own memory at " +
                "lifecycle checkpoints; no other apps, no " +
                "background polling, no uploads. Runs recorded " +
                "while enabled export an x_mem_diag block and " +
                "are non-confirmatory for EF-SCIENCE-01 until " +
                "an on/off overhead assessment exists."))
        CardDialog.show(this, "Memory Diagnostics", sections,
            listOf(
                CardDialog.Action(
                    if (on) "Disable" else "Enable",
                    primary = true
                ) {
                    MemDiag.Store.setEnabled(!on)
                    memDiagButton.text = memDiagLabel()
                    statusText(if (!on) {
                        "Memory diagnostics enabled for this launch."
                    } else {
                        "Memory diagnostics disabled; samples cleared."
                    })
                },
                CardDialog.Action("View Samples") {
                    showMemSamples()
                },
                CardDialog.Action("Clear Samples") {
                    MemDiag.Store.clear()
                    statusText("Diagnostic samples cleared.")
                }
            ))
    }

    /** Read-only sample table (selectable for copy-out). */
    private fun showMemSamples() {
        if (!MemDiag.Store.isEnabled()) {
            CardDialog.show(this, "Memory Samples",
                listOf(InsightCards.Section("State",
                    listOf(InsightCards.Row("Diagnostics",
                        "Disabled — enable in Settings to collect " +
                            "samples.")))))
            return
        }
        val samples = MemDiag.Store.snapshot()
        val base = samples.firstOrNull()?.elapsedRealtimeMs ?: 0L
        val body = if (samples.isEmpty()) {
            "No samples yet. Navigate (Home, Benchmark, " +
                "Sessions), stage a model, or run a benchmark."
        } else {
            samples.joinToString("\n\n") {
                MemDiag.formatLine(it, base)
            }
        }
        CardDialog.show(this, "Memory Samples (${samples.size})",
            listOf(InsightCards.Section("Checkpoints",
                listOf(InsightCards.Row("Samples",
                    "${samples.size} held (max " +
                        "${MemDiag.Store.MAX_SAMPLES})")),
                body)))
    }

    // EF-MEM-03: the MEM-01 "Memory Recheck" dialog is
    // retired with the blocking gate (nothing rechecks
    // anymore). Fresh advisory state lives in
    // [showMemReadiness]; historical refusal strings keep
    // their stored text.

    /**
     * EF-MEM-03 local recovery assistant. Provenance is
     * ALWAYS labeled local — never a Muse proposal.
     * Routes: agent-child failures open the Agent screen
     * (budget-enforced recovery proposals, no offline
     * bypass); bound top-level failures offer new linked
     * recovery sessions with a chosen config; unbound
     * failures offer a standalone config override.
     * Nothing here starts inference or calls Meta; every
     * new attempt needs an explicit press of Run.
     */
    @Suppress("UNCHECKED_CAST")
    private fun showRecoveryAssistant(sourceId: String?) {
        val resolved = sourceId ?: lastResult?.let {
            findSessionForRun(it.runId)?.sessionId
        }
        val record = try {
            resolved?.let { sessionManager.get(it) }
        } catch (_: Exception) {
            null
        }
        if (record != null) {
            showBoundRecovery(record)
            return
        }
        showUnboundRecovery()
    }

    private fun findSessionForRun(runId: String): SessionRecord? {
        return try {
            sessionManager.listSessions().firstOrNull {
                (it.benchmark as? Map<String, Any?>)
                    ?.get("run_id") == runId
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Bound failure: agent child vs top-level routing. */
    @Suppress("UNCHECKED_CAST")
    private fun showBoundRecovery(record: SessionRecord) {
        val bench = record.benchmark as? Map<String, Any?>
        val status = bench?.get("status") as? String ?: "unknown"
        if (status != BenchContract.STATUS_FAILED &&
            status != BenchContract.STATUS_TIMEOUT &&
            status != BenchContract.STATUS_CANCELLED
        ) {
            statusText("This session needs no recovery ($status).")
            return
        }
        val parentId = record.parentSessionId
        if (record.mode != "agent" && parentId != null) {
            // Agent experiment child: recovery runs through
            // the proposal flow so the finite budget is
            // charged exactly once. No offline bypass.
            CardDialog.show(this, "Recovery Assistant (local)",
                recoverySummarySections(record, bench),
                listOf(
                    CardDialog.Action("Open Agent Recovery",
                        primary = true
                    ) {
                        try {
                            agentManager.getParent(parentId)
                            showAgent(parentId)
                        } catch (_: Exception) {
                            statusText("Agent parent unavailable.")
                        }
                    },
                    CardDialog.Action("Recommended Models") {
                        showModelCatalog()
                    }
                ))
            return
        }
        if (record.mode == "agent") {
            statusText("Agent parents recover through their experiments.")
            return
        }
        // Top-level bound failure: new linked recovery
        // session with a developer-chosen config.
        val sourceConfig =
            SessionBenchBridge.configData(record.config)
                ?: currentBenchConfig()
        val actions = mutableListOf<CardDialog.Action>()
        for ((label, config) in recoveryConfigOptions(sourceConfig)) {
            actions.add(CardDialog.Action(label,
                primary = actions.isEmpty()
            ) {
                recoverBoundSession(record.sessionId, config)
            })
        }
        actions.add(CardDialog.Action("Recommended Models") {
            showModelCatalog()
        })
        CardDialog.show(this, "Recovery Assistant (local)",
            recoverySummarySections(record, bench) +
                InsightCards.Section(
                    "Local options (not Muse advice)",
                    listOf(InsightCards.Row("Choices",
                        "Retry the same config, or retry with a " +
                            "smaller context. Each choice creates " +
                            "a NEW linked session, authorizes it, " +
                            "and binds it; you still press Run."))),
            actions)
    }

    /** Unbound failure (bench screen): standalone override. */
    private fun showUnboundRecovery() {
        val result = lastResult
        if (result == null ||
            result.status == BenchContract.STATUS_COMPLETED
        ) {
            statusText("No uncompleted run to recover.")
            return
        }
        val base = standaloneOverride ?: currentBenchConfig()
        val actions = mutableListOf<CardDialog.Action>()
        for ((label, config) in recoveryConfigOptions(base)) {
            actions.add(CardDialog.Action("Standalone: $label",
                primary = actions.isEmpty()
            ) {
                standaloneOverride = config
                refreshModelLine()
                refreshBenchConfigLine()
                updateMemReadinessLine()
                statusText("Standalone override set " +
                    "(ctx=${config.nCtx}) — press Run to attempt.")
            })
        }
        actions.add(CardDialog.Action("Clear Override") {
            standaloneOverride = null
            refreshModelLine()
            refreshBenchConfigLine()
            statusText("Standalone override cleared.")
        })
        actions.add(CardDialog.Action("Recommended Models") {
            showModelCatalog()
        })
        val mem = result.xMemConditions
        CardDialog.show(this, "Recovery Assistant (local)",
            listOf(InsightCards.Section(
                "Unbound run ${result.runId}",
                listOf(
                    InsightCards.Row("Status", result.status,
                        InsightCards.Accent.ERROR),
                    InsightCards.Row("Reason",
                        result.error ?: "unknown"),
                    InsightCards.Row("Memory at start",
                        if (mem?.availPctStart != null) {
                            MemPolicy.formatPct(mem.availPctStart)
                        } else {
                            "Unknown"
                        }),
                    InsightCards.Row("Provenance",
                        "Local assistant — not a Muse proposal")
                ),
                "This run was not attached to a session, so " +
                    "there is no prediction to carry. Choose a " +
                    "standalone config, then press Run. " +
                    "Batch size, KV cache, and model loading " +
                    "stay fixed: only context, threads, and " +
                    "token budget are adjustable.")),
            actions)
    }

    /**
     * Deterministic local config menu: explicit same-config
     * retry plus smaller-context steps supported by the
     * current JNI bridge (context/threads/tokens only —
     * batch size is NOT adjustable without a native
     * change, so no batch control is offered).
     */
    private fun recoveryConfigOptions(
        base: BenchConfigData
    ): List<Pair<String, BenchConfigData>> {
        val options = mutableListOf(
            "Retry Same Config" to base)
        for (ctx in listOf(256, 128)) {
            if (ctx >= base.nCtx) continue
            if (ctx < BenchConfig.MIN_CTX) continue
            if (base.nPredict >= ctx) continue
            val adjusted = base.copy(nCtx = ctx)
            if (BenchConfig.validate(adjusted).isNotEmpty()) continue
            options.add("Retry with ctx $ctx" to adjusted)
        }
        return options
    }

    @Suppress("UNCHECKED_CAST")
    private fun recoverySummarySections(
        record: SessionRecord,
        bench: Map<String, Any?>?
    ): List<InsightCards.Section> {
        val measurements = bench?.get("measurements")
            as? Map<String, Any?>
        val memPct = measurements
            ?.get("mem_avail_pct_start") as? Number
        return listOf(InsightCards.Section(
            "Failed attempt ${record.sessionId}",
            listOf(
                InsightCards.Row("Status",
                    bench?.get("status") as? String ?: "unknown",
                    InsightCards.Accent.ERROR),
                InsightCards.Row("Cause",
                    bench?.get("failure_cause") as? String
                        ?: "recorded without a cause"),
                InsightCards.Row("Config",
                    SessionBenchBridge.configData(record.config)
                        ?.let {
                            "ctx ${it.nCtx}, ${it.nThreads} " +
                                "threads, ${it.nPredict} tokens"
                        } ?: "unparsed"),
                InsightCards.Row("Memory at start",
                    if (memPct != null) {
                        MemPolicy.formatPct(memPct.toDouble())
                    } else {
                        "Unknown"
                    }),
                InsightCards.Row("Provenance",
                    "Local assistant — not a Muse proposal")
            ),
            "The failed attempt stays preserved unchanged. " +
                "A recovery creates a NEW linked session; " +
                "nothing here starts inference or calls Meta."))
    }

    /**
     * Bound recovery execution: new linked session with a
     * carried prediction, authorized + bound, Run manual.
     * Single-flight: double-taps create one session.
     */
    @Volatile
    private var recoveryInFlight = false

    @Suppress("UNCHECKED_CAST")
    private fun recoverBoundSession(
        sourceId: String,
        config: BenchConfigData
    ) {
        if (recoveryInFlight) {
            statusText("Recovery already in progress.")
            return
        }
        recoveryInFlight = true
        Thread({
            try {
                val source = sessionManager.get(sourceId)
                val prediction = source.prediction
                    as? Map<String, Any?>
                    ?: throw IllegalStateException(
                        "source has no prediction")
                val gen = (prediction["predicted_gen_tps"]
                    as? Number)?.toDouble()
                    ?: throw IllegalStateException("prediction bad")
                val prefill = (prediction["predicted_prefill_tps"]
                    as? Number)?.toDouble()
                    ?: throw IllegalStateException("prediction bad")
                val profile = try {
                    DeviceCapabilityCollector(this).collect()
                } catch (_: Exception) {
                    null
                }
                val profileSha = profile?.let {
                    BenchConfig.sha256Hex(ProfileSerializer.serialize(it)
                        .toByteArray(Charsets.UTF_8))
                }
                val created = sessionManager.createRecovery(
                    sourceId, profile?.device?.model, profileSha,
                    SessionBenchBridge.configMap(config),
                    gen, prefill,
                    prediction["raw_json"] as? String ?: "{}",
                    prediction["source_type"] as? String
                        ?: SessionManager.SOURCE_OFFLINE_TEST,
                    (prediction["evidence_ids"] as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?: emptyList(),
                    carriedCreatedAtUtc = prediction["created_at_utc"]
                        as? String)
                sessionManager.authorizeBenchmark(
                    created.sessionId, "recovery authorized")
                runOnUiThread {
                    recoveryInFlight = false
                    activeSessionId = created.sessionId
                    refreshSessionLine()
                    selectTab(NavTab.BENCHMARK)
                    statusText("Recovery ${created.sessionId} " +
                        "authorized and bound — press Run.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    recoveryInFlight = false
                    statusText(
                        "Recovery failed: ${e.message}")
                }
            }
        }).start()
    }

    /**
     * EF-UX-01D SHOW evaluation (design E, in order):
     * under quota, not dismissed this launch, no run
     * executing, no host modal open. The count derives
     * from durable records every time, so retirement
     * survives relaunch and update without any flag.
     */
    private fun maybeShowOnboarding() {
        if (onboardingOpen) return
        val completions = try {
            Onboarding.countCompletions(sessionManager.listSessions())
        } catch (_: Exception) {
            0
        }
        val modalOpen = agentDialogOpen || pickerActive ||
            benchScreen.visibility == View.VISIBLE ||
            requestTracker.hasInflight()
        if (!Onboarding.shouldShow(completions,
                onboardingDismissedThisLaunch, benchActive,
                modalOpen)
        ) {
            return
        }
        openOnboarding(onboardingPage)
    }

    /** Shows the guide. Help replay calls this directly,
     * bypassing the quota without resetting retirement. */
    private fun openOnboarding(startPage: Int) {
        if (onboardingOpen) return
        onboardingOpen = true
        OnboardingView.show(this, startPage,
            onPage = { onboardingPage = it },
            onDone = { start ->
                onboardingOpen = false
                // Both Skip and Start dismiss for this
                // launch (UX-D01); the next cold start
                // re-evaluates from durable records.
                onboardingDismissedThisLaunch = true
                if (start) selectTab(NavTab.BENCHMARK)
            })
    }

    /** Settings → Help: version plus the guide replay entry. */
    private fun showHelp() {
        val sections = listOf(
            InsightCards.Section("About", listOf(
                InsightCards.Row("Version",
                    "EdgeFit AI ${BuildConfig.VERSION_NAME} " +
                        "(ai.edgefit.diag.bench)"),
                InsightCards.Row("Guide",
                    "The three-screen introduction is " +
                        "available any time, even after it " +
                        "retires automatically."))),
            InsightCards.Section("Onboarding status", listOf(
                InsightCards.Row("Qualifying completions",
                    try {
                        Onboarding.countCompletions(
                            sessionManager.listSessions())
                            .toString() + " recorded (onboarding " +
                            "auto-retires at " +
                            Onboarding.RETIRE_COUNT + ")"
                    } catch (_: Exception) {
                        "unavailable"
                    })))
        )
        CardDialog.show(this, "Help", sections,
            listOf(CardDialog.Action("Show introduction") {
                openOnboarding(0)
            }))
    }

    private fun showSeriesComparison(id: String) {
        try {
            val record = sessionManager.get(id)
            val series = sessionManager.findSeriesRuns(id)
            val result = SessionComparison.compareSeries(record, series)
            CardDialog.show(this, "Repeat series",
                InsightCards.seriesSections(result))
        } catch (e: Exception) {
            statusText("Compare failed: ${e.message}")
        }
    }

    private fun deleteSession(id: String): String {
        if (activeSessionId == id) activeSessionId = null
        refreshSessionLine()
        return if (sessionManager.deleteSession(id)) "Deleted $id."
        else "No session $id."
    }

    private fun refreshSessionLine() {
        // Binding state gates the standalone-override
        // marker, so refresh both lines together.
        try {
            refreshModelLine()
        } catch (_: Exception) {
        }
        val id = activeSessionId
        if (id == null) {
            val line = SessionView.buildActiveLine(null, null)
            sessionLine.text = line
            sessionsSummaryView.text = line
            refreshNextAction()
            refreshBenchAuthLine()
            return
        }
        val record = try {
            sessionManager.get(id)
        } catch (_: Exception) {
            null
        }
        if (record == null) {
            activeSessionId = null
            val line = SessionView.buildActiveLine(null, null)
            sessionLine.text = line
            sessionsSummaryView.text = line
        } else {
            val line = SessionView.buildActiveLine(id, record)
            sessionLine.text = line
            sessionsSummaryView.text = line
        }
        refreshNextAction()
        refreshBenchAuthLine()
    }

    // ---- live Meta (EF-BYOK-01) ----

    private fun showMetaSettings() {
        MetaView.showKeyDialog(
            this,
            status = {
                MetaView.keyStatusLine(metaVault.hasSession(),
                    metaVault.sessionSuffix(), metaVault.hasPersisted())
            },
            onSave = { key, remember -> saveMetaKey(key, remember) },
            onForget = {
                if (metaVault.forget()) "Key forgotten everywhere."
                else "No key was stored."
            },
            onPredict = { startLivePrediction(null) }
        )
    }

    private fun saveMetaKey(key: String, remember: Boolean): String {
        return try {
            metaVault.setSessionKey(key)
            if (remember) metaVault.persistSessionKey()
            if (remember) "Key saved session + securely persisted."
            else "Session-only key set; dies with the app."
        } catch (e: Exception) {
            "Save failed: ${e.message}"
        }
    }

    private fun keyNote(): String = when {
        metaVault.hasSession() -> "your session-only key"
        metaVault.hasPersisted() -> "your saved key (decrypts for this call)"
        else -> "NO KEY CONFIGURED (request will refuse)"
    }

    /**
     * Tracked live request: at most one in flight per key, with a
     * truthful progress indicator from confirmed authorization
     * until real completion or failure. Never claims
     * cancellation (the transport has no cancel path).
     */
    private fun launchTracked(
        key: String,
        title: String,
        message: String,
        threadName: String,
        block: ((String) -> Unit) -> Unit
    ) {
        if (!requestTracker.begin(key)) {
            statusText("Request already in progress.")
            return
        }
        val progress = ProgressUi.show(this, title, message)
        Thread({
            try {
                block { update ->
                    runOnUiThread { progress.update(update) }
                }
            } finally {
                requestTracker.end(key)
                runOnUiThread { progress.dismiss() }
            }
        }, threadName).start()
    }

    /**
     * Live prediction for a new session (null) or a retry on a
     * PREDICTION_PENDING byok session. Authorization first, one POST,
     * durable commit before any benchmark can bind.
     */
    private fun startLivePrediction(existingId: String?) {
        val model = staged
        if (existingId == null && model == null) {
            MetaView.showResultDialog(this, "Predict with Meta",
                "Stage a model file first.")
            return
        }
        val fields = listOf(
            "device profile summary (model, Android/API, ABIs, CPUs, RAM)",
            "model identity (display name, SHA-256, size, quantization)",
            "workload id + prompt SHA-256",
            "benchmark config (ctx/threads/tokens/sampler/runtime)",
            "up to ${MetaApi.MAX_PRIOR_RUNS} prior on-device run ids + " +
                "throughputs, if any"
        )
        // EF-MEM-02 informational readiness: appended
        // AFTER the field list (never inside it) so it is
        // not misread as data sent to Meta. Non-blocking.
        val memSample = try {
            DeviceTelemetry.sample(this)
        } catch (_: Exception) {
            null
        }
        MetaView.showAuthorizeDialog(
            this,
            MetaView.authorizeText(
                "Live throughput prediction", fields, keyNote()) +
                "\n\n" + MemReadiness.authNote(model?.name,
                    MemPolicy.assess(
                        memSample?.availRamBytes?.takeIf { it >= 0 },
                        memSample?.totalRamBytes?.takeIf { it > 0 },
                        memSample?.lowMemory)),
            "Predict"
        ) {
            launchTracked(
                "predict:${existingId ?: "new"}",
                "Meta Prediction",
                "Requesting performance prediction from Meta Muse.",
                "edgefit-meta-predict"
            ) { progress ->
                runLivePrediction(existingId, progress)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runLivePrediction(
        existingId: String?,
        progress: (String) -> Unit = {}
    ) {
        try {
            val id = existingId ?: createLiveSession()
            if (existingId == null ||
                sessionManager.get(id).state == SessionState.SESSION_CREATED
            ) {
                sessionManager.requestPrediction(id)
            }
            val spec = livePredictionSpec(id)
            val record = metaClient.requestPrediction(spec)
            progress("Response received; validating and saving prediction…")
            sessionManager.authorizeBenchmark(id, "live prediction recorded")
            activeSessionId = id
            runOnUiThread {
                refreshSessionLine()
                statusText("Live prediction recorded for $id; " +
                    "bound to the next run.")
                val prediction =
                    record.prediction as? Map<String, Any?> ?: emptyMap()
                val sections = InsightCards.predictionSections(
                    prediction, record.modelDisplay, record.config) +
                    InsightCards.Section("Next", listOf(
                        InsightCards.Row("Status",
                            "Recorded, authorized and bound. Run the " +
                                "benchmark to measure against it.")))
                CardDialog.show(this, "Live Meta Prediction", sections,
                    listOf(CardDialog.Action("Open Benchmark") {
                        selectTab(NavTab.BENCHMARK)
                    }))
            }
        } catch (e: Exception) {
            runOnUiThread {
                refreshSessionLine()
                statusText("Live prediction failed: ${e.message}")
                MetaView.showResultDialog(this, "Live Meta Prediction",
                    "No prediction recorded (nothing was persisted):\n" +
                        "${e.message}")
            }
        }
    }

    /** Creates the byok session shell; prediction comes only from Meta. */
    private fun createLiveSession(): String {
        val model = staged ?: throw IllegalStateException("no staged model")
        val profile = try {
            DeviceCapabilityCollector(this).collect()
        } catch (_: Exception) {
            null
        }
        val created = sessionManager.createSession(
            mode = "byok",
            deviceModel = profile?.device?.model,
            profileSha256 = profile?.let {
                BenchConfig.sha256Hex(ProfileSerializer.serialize(it)
                    .toByteArray(Charsets.UTF_8))
            },
            modelSha256 = model.sha256,
            modelSizeBytes = model.bytes,
            modelDisplay = model.name,
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha256 = BenchConfig.workload().promptSha256,
            config = SessionBenchBridge.configMap(currentBenchConfig())
        )
        return created.sessionId
    }

    private fun livePredictionSpec(sessionId: String):
        MetaSessionClient.PredictionSpec {
        val model = staged ?: throw IllegalStateException("no staged model")
        val profile = try {
            DeviceCapabilityCollector(this).collect()
        } catch (_: Exception) {
            null
        }
        val device = profile?.device
        return MetaSessionClient.PredictionSpec(
            sessionId = sessionId,
            deviceLines = if (device == null) {
                listOf("unavailable")
            } else {
                listOf(
                    "model=${device.manufacturer} ${device.model}",
                    "android=${device.androidRelease} api=${device.sdkInt}",
                    "abis=${device.supportedAbis.joinToString(",")}",
                    "cpus=${device.logicalProcessors}",
                    "ram_total_mb=${device.totalRamBytes / 1048576}",
                    "ram_avail_mb=${device.availRamBytes / 1048576}",
                    "thermal=${profile.thermal?.statusName ?: "unavailable"}",
                    "battery=${profile.battery?.pct ?: "unavailable"}"
                )
            },
            modelDisplay = model.name,
            modelSha = model.sha256,
            modelSizeBytes = model.bytes,
            // Pre-run quantization is genuinely unknown: the native
            // meta field only exists after inference. Never parsed
            // from the filename.
            quantization = "unknown",
            workloadId = BenchContract.WORKLOAD_ID,
            promptSha = BenchConfig.workload().promptSha256,
            config = currentBenchConfig(),
            runtimeVersion = runtimeVersionLabel(),
            runtimeAbi = runtimeAbiLabel(profile)
        )
    }

    private fun startLiveFollowUp(sessionId: String) {
        val fields = listOf(
            "benchmark run id + model/workload/config identity",
            "persisted predicted throughputs",
            "measured throughputs",
            "host-computed signed + percentage errors",
            "thermal + memory-method qualifiers",
            // EF-FOLLOWUP-02 D4: bounded priors, when any exist.
            "up to 4 prior same-config runs + host deltas"
        )
        MetaView.showAuthorizeDialog(
            this,
            MetaView.authorizeText(
                "Follow-up assessment for $sessionId", fields, keyNote()),
            "Request"
        ) {
            launchTracked(
                "followup:$sessionId",
                "Meta Follow-Up",
                "Requesting follow-up assessment from Meta Muse.",
                "edgefit-meta-followup"
            ) { progress ->
                runLiveFollowUp(sessionId, progress)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runLiveFollowUp(
        sessionId: String,
        progress: (String) -> Unit = {}
    ) {
        try {
            val before = sessionManager.get(sessionId)
            if (before.state == SessionState.COMPARISON_READY) {
                sessionManager.requestFollowup(sessionId)
            }
            val record = metaClient.requestFollowUp(sessionId)
            progress("Response received; saving assessment…")
            runOnUiThread {
                refreshSessionLine()
                statusText("Follow-up recorded for $sessionId.")
                val comparison =
                    record.comparison as? Map<String, Any?> ?: emptyMap()
                val followup =
                    record.followup as? Map<String, Any?> ?: emptyMap()
                val sections = listOf(InsightCards.Section(
                    "Prediction vs measured",
                    InsightCards.comparisonRows(comparison))) +
                    InsightCards.followUpSections(followup)
                // EF-UX-02: the recorded recommendation names
                // its eligible next steps instead of stranding
                // the user on a read-only summary.
                val next = mutableListOf<CardDialog.Action>()
                val parentId = record.parentSessionId
                if (parentId != null) {
                    next.add(CardDialog.Action("Open Agent") {
                        showAgent(parentId)
                    })
                } else {
                    if (SessionView.canRepeatStandalone(record)) {
                        next.add(CardDialog.Action(
                            "Repeat Benchmark") {
                            val outcome = repeatStandaloneSession(
                                record.sessionId)
                            val newId = outcome.newSessionId
                            if (newId != null) showRepeatPrep(newId)
                            else statusText(outcome.message)
                        })
                    }
                    if (SessionView.canCompare(record)) {
                        next.add(CardDialog.Action("Compare Runs") {
                            showSeriesComparison(record.sessionId)
                        })
                    }
                }
                CardDialog.show(this, "Meta Follow-Up", sections, next)
            }
        } catch (e: Exception) {
            runOnUiThread {
                refreshSessionLine()
                statusText("Follow-up failed: ${e.message}")
                MetaView.showResultDialog(this, "Meta Follow-Up",
                    "No assessment recorded (nothing was persisted):\n" +
                        "${e.message}")
            }
        }
    }

    // ---- optimization agent (EF-AGENT-01) ----

    private fun showAgent(selectedId: String?) {
        agentDialogOpen = true
        AgentView.show(
            this,
            initialId = selectedId,
            parents = {
                sessionManager.listSessions().filter { it.mode == "agent" }
            },
            detail = { id -> agentDetail(id) },
            onNew = { createAgentSession() },
            onRequest = { id -> startAgentProposal(id) },
            onApprove = { id -> approveAndRunExperiment(id) },
            onReject = { id -> rejectAgentProposal(id) },
            onFinish = { id -> finishAgentSession(id) },
            onClose = { agentDialogOpen = false },
            onModify = { id -> modifyAgentProposal(id) }
        )
    }

    private fun agentDetail(id: String): AgentView.AgentDetail {
        val gate = agentManager.proposalGate(id)
        return AgentView.AgentDetail(
            parent = agentManager.getParent(id),
            children = agentManager.childrenOf(id),
            pack = agentManager.buildPack(id),
            gateOk = gate.first,
            gateReason = gate.second,
            review = reviewFor(id)
        )
    }

    /**
     * EF-AGENT-REVIEW-01: independent host review of the
     * pending proposal, or null when none is pending. Local
     * and synchronous: no network, no second Meta call.
     * Display fails open (null shows the proposal without a
     * verdict); approval re-verifies fail-closed via strict
     * mode, where review errors propagate instead.
     */
    @Suppress("UNCHECKED_CAST")
    private fun reviewFor(
        parentId: String,
        strict: Boolean = false
    ): AgentReview.ReviewResult? {
        if (!strict) {
            return try {
                reviewFor(parentId, strict = true)
            } catch (_: Exception) {
                null
            }
        }
            val parent = agentManager.getParent(parentId)
            val pending = (parent.agent as? Map<String, Any?>)
                ?.get("pending_proposal") as? Map<String, Any?>
                ?: return null
            val children = agentManager.childrenOf(parentId)
            val pack = agentManager.buildPack(parentId)
            val executed = pack.comparable.mapNotNull { ev ->
                val data = agentManager.executedConfigMapForRun(
                    parentId, ev.runId)?.let {
                    SessionBenchBridge.configData(it)
                }
                if (data != null) ev.runId to data else null
            }.toMap()
            val failures = children.mapNotNull { child ->
                val runId = (child.benchmark as? Map<String, Any?>)
                    ?.get("run_id") as? String ?: return@mapNotNull null
                if (AgentEvidence.describeFailure(child) == null) {
                    return@mapNotNull null
                }
                if (child.modelSha256 != parent.modelSha256 ||
                    child.workloadId != parent.workloadId
                ) {
                    return@mapNotNull null
                }
                runId
            }.toSet()
            val memPct = mutableMapOf<String, Double>()
            val memBelow = mutableSetOf<String>()
            children.forEach { child ->
                val bench = child.benchmark as? Map<String, Any?>
                    ?: return@forEach
                val runId = bench["run_id"] as? String ?: return@forEach
                val measures = bench["measurements"] as? Map<String, Any?>
                    ?: return@forEach
                (measures["mem_avail_pct_start"] as? Number)
                    ?.toDouble()?.let { memPct[runId] = it }
                if (measures["mem_below_advisory"] == true) {
                    memBelow.add(runId)
                }
            }
            return AgentReview.reviewProposal(
                AgentReview.ReviewInput(
                    pending = pending,
                    parent = parent,
                    pack = pack,
                    failureRunIds = failures,
                    executedConfigs = executed,
                    current = agentManager.currentConfig(parentId),
                    deviceMaxThreads = deviceMaxThreads(),
                    stagedModelSha = staged?.sha256,
                    memStartPct = memPct,
                    memBelowAdvisory = memBelow
                )
            )
    }

    private fun createAgentSession(): String {
        val model = staged ?: return "Stage a model file first."
        return try {
            val profile = try {
                DeviceCapabilityCollector(this).collect()
            } catch (_: Exception) {
                null
            }
            val parent = agentManager.createAgent(
                deviceModel = profile?.device?.model,
                profileSha256 = profile?.let {
                    BenchConfig.sha256Hex(ProfileSerializer.serialize(it)
                        .toByteArray(Charsets.UTF_8))
                },
                modelSha256 = model.sha256,
                modelSizeBytes = model.bytes,
                modelDisplay = model.name,
                workloadId = BenchContract.WORKLOAD_ID,
                promptSha256 = BenchConfig.workload().promptSha256,
                baseConfig = currentBenchConfig()
            )
            "Agent ${parent.sessionId} created with experiment 1 shell. " +
                "Run experiment 1 via Sessions (Predict), then request " +
                "a proposal here."
        } catch (e: Exception) {
            "Agent creation failed: ${e.message}"
        }
    }

    private fun deviceMaxThreads(): Int {
        val logical = try {
            DeviceCapabilityCollector(this).collect()
                .device.logicalProcessors
        } catch (_: Exception) {
            BenchConfig.N_THREADS
        }
        return BenchConfig.MAX_THREADS.coerceAtMost(logical.coerceAtLeast(1))
    }

    private fun startAgentProposal(parentId: String) {
        val recovery = try {
            agentManager.proposalGateEx(parentId).mode ==
                AgentManager.ProposalMode.RECOVERY
        } catch (_: Exception) {
            false
        }
        val fields = listOf(
            "agent evidence pack (run ids, configs, throughputs, errors)",
            "current configuration + next experiment number",
            "model SHA-256 + workload id",
            "experiment budget used/max"
        ) + if (recovery) {
            listOf("failed attempt evidence (config, cause, memory " +
                "conditions — no throughput exists for failed runs)")
        } else {
            emptyList()
        }
        MetaView.showAuthorizeDialog(
            this,
            MetaView.authorizeText(
                "Optimization proposal for $parentId", fields, keyNote()),
            "Request"
        ) {
            launchTracked(
                "proposal:$parentId",
                "Agent Proposal",
                "Requesting optimization proposal from Meta Muse.",
                "edgefit-agent-proposal"
            ) { progress ->
                runAgentProposal(parentId, progress)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runAgentProposal(
        parentId: String,
        progress: (String) -> Unit = {}
    ) {
        try {
            val gate = agentManager.proposalGateEx(parentId)
            if (!gate.ok) throw IllegalStateException(gate.reason)
            val recoveryMode =
                gate.mode == AgentManager.ProposalMode.RECOVERY
            val parent = agentManager.getParent(parentId)
            val pack = agentManager.buildPack(parentId)
            val children = agentManager.childrenOf(parentId)
            // EF-MEM-03: failed-run ids of THIS parent only,
            // with matching identity — the host allowlist
            // behind recovery citation.
            val recoveryIds = children.mapNotNull { child ->
                @Suppress("UNCHECKED_CAST")
                val bench = child.benchmark as? Map<String, Any?>
                    ?: return@mapNotNull null
                val runId = bench["run_id"] as? String
                    ?: return@mapNotNull null
                if (AgentEvidence.describeFailure(child) == null) {
                    return@mapNotNull null
                }
                if (child.modelSha256 != parent.modelSha256 ||
                    child.workloadId != parent.workloadId
                ) {
                    return@mapNotNull null
                }
                runId
            }.toSet()
            val proposal = metaClient.requestProposal(
                MetaSessionClient.ProposalSpec(
                    parentId = parentId,
                    packText = AgentEvidence.renderPack(pack),
                    currentConfig = agentManager.currentConfig(parentId),
                    nextSeq = agentManager.nextSeq(parentId),
                    modelSha = parent.modelSha256,
                    workloadId = parent.workloadId,
                    attemptsUsed = parent.budgetUsed,
                    attemptsMax = parent.budgetMax,
                    allowedRunIds = pack.comparable.map { it.runId }.toSet(),
                    deviceMaxThreads = deviceMaxThreads(),
                    // EF-FOLLOWUP-02: stored executed config per
                    // comparable run; repeats verify against these.
                    repeatConfigs = pack.comparable.mapNotNull { ev ->
                        val exec = agentManager.executedConfigMapForRun(
                            parentId, ev.runId)
                        val data = exec?.let {
                            SessionBenchBridge.configData(it)
                        }
                        if (data != null) ev.runId to data else null
                    }.toMap(),
                    recoveryRunIds = recoveryIds,
                    failureText = AgentEvidence.renderFailures(children),
                    recoveryMode = recoveryMode
                )
            )
            progress("Response received; validating proposal…")
            val stored = agentManager.storeProposal(
                parentId, proposal, pack.packHash,
                agentManager.nextSeq(parentId))
            runOnUiThread {
                val declined = (stored.agent as? Map<String, Any?>)
                    ?.get("terminal_reason") ==
                    AgentManager.END_NO_FURTHER_TESTING
                statusText(if (declined) {
                    "Agent declined further testing; session closed."
                } else {
                    "Proposal pending review for $parentId."
                })
                showAgent(parentId)
            }
        } catch (e: Exception) {
            val struck = e is MetaSessionClient.ClientError &&
                e.code in setOf("BAD_PROPOSAL", "BAD_RESPONSE",
                    "REPLY_TOO_LARGE")
            val note = if (struck) {
                try {
                    agentManager.recordInvalidProposal(parentId,
                        e.message ?: e.code)
                    " Invalid proposal recorded."
                } catch (_: Exception) {
                    ""
                }
            } else {
                ""
            }
            runOnUiThread {
                statusText("Proposal failed: ${e.message}.$note")
                showAgent(parentId)
            }
        }
    }

    /**
     * Approve & Run Once: creates the approved child with the
     * proposal's expected outcome as its persisted prediction,
     * links + charges exactly once, binds, and starts the run.
     */
    @Suppress("UNCHECKED_CAST")
    private fun approveAndRunExperiment(parentId: String) {
        Thread({
            try {
                // EF-AGENT-REVIEW-01 fail-closed gate: an
                // INVALID independent review blocks approval
                // even if the UI gate was bypassed. A review
                // that cannot be computed also blocks (strict
                // mode throws); CAUTION stays approvable by
                // explicit developer choice. Nothing here
                // charges the budget; charging happens once in
                // the manager's approval below.
                val review = reviewFor(parentId, strict = true)
                    ?: throw IllegalStateException("no proposal pending")
                if (review.verdict == AgentReview.Verdict.INVALID) {
                    throw IllegalStateException(
                        "proposal failed independent review: " +
                            (review.objections.firstOrNull()
                                ?: "see findings"))
                }
                val parent = agentManager.getParent(parentId)
                val pending = (parent.agent as? Map<String, Any?>)
                    ?.get("pending_proposal") as? Map<String, Any?>
                    ?: throw IllegalStateException("no proposal pending")
                val seq = (pending["experiment_seq"] as? Number)?.toInt()
                    ?: throw IllegalStateException("proposal seq missing")
                val proposed = SessionBenchBridge.configData(
                    pending["proposed_config"] as? Map<String, Any?>
                        ?: throw IllegalStateException("proposal config bad"))
                    ?: throw IllegalStateException("proposal config invalid")
                val model = staged
                    ?: throw IllegalStateException("stage the model first")
                if (model.sha256.lowercase() != parent.modelSha256) {
                    throw IllegalStateException("staged model differs")
                }
                val profile = try {
                    DeviceCapabilityCollector(this).collect()
                } catch (_: Exception) {
                    null
                }
                val profileSha = profile?.let {
                    BenchConfig.sha256Hex(ProfileSerializer.serialize(it)
                        .toByteArray(Charsets.UTF_8))
                }
                val purpose = pending["purpose"] as? String
                val isRepeat = purpose == AgentApi.PURPOSE_REPEAT
                val isRecovery = purpose == AgentApi.PURPOSE_RECOVERY
                // Expected values are read before branching:
                // recovery records them inside createRecovery
                // (fresh estimate for the new attempt), while
                // the optimize path records below.
                val expectedGenEarly =
                    (pending["expected_gen_tps"] as? Number)?.toDouble()
                val expectedPrefillEarly =
                    (pending["expected_prefill_tps"] as? Number)?.toDouble()
                val child = if (isRepeat) {
                    // EF-FOLLOWUP-02: repeat children carry the
                    // cited run's prediction; no new estimate.
                    val cited = pending["repeat_of_run_id"] as? String
                        ?: throw IllegalStateException("repeat cite bad")
                    val source = agentManager.childForRun(parentId, cited)
                        ?: throw IllegalStateException("cited run unknown")
                    if (model.sha256.lowercase() !=
                        source.modelSha256.lowercase()
                    ) {
                        throw IllegalStateException("staged model differs")
                    }
                    sessionManager.createRepeat(
                        source.sessionId,
                        profile?.device?.model,
                        profileSha,
                        parentSessionId = parentId,
                        experimentSeq = seq
                    )
                } else if (isRecovery) {
                    // EF-MEM-03: recovery children link the
                    // failed run and record the proposal's
                    // fresh expected values (an ESTIMATE for
                    // the new attempt, never a measurement of
                    // the failed one).
                    val cited = pending["recovery_of_run_id"] as? String
                        ?: throw IllegalStateException("recovery cite bad")
                    val source = agentManager.childForRun(parentId, cited)
                        ?: throw IllegalStateException("cited run unknown")
                    if (model.sha256.lowercase() !=
                        source.modelSha256.lowercase()
                    ) {
                        throw IllegalStateException("staged model differs")
                    }
                    val gen = expectedGenEarly
                        ?: throw IllegalStateException("expected gen bad")
                    val prefill = expectedPrefillEarly
                        ?: throw IllegalStateException(
                            "expected prefill bad")
                    @Suppress("UNCHECKED_CAST")
                    val supporting =
                        ((pending["supporting_evidence_ids"] as? List<*>)
                            ?.mapNotNull { it as? String }
                            ?: emptyList())
                    sessionManager.createRecovery(
                        source.sessionId,
                        profile?.device?.model,
                        profileSha,
                        SessionBenchBridge.configMap(proposed),
                        gen, prefill,
                        SessionJson.stringify(linkedMapOf(
                            "expected_gen_tps" to gen,
                            "expected_prefill_tps" to prefill,
                            "hypothesis" to pending["hypothesis"],
                            "uncertainty" to pending["uncertainty"],
                            "supporting_evidence_ids" to supporting,
                            "proposal_hash" to pending["proposal_hash"],
                            "recovery_of_run_id" to cited
                        )),
                        SessionManager.SOURCE_LIVE_META,
                        supporting,
                        parentSessionId = parentId,
                        experimentSeq = seq
                    )
                } else {
                    sessionManager.createSession(
                        mode = "byok",
                        deviceModel = profile?.device?.model,
                        profileSha256 = profileSha,
                        modelSha256 = model.sha256,
                        modelSizeBytes = model.bytes,
                        modelDisplay = model.name,
                        workloadId = parent.workloadId,
                        promptSha256 = parent.promptSha256,
                        config = SessionBenchBridge.configMap(proposed),
                        parentSessionId = parentId,
                        experimentSeq = seq
                    ).also {
                        sessionManager.requestPrediction(it.sessionId)
                    }
                }
                val expectedGen =
                    (pending["expected_gen_tps"] as? Number)?.toDouble()
                        ?: throw IllegalStateException("expected gen bad")
                val expectedPrefill =
                    (pending["expected_prefill_tps"] as? Number)?.toDouble()
                        ?: throw IllegalStateException("expected prefill bad")
                if (!isRepeat && !isRecovery) {
                    sessionManager.recordPrediction(
                        child.sessionId, expectedGen, expectedPrefill,
                        SessionJson.stringify(linkedMapOf(
                            "expected_gen_tps" to expectedGen,
                            "expected_prefill_tps" to expectedPrefill,
                            "hypothesis" to pending["hypothesis"],
                            "uncertainty" to pending["uncertainty"],
                            "supporting_evidence_ids" to
                                pending["supporting_evidence_ids"],
                            "proposal_hash" to pending["proposal_hash"]
                        )),
                        SessionManager.SOURCE_LIVE_META,
                        ((pending["supporting_evidence_ids"] as? List<*>)
                            ?.mapNotNull { it as? String } ?: emptyList()))
                }
                sessionManager.authorizeBenchmark(
                    child.sessionId, "agent experiment $seq approved")
                agentManager.approveProposal(parentId, child.sessionId)
                activeSessionId = child.sessionId
                runOnUiThread {
                    refreshSessionLine()
                    statusText("Experiment $seq approved; starting run.")
                    startBench()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    refreshSessionLine()
                    statusText("Approval failed: ${e.message}")
                    showAgent(parentId)
                }
            }
        }, "edgefit-agent-approve").start()
    }

    /**
     * EF-AGENT-REVIEW-01 developer Modify: edits the pending
     * proposed config with host validation. Repeat configs
     * are fixed (the manager refuses those); model expected
     * values are kept but flagged stale. No Meta call, no
     * budget charge — a local revision of a pending review.
     */
    @Suppress("UNCHECKED_CAST")
    private fun modifyAgentProposal(parentId: String) {
        val parent = try {
            agentManager.getParent(parentId)
        } catch (e: Exception) {
            statusText("Modify failed: ${e.message}")
            return
        }
        val pending = (parent.agent as? Map<String, Any?>)
            ?.get("pending_proposal") as? Map<String, Any?>
        val proposed = pending?.get("proposed_config") as? Map<String, Any?>
        if (pending == null || proposed == null) {
            statusText("Modify failed: no proposal pending.")
            return
        }
        if ((pending["purpose"] as? String) == AgentApi.PURPOSE_REPEAT) {
            statusText("Repeat configs are fixed to the cited run; " +
                "Reject and request a fresh proposal to change them.")
            showAgent(parentId)
            return
        }
        fun numberField(hint: String, value: Any?): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(value?.toString().orEmpty())
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        val ctxField = numberField("Context tokens (n_ctx)",
            proposed["n_ctx"])
        val threadsField = numberField("Threads (n_threads)",
            proposed["n_threads"])
        val predictField = numberField("Generated tokens (n_predict)",
            proposed["n_predict"])
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ctxField)
            addView(threadsField)
            addView(predictField)
        }
        AlertDialog.Builder(this)
            .setTitle("Modify proposed config")
            .setMessage("Host-validated bounds apply. Muse's expected " +
                "values predate your change and are flagged stale; " +
                "approval then runs your config.")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val nCtx = ctxField.text.toString().toIntOrNull()
                val nThreads = threadsField.text.toString().toIntOrNull()
                val nPredict = predictField.text.toString().toIntOrNull()
                if (nCtx == null || nThreads == null || nPredict == null) {
                    statusText("Modify failed: enter integer values.")
                    showAgent(parentId)
                    return@setPositiveButton
                }
                try {
                    agentManager.updatePendingConfig(parentId, nCtx,
                        nThreads, nPredict, deviceMaxThreads())
                    statusText("Proposal modified; review the updated " +
                        "config before approving.")
                } catch (e: Exception) {
                    statusText("Modify failed: ${e.message}")
                }
                showAgent(parentId)
            }
            .setNegativeButton("Cancel") { _, _ -> showAgent(parentId) }
            .show()
    }

    private fun rejectAgentProposal(parentId: String): String {
        return try {
            agentManager.rejectProposal(parentId, "rejected by developer")
            "Proposal preserved as rejected. Request another or Finish."
        } catch (e: Exception) {
            "Reject failed: ${e.message}"
        }
    }

    private fun finishAgentSession(parentId: String): String {
        return try {
            agentManager.finishAgent(parentId)
            "Agent session finished with summary."
        } catch (e: Exception) {
            "Finish failed: ${e.message}"
        }
    }

    private fun humanSummary(profile: CollectedProfile): String {
        val device = profile.device
        val lines = mutableListOf(
            "Model: ${device.manufacturer} ${device.model} (${device.deviceCodename})",
            "Android: ${device.androidRelease} (API ${device.sdkInt})",
            "ABIs: ${device.supportedAbis.joinToString(", ")}",
            "Logical processors: ${device.logicalProcessors}",
            "RAM total: ${device.totalRamBytes / 1048576} MB",
            "RAM available (live): ${device.availRamBytes / 1048576} MB",
            "Thermal: ${profile.thermal?.statusName ?: "unavailable"}",
            "Battery: ${profile.battery?.let { "${it.pct}% (${it.level}/${it.scale})" } ?: "unavailable"}",
            "Collected: ${profile.collectedAtUtc} UTC"
        )
        if (profile.unavailable.isNotEmpty()) {
            lines.add("Unavailable: ${profile.unavailable.joinToString(", ")}")
        }
        return lines.joinToString("\n")
    }

    private data class StagedModel(
        val file: File,
        val sha256: String,
        val bytes: Long,
        val name: String
    )

    companion object {
        private const val REQUEST_CREATE_DOCUMENT = 1
        private const val REQUEST_OPEN_DOCUMENT = 2
        private const val REQUEST_IMPORT_BENCH = 3
        private const val KEY_PENDING_JSON = "pending_json"
        private const val KEY_PENDING_FILENAME = "pending_filename"
        private const val KEY_ACTIVE_SESSION = "active_session"
    private const val KEY_TAB = "nav_tab"
    private const val KEY_ONBOARDING_PAGE = "onboarding_page"
    }
}
