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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingJson = savedInstanceState?.getString(KEY_PENDING_JSON)
        pendingFilename = savedInstanceState?.getString(KEY_PENDING_FILENAME)
        runner = BenchRunner(this)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val basePadding = dp(16)
        summaryView = TextView(this)
        statusView = TextView(this)
        modelView = TextView(this).apply { text = "Model: none selected" }
        benchView = TextView(this).apply { text = "Benchmark: idle" }
        val export = Button(this).apply { text = "Export Profile" }
        runButton = Button(this).apply { text = "Run Benchmark" }
        cancelButton = Button(this).apply { text = "Cancel"; isEnabled = false }
        saveButton = Button(this).apply { text = "Save Result"; isEnabled = false }
        val select = Button(this).apply { text = "Select Model File" }
        val workloadButton = Button(this).apply { text = "View Workload" }
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
            addView(workloadButton)
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
        benchView.text = "Benchmark: running…"
        runButton.isEnabled = false
        cancelButton.isEnabled = true
        saveButton.isEnabled = false
        runner.setProgressListener { update ->
            runOnUiThread { benchScreen.render(update) }
        }
        val started = runner.start(model.file, model.sha256, model.name) { result ->
            runOnUiThread { onBenchDone(result) }
        }
        if (!started) {
            runner.setProgressListener(null)
            runButton.isEnabled = true
            cancelButton.isEnabled = false
            statusText("A benchmark is already running.")
            return
        }
        val config = currentBenchConfig()
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
    }
}
