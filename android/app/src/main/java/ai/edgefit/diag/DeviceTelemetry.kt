package ai.edgefit.diag

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * EF-UX-01E live device telemetry. [sample] reads only
 * cheap platform APIs (no full profile, no shell, no new
 * permissions); formatters are pure and unit-tested.
 * Battery temperature (tenths of a degree C from the
 * platform) and Android thermal status are separate
 * measurements and are never mixed.
 */
object DeviceTelemetry {

    /** Home glanceable cadence; details uses [DETAILS_MS]. */
    const val HOME_MS = 30_000L
    const val DETAILS_MS = 15_000L

    data class Snapshot(
        val availRamBytes: Long,
        val totalRamBytes: Long,
        val lowMemory: Boolean,
        /** Android's low-memory threshold; 0 when unread. */
        val thresholdBytes: Long = 0L,
        val thermalName: String?,
        val batteryPct: Double?,
        val batteryStatus: Int?,
        val batteryPlugged: Int?,
        val batteryTempC: Double?
    )

    /**
     * Lightweight sample: ActivityManager memory +
     * PowerManager thermal + sticky battery intent. Never
     * throws; missing signals stay null (displayed as
     * unavailable, never fabricated).
     */
    fun sample(context: Context): Snapshot {
        var avail = 0L
        var total = 0L
        var low = false
        var threshold = 0L
        try {
            val manager = context.getSystemService(
                Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            avail = info.availMem
            total = info.totalMem
            low = info.lowMemory
            threshold = info.threshold
        } catch (_: Exception) {
        }
        var thermal: String? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val power =
                    context.getSystemService(PowerManager::class.java)
                thermal = ProfileContract.thermalName(
                    power.currentThermalStatus)
            } catch (_: Exception) {
            }
        }
        var pct: Double? = null
        var status: Int? = null
        var plugged: Int? = null
        var tempC: Double? = null
        try {
            @Suppress("DEPRECATION")
            val intent: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
            if (intent != null) {
                val level = intent.getIntExtra(
                    BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(
                    BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    pct = level * 100.0 / scale
                }
                status = intent.getIntExtra(
                    BatteryManager.EXTRA_STATUS, -1)
                    .takeIf { it >= 0 }
                plugged = intent.getIntExtra(
                    BatteryManager.EXTRA_PLUGGED, -1)
                    .takeIf { it >= 0 }
                tempC = intent.getIntExtra(
                    BatteryManager.EXTRA_TEMPERATURE,
                    Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE }
                    ?.div(10.0)
            }
        } catch (_: Exception) {
        }
        return Snapshot(avail, total, low, threshold, thermal, pct,
            status, plugged, tempC)
    }

    /** 2.4 GB above 1024 MB, else integer MB. */
    fun formatRam(bytes: Long): String {
        if (bytes <= 0) return "unavailable"
        val mb = bytes / (1024L * 1024L)
        if (mb < 1024) return "$mb MB"
        return "%.1f GB".format(mb / 1024.0)
    }

    fun batteryPctLabel(pct: Double?): String =
        if (pct == null) "unavailable" else "${pct.toInt()}%"

    fun chargingLabel(status: Int?, plugged: Int?): String {
        val base = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> null
        } ?: return "unavailable"
        val via = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> null
        }
        return if (via != null && base == "Charging") "$base ($via)"
        else base
    }

    /** Platform tenths-of-C already converted; null stays null. */
    fun batteryTempLabel(tempC: Double?): String =
        if (tempC == null || !tempC.isFinite()) "unavailable"
        else "%.1f °C".format(tempC)

    /** User-friendly thermal label; null stays unavailable. */
    fun thermalLabel(name: String?): String = when (name) {
        "none" -> "Normal"
        "light" -> "Light"
        "moderate" -> "Moderate"
        "severe" -> "Severe"
        "critical" -> "Critical"
        "emergency" -> "Emergency"
        "shutdown" -> "Shutdown"
        null -> "unavailable"
        else -> name.replaceFirstChar { it.uppercase() }
    }

    // ---- visibility-gated ticker ----

    fun interface Cancellable {
        fun cancel()
    }

    interface Scheduler {
        fun postDelayed(task: Runnable, delayMs: Long): Cancellable
    }

    /**
     * Repeating tick with idempotent start/stop. The
     * scheduler is injected so JVM tests use a fake; the
     * activity passes a main-looper Handler wrapper.
     */
    class Ticker(
        private val intervalMs: Long,
        private val scheduler: Scheduler,
        private val onTick: () -> Unit
    ) {
        private var current: Cancellable? = null
        private val task: Runnable = Runnable {
            // Re-arm before firing so a throwing
            // observer cannot silently stop the loop;
            // stop() inside onTick still wins because
            // it cancels the fresh handle.
            current = scheduler.postDelayed(task, intervalMs)
            onTick()
        }

        @Synchronized
        fun start() {
            if (current != null) return
            current = scheduler.postDelayed(task, intervalMs)
        }

        @Synchronized
        fun stop() {
            current?.cancel()
            current = null
        }

        @Synchronized
        fun isRunning(): Boolean = current != null
    }
}
