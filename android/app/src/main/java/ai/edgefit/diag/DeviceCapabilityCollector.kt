package ai.edgefit.diag

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collection layer: Android platform APIs -> [CollectedProfile].
 * No serialization, no UI, no permissions required. Unsupported APIs and
 * missing extras are recorded in [CollectedProfile.unavailable], never
 * zero-filled or inferred.
 */
class DeviceCapabilityCollector(private val context: Context) {

    fun collect(): CollectedProfile {
        val unavailable = mutableListOf<String>()
        val memory = activityMemory()
        val device = DeviceSection(
            manufacturer = clip(Build.MANUFACTURER),
            model = clip(Build.MODEL),
            deviceCodename = clip(Build.DEVICE),
            androidRelease = clip(Build.VERSION.RELEASE),
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            logicalProcessors = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = memory.totalMem,
            availRamBytes = memory.availMem
        )
        val thermal = collectThermal(unavailable)
        val battery = collectBattery(unavailable)
        return CollectedProfile(device, thermal, battery, unavailable.sorted(), utcNow())
    }

    private fun activityMemory(): ActivityManager.MemoryInfo {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    }

    private fun collectThermal(unavailable: MutableList<String>): ThermalSection? {
        if (Build.VERSION.SDK_INT < 29) {
            unavailable.add("thermal.status")
            return null
        }
        return try {
            val power = context.getSystemService(PowerManager::class.java)
            val code = power.currentThermalStatus
            ThermalSection(ProfileContract.thermalName(code), code)
        } catch (e: Exception) {
            unavailable.add("thermal.status")
            null
        }
    }

    private fun collectBattery(unavailable: MutableList<String>): BatterySection? {
        val intent: Intent? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        } catch (e: Exception) {
            null
        }
        if (intent == null) {
            unavailable.add("battery.level")
            return null
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            unavailable.add("battery.level")
            return null
        }
        return BatterySection(
            level = level,
            scale = scale,
            pct = level * 100.0 / scale,
            status = intExtraOrMissing(
                intent, BatteryManager.EXTRA_STATUS, "battery.status", unavailable
            ),
            health = intExtraOrMissing(
                intent, BatteryManager.EXTRA_HEALTH, "battery.health", unavailable
            ),
            plugged = intExtraOrMissing(
                intent, BatteryManager.EXTRA_PLUGGED, "battery.plugged", unavailable
            ),
            temperatureC = intExtraOrMissing(
                intent, BatteryManager.EXTRA_TEMPERATURE, "battery.temperature_c", unavailable
            )?.div(10.0),
            voltageV = intExtraOrMissing(
                intent, BatteryManager.EXTRA_VOLTAGE, "battery.voltage_v", unavailable
            )?.div(1000.0),
            technology = stringExtraOrMissing(
                intent, BatteryManager.EXTRA_TECHNOLOGY, "battery.technology", unavailable
            )
        )
    }

    private fun intExtraOrMissing(
        intent: Intent,
        name: String,
        dotted: String,
        unavailable: MutableList<String>
    ): Int? {
        if (!intent.hasExtra(name)) {
            unavailable.add(dotted)
            return null
        }
        return intent.getIntExtra(name, 0)
    }

    private fun stringExtraOrMissing(
        intent: Intent,
        name: String,
        dotted: String,
        unavailable: MutableList<String>
    ): String? {
        val value = intent.getStringExtra(name)
        if (value == null) {
            unavailable.add(dotted)
            return null
        }
        return clip(value)
    }

    private fun clip(value: String?): String =
        (value ?: "unknown").take(ProfileContract.MAX_STRING_LEN)

    private fun utcNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
