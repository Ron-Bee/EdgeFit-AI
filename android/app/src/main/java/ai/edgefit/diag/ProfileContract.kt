package ai.edgefit.diag

/**
 * Contract v1 constants and profile model. Pure JVM: no Android imports.
 * Mirrors docs/android-profile-contract.md.
 */
object ProfileContract {
    const val CONTRACT = "edgefit-android-profile"
    const val CONTRACT_VERSION = 1
    const val COLLECTOR_NAME = "edgefit-diag"
    const val COLLECTOR_VERSION = "0.1.0"
    const val MAX_JSON_BYTES = 65536
    const val MAX_STRING_LEN = 128
    const val PROVENANCE_PLATFORM = "platform_reported"

    // PowerManager thermal constants: NONE=0 LIGHT=1 MODERATE=2 SEVERE=3
    // CRITICAL=4 EMERGENCY=5 SHUTDOWN=6. Unknown codes stay "unknown".
    fun thermalName(code: Int): String = when (code) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown"
    }
}

data class DeviceSection(
    val manufacturer: String,
    val model: String,
    val deviceCodename: String,
    val androidRelease: String,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val logicalProcessors: Int,
    val totalRamBytes: Long,
    val availRamBytes: Long
)

data class ThermalSection(val statusName: String, val statusCode: Int)

data class BatterySection(
    val level: Int,
    val scale: Int,
    val pct: Double,
    val status: Int?,
    val health: Int?,
    val plugged: Int?,
    val temperatureC: Double?,
    val voltageV: Double?,
    val technology: String?
)

data class CollectedProfile(
    val device: DeviceSection,
    val thermal: ThermalSection?,
    val battery: BatterySection?,
    val unavailable: List<String>,
    val collectedAtUtc: String
)
