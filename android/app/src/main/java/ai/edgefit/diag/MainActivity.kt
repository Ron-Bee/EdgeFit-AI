package ai.edgefit.diag

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
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

    private lateinit var summaryView: TextView
    private lateinit var statusView: TextView
    private lateinit var modelView: TextView
    private lateinit var benchView: TextView
    private lateinit var runButton: Button
    private lateinit var cancelButton: Button
    private lateinit var saveButton: Button
    private var pendingJson: String? = null
    private var pendingFilename: String? = null
    private var staged: StagedModel? = null
    private var benchJson: String? = null
    private var lastResult: BenchResult? = null
    private lateinit var runner: BenchRunner
    private lateinit var mainRoot: View
    private lateinit var benchScreen: BenchScreenView
    private lateinit var sessionManager: SessionManager
    private lateinit var sessionLine: TextView
    private var activeSessionId: String? = null
    private lateinit var metaVault: MetaKeyVault
    private lateinit var metaClient: MetaSessionClient
    private lateinit var agentManager: AgentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingJson = savedInstanceState?.getString(KEY_PENDING_JSON)
        pendingFilename = savedInstanceState?.getString(KEY_PENDING_FILENAME)
        activeSessionId = savedInstanceState?.getString(KEY_ACTIVE_SESSION)
        runner = BenchRunner(this)
        sessionManager = SessionManager(filesDir)
        metaVault = MetaKeyVault(filesDir, AndroidKeySeal())
        metaClient = MetaSessionClient(
            sessionManager, HttpsMetaTransport(), metaVault)
        agentManager = AgentManager(sessionManager)
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
        summaryView = TextView(this)
        statusView = TextView(this)
        modelView = TextView(this).apply { text = "Model: none selected" }
        benchView = TextView(this).apply { text = "Benchmark: idle" }
        sessionLine = TextView(this).apply {
            text = SessionView.buildActiveLine(null, null)
        }
        val export = Button(this).apply { text = "Export Profile" }
        runButton = Button(this).apply { text = "Run Benchmark" }
        cancelButton = Button(this).apply { text = "Cancel"; isEnabled = false }
        saveButton = Button(this).apply { text = "Save Result"; isEnabled = false }
        val select = Button(this).apply { text = "Select Model File" }
        val workloadButton = Button(this).apply { text = "View Workload" }
        val sessionsButton = Button(this).apply { text = "Sessions" }
        val metaButton = Button(this).apply { text = "Meta API" }
        val agentButton = Button(this).apply { text = "Agent" }
        val runRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(runButton, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancelButton, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun sectionHeader(title: String): TextView =
            TextView(this).apply {
                text = title
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(4))
            }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionHeader("Device Capabilities"))
            addView(summaryView)
            addView(sectionHeader("Benchmark"))
            addView(modelView)
            addView(select)
            addView(benchView)
            addView(sessionLine)
            addView(workloadButton)
            addView(sessionsButton)
            addView(metaButton)
            addView(agentButton)
            addView(saveButton)
        }
        val scroller = ScrollView(this).apply { addView(scrollContent) }
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(export, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(runRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(basePadding, basePadding, basePadding, basePadding)
            addView(scroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actionBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(basePadding + bars[0], basePadding + bars[1],
                basePadding + bars[2], basePadding + bars[3])
            insets
        }
        mainRoot = layout
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
            onBack = { showMain() }
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
        }
        benchScreen.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(bars[0], bars[1], bars[2], bars[3])
            insets
        }
        val root = FrameLayout(this).apply {
            addView(mainRoot, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            addView(benchScreen, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        refresh()
        refreshSessionLine()
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
    }

    override fun onStop() {
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
        mainRoot.visibility = View.GONE
    }

    private fun showMain() {
        applyBenchChrome(dark = false)
        benchScreen.visibility = View.GONE
        mainRoot.visibility = View.VISIBLE
    }

    /**
     * Hides the light action-bar header on the dark benchmark screen so
     * content is never hidden behind it, and keeps status/navigation
     * icons legible on both screens. Bar colors stay system-owned;
     * only icon contrast is toggled, so restore is exact.
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
            summaryView.text = humanSummary(profile)
            statusView.text = "Ready."
            json
        } catch (e: Exception) {
            summaryView.text = "Collection failed."
            statusView.text = "Error: ${e.message}"
            null
        }
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
        runner.setProgressListener { update ->
            runOnUiThread { benchScreen.render(update) }
        }
        val started = runner.startWithConfig(
            model.file, model.sha256, model.name, boundConfig) { result ->
            runOnUiThread { onBenchDone(result) }
        }
        if (!started) {
            runner.setProgressListener(null)
            runButton.isEnabled = true
            cancelButton.isEnabled = false
            statusText("A benchmark is already running.")
            return
        }
        bindSessionToRun()
        // AUD-03: show the config the runner executes (bound
        // override wins), never the live default over an approval.
        val config = BenchConfig.displayConfig(
            boundConfig, currentBenchConfig())
        val profile = try {
            DeviceCapabilityCollector(this).collect()
        } catch (_: Exception) {
            null
        }
        benchScreen.showRun(
            model.name, model.bytes, model.sha256, config,
            runtimeVersionLabel(), runtimeAbiLabel(profile))
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
        lastResult = result
        runButton.isEnabled = true
        cancelButton.isEnabled = false
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
            benchView.append("\nStandalone physical measurement (no session).")
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
                    modelView.text = "Model: $name (${bytes / 1048576} MB)"
                }
            } catch (e: Exception) {
                runOnUiThread { modelView.text = "Stage failed: ${e.message}" }
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

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OPEN_DOCUMENT)
        } catch (e: Exception) {
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
            startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
        } catch (e: Exception) {
            pendingJson = null
            pendingFilename = null
            statusText("No document picker available: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_DOCUMENT) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null) {
                statusText("Model selection cancelled.")
            } else {
                stageModel(uri)
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

    private fun statusText(message: String) {
        statusView.text = message
    }

    // ---- sessions (EF-SESSION-01) ----

    private fun showSessions() {
        SessionView.show(
            this,
            load = { sessionManager.listSessions() },
            activeId = { activeSessionId },
            onCreate = { gen, prefill -> createOfflineSession(gen, prefill) },
            onBind = { id -> bindSession(id) },
            onDelete = { id -> deleteSession(id) },
            onPredict = { id -> startLivePrediction(id) },
            onFollowUp = { id -> startLiveFollowUp(id) }
        )
    }

    /**
     * Creates an offline-test session from the staged model and the
     * exact config the next run will use, then authorizes and binds it.
     * Labeled offline_test throughout; never a Meta prediction.
     */
    private fun createOfflineSession(gen: Double, prefill: Double): String {
        val model = staged ?: return "Stage a model file first."
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
            "Created ${created.sessionId}, authorized and bound to next run."
        } catch (e: Exception) {
            "Session creation failed: ${e.message}"
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

    private fun deleteSession(id: String): String {
        if (activeSessionId == id) activeSessionId = null
        refreshSessionLine()
        return if (sessionManager.deleteSession(id)) "Deleted $id."
        else "No session $id."
    }

    private fun refreshSessionLine() {
        val id = activeSessionId
        if (id == null) {
            sessionLine.text = SessionView.buildActiveLine(null, null)
            return
        }
        val record = try {
            sessionManager.get(id)
        } catch (_: Exception) {
            null
        }
        if (record == null) {
            activeSessionId = null
            sessionLine.text = SessionView.buildActiveLine(null, null)
        } else {
            sessionLine.text = SessionView.buildActiveLine(id, record)
        }
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
        MetaView.showAuthorizeDialog(
            this,
            MetaView.authorizeText(
                "Live throughput prediction", fields, keyNote()),
            "Predict"
        ) {
            Thread({
                runLivePrediction(existingId)
            }, "edgefit-meta-predict").start()
        }
    }

    private fun runLivePrediction(existingId: String?) {
        try {
            val id = existingId ?: createLiveSession()
            if (existingId == null ||
                sessionManager.get(id).state == SessionState.SESSION_CREATED
            ) {
                sessionManager.requestPrediction(id)
            }
            val spec = livePredictionSpec(id)
            val record = metaClient.requestPrediction(spec)
            sessionManager.authorizeBenchmark(id, "live prediction recorded")
            activeSessionId = id
            runOnUiThread {
                refreshSessionLine()
                statusText("Live prediction recorded for $id; " +
                    "bound to the next run.")
                MetaView.showResultDialog(this, "Live Meta Prediction",
                    MetaView.predictionText(record) +
                        "\n\nRun the benchmark to measure against it.")
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
            "thermal + memory-method qualifiers"
        )
        MetaView.showAuthorizeDialog(
            this,
            MetaView.authorizeText(
                "Follow-up assessment for $sessionId", fields, keyNote()),
            "Request"
        ) {
            Thread({
                runLiveFollowUp(sessionId)
            }, "edgefit-meta-followup").start()
        }
    }

    private fun runLiveFollowUp(sessionId: String) {
        try {
            val before = sessionManager.get(sessionId)
            if (before.state == SessionState.COMPARISON_READY) {
                sessionManager.requestFollowup(sessionId)
            }
            val record = metaClient.requestFollowUp(sessionId)
            runOnUiThread {
                refreshSessionLine()
                statusText("Follow-up recorded for $sessionId.")
                MetaView.showResultDialog(this, "Meta Follow-Up",
                    MetaView.followUpText(record))
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
            onFinish = { id -> finishAgentSession(id) }
        )
    }

    private fun agentDetail(id: String): AgentView.AgentDetail {
        val gate = agentManager.proposalGate(id)
        return AgentView.AgentDetail(
            parent = agentManager.getParent(id),
            children = agentManager.childrenOf(id),
            pack = agentManager.buildPack(id),
            gateOk = gate.first,
            gateReason = gate.second
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
        val fields = listOf(
            "agent evidence pack (run ids, configs, throughputs, errors)",
            "current configuration + next experiment number",
            "model SHA-256 + workload id",
            "experiment budget used/max"
        )
        MetaView.showAuthorizeDialog(
            this,
            MetaView.authorizeText(
                "Optimization proposal for $parentId", fields, keyNote()),
            "Request"
        ) {
            Thread({
                runAgentProposal(parentId)
            }, "edgefit-agent-proposal").start()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runAgentProposal(parentId: String) {
        try {
            val gate = agentManager.proposalGate(parentId)
            if (!gate.first) throw IllegalStateException(gate.second)
            val parent = agentManager.getParent(parentId)
            val pack = agentManager.buildPack(parentId)
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
                    deviceMaxThreads = deviceMaxThreads()
                )
            )
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
                val child = sessionManager.createSession(
                    mode = "byok",
                    deviceModel = profile?.device?.model,
                    profileSha256 = profile?.let {
                        BenchConfig.sha256Hex(ProfileSerializer.serialize(it)
                            .toByteArray(Charsets.UTF_8))
                    },
                    modelSha256 = model.sha256,
                    modelSizeBytes = model.bytes,
                    modelDisplay = model.name,
                    workloadId = parent.workloadId,
                    promptSha256 = parent.promptSha256,
                    config = SessionBenchBridge.configMap(proposed),
                    parentSessionId = parentId,
                    experimentSeq = seq
                )
                sessionManager.requestPrediction(child.sessionId)
                val expectedGen =
                    (pending["expected_gen_tps"] as? Number)?.toDouble()
                        ?: throw IllegalStateException("expected gen bad")
                val expectedPrefill =
                    (pending["expected_prefill_tps"] as? Number)?.toDouble()
                        ?: throw IllegalStateException("expected prefill bad")
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
        private const val KEY_PENDING_JSON = "pending_json"
        private const val KEY_PENDING_FILENAME = "pending_filename"
        private const val KEY_ACTIVE_SESSION = "active_session"
    }
}
