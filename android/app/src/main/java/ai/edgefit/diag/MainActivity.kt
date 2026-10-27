package ai.edgefit.diag

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Presentation layer: device summary in a scrollable region, export action
 * in a persistent bottom bar. Respects status/navigation/cutout insets on
 * all API levels (including enforced edge-to-edge on Android 15+). Export
 * uses the Storage Access Framework on explicit tap only. No storage
 * permission, no network, no background collection, no automatic upload.
 */
class MainActivity : Activity() {

    private lateinit var summaryView: TextView
    private lateinit var statusView: TextView
    private var pendingJson: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingJson = savedInstanceState?.getString(KEY_PENDING_JSON)
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val basePadding = dp(16)
        summaryView = TextView(this)
        statusView = TextView(this)
        val export = Button(this).apply {
            text = "Collect & Export Profile"
            minimumHeight = dp(48)
        }
        val scroller = ScrollView(this).apply { addView(summaryView) }
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(
                export,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(basePadding, basePadding, basePadding, basePadding)
            addView(
                scroller,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            addView(
                actionBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(
                basePadding + bars[0],
                basePadding + bars[1],
                basePadding + bars[2],
                basePadding + bars[3]
            )
            insets
        }
        setContentView(layout)
        refresh()
        export.setOnClickListener {
            val json = refresh()
            if (json == null) {
                statusText("Collection failed; nothing exported.")
                return@setOnClickListener
            }
            pendingJson = json
            launchSaver()
        }
    }

    private fun systemBarInsets(insets: WindowInsets): IntArray {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            val bars = insets.systemWindowInsets
            intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_JSON, pendingJson)
    }

    private fun refresh(): String? {
        return try {
            val profile = DeviceCapabilityCollector(this).collect()
            val json = ProfileSerializer.serialize(profile)
            summaryView.text = humanSummary(profile)
            statusView.text = "Ready. Tap export and choose where to save."
            json
        } catch (e: Exception) {
            summaryView.text = "Collection failed."
            statusView.text = "Error: ${e.message}"
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun launchSaver() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "edgefit-profile-$stamp.json")
        }
        try {
            startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
        } catch (e: Exception) {
            pendingJson = null
            statusText("No document picker available: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE_DOCUMENT) return
        val json = pendingJson
        pendingJson = null
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

    companion object {
        private const val REQUEST_CREATE_DOCUMENT = 1
        private const val KEY_PENDING_JSON = "pending_json"
    }
}
