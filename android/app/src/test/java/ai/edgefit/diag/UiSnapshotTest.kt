package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the rotation snapshot codec. */
class UiSnapshotTest {

    @Test
    fun roundTripPreservesEverything() {
        val snapshot = UiSnapshot(
            tab = "BENCHMARK",
            activeSessionId = "S-1",
            pendingJson = "{}",
            pendingFilename = "f.json",
            standaloneOverride = BenchConfig.default(4).copy(nCtx = 1024),
            benchJson = "{\"run_id\":\"R\"}",
            runInFlight = true,
            benchOverlay = true,
            openSheet = Sheets.SESSIONS,
            openSheetArg = "S-2",
            pendingRepeatPrepId = "S-3",
            onboardingPage = 2,
            manualChecked = 1,
            draftOrigin = "https://x.example",
            draftRememberGlobal = true,
            draftRememberMeta = true,
            draftModify = "512,4,64",
            draftCreate = "15.0,20.0",
            statusText = "hello",
            pickerActive = true
        )
        assertEquals(snapshot, UiSnapshotCodec.decode(
            UiSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun emptyMapDecodesToDefaults() {
        val snapshot = UiSnapshotCodec.decode(emptyMap())
        assertEquals(UiSnapshot(), snapshot)
        assertFalse(snapshot.runInFlight)
        assertFalse(snapshot.benchOverlay)
    }

    @Test
    fun corruptOverrideFailsClosedToNull() {
        assertNull(BenchConfigCodec.decode("{oops"))
        assertNull(BenchConfigCodec.decode("{\"n_ctx\":1}"))
        // Structurally valid but outside production bounds.
        val bad = BenchConfigCodec.encode(
            BenchConfig.default().copy(nCtx = 64))
        assertNull(BenchConfigCodec.decode(bad))
        val decoded = UiSnapshotCodec.decode(
            mapOf(UiSnapshotCodec.K_OVERRIDE to "{oops"))
        assertNull(decoded.standaloneOverride)
    }

    @Test
    fun corruptScalarsFailClosedToDefaults() {
        val decoded = UiSnapshotCodec.decode(mapOf(
            UiSnapshotCodec.K_ONBOARDING_PAGE to "NaN",
            UiSnapshotCodec.K_MANUAL_CHECKED to "x",
            UiSnapshotCodec.K_RUN_IN_FLIGHT to "yes"
        ))
        assertEquals(0, decoded.onboardingPage)
        assertNull(decoded.manualChecked)
        assertFalse(decoded.runInFlight)
    }

    @Test
    fun configCodecRoundTrip() {
        val config = BenchConfig.default(2).copy(
            nCtx = 2048, nPredict = 128, timeoutMs = 120000L)
        assertEquals(config, BenchConfigCodec.decode(
            BenchConfigCodec.encode(config)))
    }

    @Test
    fun secretsHaveNoSnapshotField() {
        // Meta keys and upload tokens must never ride the
        // saved-instance bundle (the OS may write it to
        // disk). They live in the memory-only retained
        // holder instead; assert the codec cannot carry
        // them by construction.
        val keys = UiSnapshotCodec.encode(UiSnapshot(
            statusText = "k", draftOrigin = "o")).keys
        for (key in keys) {
            assertFalse(key.contains("token"))
            assertFalse(key.contains("secret"))
            assertFalse(key.contains("key"))
        }
        assertTrue(UiSnapshot().let {
            UiSnapshotCodec.encode(it).keys.none { key ->
                key.contains("token") || key.contains("secret")
            }
        })
    }
}
