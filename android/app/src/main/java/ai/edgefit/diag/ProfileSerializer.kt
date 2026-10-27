package ai.edgefit.diag

/**
 * Serialization layer: [CollectedProfile] -> bounded contract JSON.
 * Pure JVM with zero dependencies (manual writer: deterministic key order,
 * strict escaping, enforced byte bound). Throws [IllegalArgumentException]
 * when the document would violate the contract bounds.
 */
object ProfileSerializer {

    fun serialize(profile: CollectedProfile): String {
        val device = profile.device
        val root = linkedMapOf<String, Any?>(
            "contract" to ProfileContract.CONTRACT,
            "contract_version" to ProfileContract.CONTRACT_VERSION,
            "collector" to linkedMapOf(
                "name" to ProfileContract.COLLECTOR_NAME,
                "version" to ProfileContract.COLLECTOR_VERSION
            ),
            "collected_at_utc" to profile.collectedAtUtc,
            "device" to linkedMapOf(
                "manufacturer" to device.manufacturer,
                "model" to device.model,
                "device_codename" to device.deviceCodename,
                "android_release" to device.androidRelease,
                "sdk_int" to device.sdkInt,
                "supported_abis" to device.supportedAbis,
                "logical_processors" to device.logicalProcessors,
                "total_ram_bytes" to device.totalRamBytes,
                "avail_ram_bytes" to device.availRamBytes,
                "provenance" to ProfileContract.PROVENANCE_PLATFORM
            ),
            "thermal" to profile.thermal?.let {
                linkedMapOf(
                    "status_name" to it.statusName,
                    "status_code" to it.statusCode,
                    "provenance" to ProfileContract.PROVENANCE_PLATFORM
                )
            },
            "battery" to profile.battery?.let { batteryMap(it) },
            "unavailable" to profile.unavailable
        )
        val json = render(root)
        val bytes = json.toByteArray(Charsets.UTF_8).size
        require(bytes <= ProfileContract.MAX_JSON_BYTES) {
            "profile exceeds ${ProfileContract.MAX_JSON_BYTES} bytes"
        }
        return json
    }

    private fun batteryMap(battery: BatterySection): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "level" to battery.level,
            "scale" to battery.scale,
            "pct" to battery.pct
        )
        battery.status?.let { map["status"] = it }
        battery.health?.let { map["health"] = it }
        battery.plugged?.let { map["plugged"] = it }
        battery.temperatureC?.let { map["temperature_c"] = it }
        battery.voltageV?.let { map["voltage_v"] = it }
        battery.technology?.let { map["technology"] = it }
        map["provenance"] = ProfileContract.PROVENANCE_PLATFORM
        return map
    }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + escape(value) + "\""
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> formatDouble(value)
        is Float -> formatDouble(value.toDouble())
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, entry) ->
            render(key.toString()) + ":" + render(entry)
        }
        is List<*> -> value.joinToString(",", "[", "]") { render(it) }
        else -> throw IllegalArgumentException("unsupported JSON value: $value")
    }

    private fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (char in text) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char < '\u0020') {
                    out.append(String.format("\\u%04x", char.code))
                } else {
                    out.append(char)
                }
            }
        }
        return out.toString()
    }

    private fun formatDouble(value: Double): String {
        require(value.isFinite()) { "non-finite number" }
        return value.toString()
    }
}
