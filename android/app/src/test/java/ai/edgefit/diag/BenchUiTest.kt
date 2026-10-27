package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JVM unit tests for benchmark progress, states, and display math. */
class BenchUiTest {

    private fun nativeMailbox(): ByteBuffer {
        // Simulates bench_jni.cpp writes: raw LE int32 cells.
        val buf = ByteBuffer.allocateDirect(BenchProgressLayout.SIZE_BYTES)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0 until BenchProgressLayout.SIZE_BYTES step 4) {
            buf.putInt(offset, 0)
        }
        return buf
    }

    @Test
    fun statePipelineIsForwardOnly() {
        val order = listOf(
            BenchExecutionState.VALIDATING_MODEL,
            BenchExecutionState.LOADING_MODEL,
            BenchExecutionState.PROCESSING_PROMPT,
            BenchExecutionState.GENERATING,
            BenchExecutionState.FINALIZING
        )
        assertTrue(BenchExecutionState.can(
            BenchExecutionState.IDLE, BenchExecutionState.VALIDATING_MODEL))
        for (i in order.indices) {
            for (j in order.indices) {
                val can = BenchExecutionState.can(order[i], order[j])
                if (j >= i) assertTrue("forward $i->$j", can)
                else assertFalse("backward $i->$j", can)
            }
        }
        assertFalse(BenchExecutionState.can(
            BenchExecutionState.GENERATING, BenchExecutionState.IDLE))
        assertFalse(BenchExecutionState.can(
            BenchExecutionState.IDLE, BenchExecutionState.GENERATING))
    }

    @Test
    fun terminalStatesAndReset() {
        val terminals = listOf(
            BenchExecutionState.COMPLETED, BenchExecutionState.FAILED,
            BenchExecutionState.CANCELLED, BenchExecutionState.TIMEOUT)
        for (terminal in terminals) {
            assertTrue(BenchExecutionState.isTerminal(terminal))
            assertTrue(BenchExecutionState.can(terminal, BenchExecutionState.IDLE))
            assertFalse(BenchExecutionState.can(
                terminal, BenchExecutionState.GENERATING))
            assertTrue(BenchExecutionState.can(
                BenchExecutionState.GENERATING, terminal))
        }
        assertFalse(BenchExecutionState.isTerminal(BenchExecutionState.GENERATING))
        assertFalse(BenchExecutionState.isTerminal(BenchExecutionState.IDLE))
        // Cancelled/failed/completed are distinct terminals.
        assertEquals(4, terminals.toSet().size)
    }

    @Test
    fun nativeStageMapping() {
        assertEquals(BenchExecutionState.LOADING_MODEL,
            BenchExecutionState.fromNativeStage(BenchProgressLayout.STAGE_LOADING))
        assertEquals(BenchExecutionState.PROCESSING_PROMPT,
            BenchExecutionState.fromNativeStage(BenchProgressLayout.STAGE_PREFILL))
        assertEquals(BenchExecutionState.GENERATING,
            BenchExecutionState.fromNativeStage(BenchProgressLayout.STAGE_GENERATING))
        assertEquals(null, BenchExecutionState.fromNativeStage(
            BenchProgressLayout.STAGE_NATIVE_DONE))
        assertEquals(null, BenchExecutionState.fromNativeStage(
            BenchProgressLayout.STAGE_NATIVE_ERROR))
    }

    @Test
    fun mailboxRoundTrip() {
        val buf = nativeMailbox()
        buf.putInt(BenchProgressLayout.OFF_MAGIC, BenchProgressLayout.MAGIC)
        buf.putInt(BenchProgressLayout.OFF_VERSION, BenchProgressLayout.VERSION)
        buf.putInt(BenchProgressLayout.OFF_STAGE,
            BenchProgressLayout.STAGE_GENERATING)
        buf.putInt(BenchProgressLayout.OFF_TARGET, 64)
        buf.putInt(BenchProgressLayout.OFF_GENERATED, 12)
        buf.putInt(BenchProgressLayout.OFF_PREFILL_TOKENS, 23)
        buf.putInt(BenchProgressLayout.OFF_LOAD_MS, 3000)
        buf.putInt(BenchProgressLayout.OFF_PREFILL_MS, 1020)
        buf.putInt(BenchProgressLayout.OFF_GEN_MS, 900)
        val snap = BenchProgressMailbox.read(buf)
        assertTrue(snap.valid)
        assertEquals(BenchProgressLayout.STAGE_GENERATING, snap.nativeStage)
        assertEquals(64, snap.targetTokens)
        assertEquals(12, snap.generatedTokens)
        assertEquals(23, snap.prefillTokens)
        assertEquals(3000L, snap.loadMs)
        assertEquals(1020L, snap.prefillMs)
        assertEquals(900L, snap.genMs)
        assertEquals(BenchProgressLayout.ERR_NONE, snap.nativeError)
    }

    @Test
    fun mailboxRejectsBadHeaderOrSize() {
        val badMagic = nativeMailbox()
        badMagic.putInt(BenchProgressLayout.OFF_MAGIC, 0xDEAD)
        assertFalse(BenchProgressMailbox.read(badMagic).valid)
        val badVersion = nativeMailbox()
        badVersion.putInt(BenchProgressLayout.OFF_MAGIC, BenchProgressLayout.MAGIC)
        badVersion.putInt(BenchProgressLayout.OFF_VERSION, 99)
        assertFalse(BenchProgressMailbox.read(badVersion).valid)
        val short = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        assertFalse(BenchProgressMailbox.read(short).valid)
    }

    @Test
    fun mailboxClampsCounts() {
        val buf = nativeMailbox()
        buf.putInt(BenchProgressLayout.OFF_MAGIC, BenchProgressLayout.MAGIC)
        buf.putInt(BenchProgressLayout.OFF_VERSION, BenchProgressLayout.VERSION)
        buf.putInt(BenchProgressLayout.OFF_TARGET, 64)
        buf.putInt(BenchProgressLayout.OFF_GENERATED, 9999)
        buf.putInt(BenchProgressLayout.OFF_STAGE, 77)
        val snap = BenchProgressMailbox.read(buf)
        assertTrue(snap.valid)
        // Displayed count never exceeds the reported target.
        assertEquals(64, snap.generatedTokens)
        assertEquals(BenchProgressLayout.STAGE_NATIVE_ERROR, snap.nativeStage)
    }

    @Test
    fun provisionalTpsMath() {
        assertEquals(10.0, BenchProgressMath.provisionalTps(64, 6400L), 1e-9)
        assertEquals(0.0, BenchProgressMath.provisionalTps(0, 6400L), 0.0)
        assertEquals(0.0, BenchProgressMath.provisionalTps(64, 0L), 0.0)
        assertEquals(0.0, BenchProgressMath.provisionalTps(-5, 6400L), 0.0)
        assertEquals(0.0, BenchProgressMath.provisionalTps(64, -1L), 0.0)
    }

    @Test
    fun clampGeneratedBounds() {
        assertEquals(12, BenchProgressMath.clampGenerated(12, 64))
        assertEquals(64, BenchProgressMath.clampGenerated(999, 64))
        assertEquals(0, BenchProgressMath.clampGenerated(-3, 64))
        assertEquals(0, BenchProgressMath.clampGenerated(12, 0))
    }

    @Test
    fun tpsSamplesBoundedAndOrdered() {
        val samples = BenchTpsSamples(capacity = 4)
        samples.add(100L, 1)
        samples.add(50L, 9)
        samples.add(200L, 2)
        samples.add(200L, 3)
        samples.add(300L, 4)
        samples.add(400L, 5)
        samples.add(500L, 6)
        val snap = samples.snapshot()
        assertEquals(4, snap.size)
        // Out-of-order write dropped; same-timestamp replaced; oldest evicted.
        assertEquals(listOf(200L, 300L, 400L, 500L), snap.map { it.elapsedMs })
        assertEquals(listOf(3, 4, 5, 6), snap.map { it.generated })
        samples.clear()
        assertEquals(0, samples.size)
    }

    @Test
    fun stageLabelsCoverAllStates() {
        val states = listOf(
            BenchExecutionState.IDLE, BenchExecutionState.VALIDATING_MODEL,
            BenchExecutionState.LOADING_MODEL,
            BenchExecutionState.PROCESSING_PROMPT, BenchExecutionState.GENERATING,
            BenchExecutionState.FINALIZING, BenchExecutionState.COMPLETED,
            BenchExecutionState.FAILED, BenchExecutionState.CANCELLED,
            BenchExecutionState.TIMEOUT)
        for (state in states) {
            val label = BenchUiText.stageLabel(state)
            assertFalse(label.isEmpty())
            assertFalse(label.contains("_"))
        }
        assertEquals("Generating",
            BenchUiText.stageLabel(BenchExecutionState.GENERATING))
    }

    @Test
    fun elapsedLabelFormats() {
        assertEquals("00:00", BenchUiText.elapsedLabel(0L))
        assertEquals("00:01", BenchUiText.elapsedLabel(1500L))
        assertEquals("01:01", BenchUiText.elapsedLabel(61000L))
        assertEquals("00:00", BenchUiText.elapsedLabel(-500L))
        assertEquals("99:59", BenchUiText.elapsedLabel(Long.MAX_VALUE))
    }

    @Test
    fun safeErrorFiltersPaths() {
        assertEquals("Unknown error.", BenchUiText.safeError(null))
        assertEquals("Unknown error.", BenchUiText.safeError("  "))
        assertEquals("insufficient available memory",
            BenchUiText.safeError("insufficient available memory"))
        assertEquals(
            "Benchmark runtime unavailable (native library failed to load).",
            BenchUiText.safeError(
                "runtime unavailable: dlopen failed: /data/app/lib.so not found"))
        assertEquals(
            "Native benchmark call failed. See device logs for details.",
            BenchUiText.safeError("native call failed: bad JNI handle 0x1234"))
    }
}
