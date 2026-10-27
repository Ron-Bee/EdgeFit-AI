package ai.edgefit.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** JVM unit tests for the pure serialization layer (no Android APIs). */
class ProfileSerializerTest {

    private fun sample(): CollectedProfile = CollectedProfile(
        device = DeviceSection(
            manufacturer = "samsung",
            model = "SM-S908U",
            deviceCodename = "dm3q",
            androidRelease = "16",
            sdkInt = 36,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            logicalProcessors = 8,
            totalRamBytes = 12884901888L,
            availRamBytes = 4294967296L
        ),
        thermal = ThermalSection("light", 1),
        battery = BatterySection(82, 100, 82.0, null, null, null, 31.5, 4.1, null),
        unavailable = emptyList(),
        collectedAtUtc = "2026-10-27T00:00:00Z"
    )

    @Test
    fun serializesContractKeys() {
        val json = ProfileSerializer.serialize(sample())
        assertTrue(json.contains("\"contract\":\"edgefit-android-profile\""))
        assertTrue(json.contains("\"contract_version\":1"))
        assertTrue(json.contains("\"sdk_int\":36"))
        assertTrue(json.contains("\"status_name\":\"light\""))
        assertTrue(json.contains("\"pct\":82.0"))
        assertTrue(json.contains("\"unavailable\":[]"))
    }

    @Test
    fun escapesControlCharacters() {
        val profile = sample().copy(
            device = sample().device.copy(model = "A\"B\nC\\D")
        )
        val json = ProfileSerializer.serialize(profile)
        assertTrue(json.contains("\"model\":\"A\\\"B\\nC\\\\D\""))
        assertFalse(json.contains("\"model\":\"A\"B"))
    }

    @Test
    fun nullSectionsSerializeAsNull() {
        val profile = sample().copy(
            thermal = null,
            battery = null,
            unavailable = listOf("thermal.status", "battery.level")
        )
        val json = ProfileSerializer.serialize(profile)
        assertTrue(json.contains("\"thermal\":null"))
        assertTrue(json.contains("\"battery\":null"))
        assertTrue(json.contains("thermal.status"))
    }

    @Test
    fun oversizeDocumentRejected() {
        val profile = sample().copy(
            device = sample().device.copy(model = "x".repeat(70000))
        )
        try {
            ProfileSerializer.serialize(profile)
            fail("expected oversize rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds"))
        }
    }
}
