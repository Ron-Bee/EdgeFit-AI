package ai.edgefit.diag

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTelemetryTest {

    @Test
    fun ramFormatsBelowAndAboveGb() {
        assertEquals("856 MB",
            DeviceTelemetry.formatRam(856L * 1024 * 1024))
        assertEquals("2.4 GB",
            DeviceTelemetry.formatRam((2.4 * 1024 * 1024 * 1024).toLong()))
        assertEquals("unavailable", DeviceTelemetry.formatRam(0L))
        assertEquals("unavailable", DeviceTelemetry.formatRam(-5L))
    }

    @Test
    fun batteryTempLabel() {
        assertEquals("31.2 °C",
            DeviceTelemetry.batteryTempLabel(31.234))
        assertEquals("unavailable",
            DeviceTelemetry.batteryTempLabel(null))
        assertEquals("unavailable",
            DeviceTelemetry.batteryTempLabel(Double.NaN))
    }

    @Test
    fun batteryPctLabel() {
        assertEquals("74%", DeviceTelemetry.batteryPctLabel(74.0))
        assertEquals("unavailable",
            DeviceTelemetry.batteryPctLabel(null))
    }

    @Test
    fun chargingLabels() {
        assertEquals("Charging (USB)",
            DeviceTelemetry.chargingLabel(
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("Full",
            DeviceTelemetry.chargingLabel(
                BatteryManager.BATTERY_STATUS_FULL, 0))
        assertEquals("Not charging",
            DeviceTelemetry.chargingLabel(
                BatteryManager.BATTERY_STATUS_NOT_CHARGING, 0))
        assertEquals("Discharging",
            DeviceTelemetry.chargingLabel(
                BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
        assertEquals("unavailable",
            DeviceTelemetry.chargingLabel(null, null))
        assertEquals("unavailable",
            DeviceTelemetry.chargingLabel(999, 0))
    }

    @Test
    fun thermalLabels() {
        assertEquals("Normal", DeviceTelemetry.thermalLabel("none"))
        assertEquals("Moderate",
            DeviceTelemetry.thermalLabel("moderate"))
        assertEquals("Severe", DeviceTelemetry.thermalLabel("severe"))
        assertEquals("unavailable",
            DeviceTelemetry.thermalLabel(null))
    }

    private class FakeScheduler : DeviceTelemetry.Scheduler {
        var posts = 0
        var cancels = 0
        override fun postDelayed(
            task: Runnable,
            delayMs: Long
        ): DeviceTelemetry.Cancellable {
            posts++
            return DeviceTelemetry.Cancellable { cancels++ }
        }
    }

    @Test
    fun tickerStartIsIdempotent() {
        val scheduler = FakeScheduler()
        val ticker = DeviceTelemetry.Ticker(1000L, scheduler) {}
        ticker.start()
        ticker.start()
        assertEquals(1, scheduler.posts)
        assertTrue(ticker.isRunning())
    }

    @Test
    fun tickerStopCancelsOnce() {
        val scheduler = FakeScheduler()
        val ticker = DeviceTelemetry.Ticker(1000L, scheduler) {}
        ticker.start()
        ticker.stop()
        ticker.stop()
        assertEquals(1, scheduler.cancels)
        assertFalse(ticker.isRunning())
    }

    @Test
    fun tickerRestartsAfterStop() {
        val scheduler = FakeScheduler()
        val ticker = DeviceTelemetry.Ticker(1000L, scheduler) {}
        ticker.start()
        ticker.stop()
        ticker.start()
        assertEquals(2, scheduler.posts)
        assertTrue(ticker.isRunning())
    }
}
