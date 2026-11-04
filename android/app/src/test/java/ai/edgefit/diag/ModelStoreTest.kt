package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * JVM unit tests for the durable model repository
 * (EF-ANDROID-LIFECYCLE-PERSISTENCE-P0-01). No Android APIs.
 */
class ModelStoreTest {

    /** Little-endian GGUF header builder for tests. */
    private class Head {
        val buf = ByteBuffer.allocate(65536)
            .order(ByteOrder.LITTLE_ENDIAN)

        fun u32(v: Long) {
            buf.putInt(v.toInt())
        }

        fun u64(v: Long) {
            buf.putLong(v)
        }

        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u64(b.size.toLong())
            buf.put(b)
        }

        fun kvString(key: String, value: String) {
            str(key)
            u32(8)
            str(value)
        }

        fun kvU32(key: String, value: Long) {
            str(key)
            u32(4)
            u32(value)
        }

        fun kvTokens(key: String, count: Int) {
            str(key)
            u32(9)
            u32(8)
            u64(count.toLong())
            repeat(count) { str("t$it") }
        }

        fun bytes(): ByteArray =
            buf.array().copyOf(buf.position())
    }

    private fun tinyGguf(): ByteArray {
        val h = Head()
        h.buf.put("GGUF".toByteArray(Charsets.UTF_8))
        h.u32(3)
        h.u64(2) // tensor count
        val body = Head()
        body.kvString("general.architecture", "llama")
        body.kvU32("general.file_type", 15)
        body.kvU32("general.quantization_version", 2)
        body.kvString("tokenizer.ggml.model", "llama")
        body.kvTokens("tokenizer.ggml.tokens", 3)
        body.kvU32("llama.context_length", 8192)
        val count = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN).putLong(6).array()
        return h.bytes() + count + body.bytes()
    }

    private fun runnableVerdict(bytes: ByteArray): GgufInspect.Verdict {
        val tmp = Files.createTempDirectory("gguf").toFile()
        val file = File(tmp, "probe.gguf")
        file.writeBytes(bytes)
        val parse = GgufInspect.inspectFile(file)
        return GgufInspect.assess(
            parse, bytes.size.toLong(), null, emptySet(), null)
    }

    private fun sandbox(): Pair<File, File> {
        val root = Files.createTempDirectory("modelstore").toFile()
        val cache = File(root, "cache").apply { mkdirs() }
        return root to cache
    }

    private fun stageTmp(cache: File, bytes: ByteArray): File {
        val tmp = File(cache, ModelStore.STAGE_TMP_NAME)
        tmp.writeBytes(bytes)
        return tmp
    }

    @Test
    fun selectThenRestoreRoundTrip() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        val bytes = tinyGguf()
        val verdict = runnableVerdict(bytes)
        assertTrue(verdict.runnable)
        val tmp = stageTmp(cache, bytes)
        val sha = ModelStore.sha256HexOf(tmp)
        val model = store.select(
            tmp, sha, bytes.size.toLong(), "tiny.gguf",
            "content://doc/1", verdict, "2026-11-03T00:00:00Z")
        assertEquals("tiny.gguf", model.displayName)
        assertEquals(sha, model.sha256)
        assertEquals("llama", model.architecture)
        assertEquals("Q4_K_M", model.quantization)
        assertEquals(true, model.hasTokenizer)
        // Temp moved into place: no duplicate copy left behind.
        assertFalse(tmp.exists())
        val retained = File(File(root, "models"), "$sha.gguf")
        assertTrue(retained.isFile)
        when (val restored = store.restore()) {
            is ModelStore.Restore.Ready -> {
                assertEquals(model, restored.model)
                assertEquals(retained, restored.file)
            }
            else -> fail("expected Ready, got $restored")
        }
    }

    @Test
    fun reselectSameBytesDedupsWithoutSecondCopy() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        val bytes = tinyGguf()
        val verdict = runnableVerdict(bytes)
        val first = stageTmp(cache, bytes)
        val sha = ModelStore.sha256HexOf(first)
        store.select(first, sha, bytes.size.toLong(),
            "tiny.gguf", null, verdict, "2026-11-03T00:00:00Z")
        val second = stageTmp(cache, bytes)
        store.select(second, sha, bytes.size.toLong(),
            "tiny-renamed.gguf", null, verdict,
            "2026-11-03T00:00:01Z")
        // Temp dropped; exactly one retained file remains.
        assertFalse(second.exists())
        val files = File(root, "models").listFiles()!!
            .filter { it.name.endsWith(".gguf") }
        assertEquals(1, files.size)
        // The latest display name wins the selection record.
        val restored = store.restore()
        assertTrue(restored is ModelStore.Restore.Ready)
        assertEquals("tiny-renamed.gguf",
            (restored as ModelStore.Restore.Ready).model.displayName)
    }

    @Test
    fun refusedVerdictNeverReachesSlot() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        val tmp = stageTmp(cache, "not a model".toByteArray())
        val sha = ModelStore.sha256HexOf(tmp)
        val refused = GgufInspect.Verdict(
            GgufInspect.Status.INVALID_MODEL,
            listOf("invalid GGUF: bad magic"), false, null)
        try {
            store.select(tmp, sha, tmp.length(), "bad.gguf",
                null, refused, "2026-11-03T00:00:00Z")
            fail("refused model must not select")
        } catch (_: ModelStore.StoreError) {
        }
        assertTrue(store.restore() is ModelStore.Restore.None)
    }

    @Test
    fun missingFileRestoresUnavailableNeverReady() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        val bytes = tinyGguf()
        val tmp = stageTmp(cache, bytes)
        store.select(tmp, ModelStore.sha256HexOf(tmp),
            bytes.size.toLong(), "tiny.gguf", "content://doc/1",
            runnableVerdict(bytes), "2026-11-03T00:00:00Z")
        File(File(root, "models"), "selected_model.json").let {
            assertTrue(it.isFile)
        }
        // Simulate the user deleting the file out from under us.
        File(root, "models").listFiles()!!
            .filter { it.name.endsWith(".gguf") }
            .forEach { it.delete() }
        when (val restored = store.restore()) {
            is ModelStore.Restore.Unavailable -> {
                assertTrue(restored.reason.contains("missing"))
                assertTrue(restored.model != null)
            }
            else -> fail("expected Unavailable, got $restored")
        }
    }

    @Test
    fun sizeMismatchRestoresUnavailable() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        val bytes = tinyGguf()
        val tmp = stageTmp(cache, bytes)
        val sha = ModelStore.sha256HexOf(tmp)
        store.select(tmp, sha, bytes.size.toLong(), "tiny.gguf",
            null, runnableVerdict(bytes), "2026-11-03T00:00:00Z")
        // Corrupt the retained bytes (truncate).
        val retained = File(File(root, "models"), "$sha.gguf")
        retained.writeBytes(bytes.copyOf(bytes.size / 2))
        when (val restored = store.restore()) {
            is ModelStore.Restore.Unavailable ->
                assertTrue(restored.reason.contains("changed"))
            else -> fail("expected Unavailable, got $restored")
        }
    }

    @Test
    fun unreadableRecordRestoresUnavailableNeverCrash() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        File(root, "models").apply { mkdirs() }
        File(File(root, "models"), "selected_model.json")
            .writeText("{oops", Charsets.UTF_8)
        when (val restored = store.restore()) {
            is ModelStore.Restore.Unavailable ->
                assertTrue(restored.reason.contains("unreadable"))
            else -> fail("expected Unavailable, got $restored")
        }
    }

    @Test
    fun legacyCacheMigratesOnceThenSelectionWins() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        val bytes = tinyGguf()
        File(cache, ModelStore.LEGACY_SLOT_NAME).writeBytes(bytes)
        val migrated = store.migrateLegacyCache(
            "2026-11-03T00:00:00Z", null, emptySet())
        assertTrue(migrated != null)
        // Bytes moved, not copied.
        assertFalse(File(cache, ModelStore.LEGACY_SLOT_NAME).exists())
        val restored = store.restore()
        assertTrue(restored is ModelStore.Restore.Ready)
        // A second call is a no-op: selection wins.
        assertTrue(store.migrateLegacyCache(
            "2026-11-03T00:00:01Z", null, emptySet()) == null)
    }

    @Test
    fun legacyMigrationSkipsInvalidBytes() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        File(cache, ModelStore.LEGACY_SLOT_NAME)
            .writeBytes("junk".toByteArray())
        assertTrue(store.migrateLegacyCache(
            "2026-11-03T00:00:00Z", null, emptySet()) == null)
        assertTrue(store.restore() is ModelStore.Restore.None)
    }

    @Test
    fun staleTempClearedWithoutTouchingSelection() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        val bytes = tinyGguf()
        val tmp = stageTmp(cache, bytes)
        store.select(tmp, ModelStore.sha256HexOf(tmp),
            bytes.size.toLong(), "tiny.gguf", null,
            runnableVerdict(bytes), "2026-11-03T00:00:00Z")
        // Abandoned mid-copy temp from a killed import.
        File(cache, ModelStore.STAGE_TMP_NAME)
            .writeBytes("partial".toByteArray())
        store.clearStaleTemp()
        assertFalse(
            File(cache, ModelStore.STAGE_TMP_NAME).exists())
        assertTrue(store.restore() is ModelStore.Restore.Ready)
    }

    @Test
    fun selectedModelMapRoundTripAndRefusals() {
        val (root, cache) = sandbox()
        val store = ModelStore(root, cache)
        val bytes = tinyGguf()
        val tmp = stageTmp(cache, bytes)
        val model = store.select(tmp, ModelStore.sha256HexOf(tmp),
            bytes.size.toLong(), "tiny.gguf", null,
            runnableVerdict(bytes), "2026-11-03T00:00:00Z")
        @Suppress("UNCHECKED_CAST")
        val parsed = ModelStore.SelectedModel.fromMap(
            SessionJson.parse(SessionJson.stringify(model.toMap()))
                as Map<String, Any?>)
        assertEquals(model, parsed)
        // Corrupt records fail closed.
        assertTrue(ModelStore.SelectedModel.fromMap(
            model.toMap() + ("sha256" to "xyz")) == null)
        assertTrue(ModelStore.SelectedModel.fromMap(
            model.toMap() + ("size_bytes" to -1L)) == null)
        assertTrue(ModelStore.SelectedModel.fromMap(
            model.toMap() + ("file_name" to "evil.gguf")) == null)
    }
}
