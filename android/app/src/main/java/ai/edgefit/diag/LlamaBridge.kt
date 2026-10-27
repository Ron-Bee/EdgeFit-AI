package ai.edgefit.diag

import java.nio.ByteBuffer

/**
 * Isolated JNI boundary to libbench-jni. Library load failure is captured,
 * never thrown, so the app degrades to a clean "runtime unavailable" state.
 */
object LlamaBridge {
    val available: Boolean
    val loadError: String?

    init {
        var loaded = false
        var error: String? = null
        try {
            System.loadLibrary("bench-jni")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            error = e.message ?: "load failed"
        } catch (e: SecurityException) {
            error = e.message ?: "load denied"
        }
        available = loaded
        loadError = error
    }

    /** One-shot cooperative-cancel mailbox shared with native code. */
    fun newCancelMailbox(): ByteBuffer = ByteBuffer.allocateDirect(1)

    fun cancel(mailbox: ByteBuffer) {
        mailbox.put(0, 1)
    }

    external fun nativeVersion(): String
    external fun nativeAllocBytes(): Long

    /**
     * Runs load + prefill + generation synchronously. Returns a JSON object
     * string: {"ok":true,...} or {"ok":false,"error":...}. Polls the mailbox
     * byte each token; nonzero aborts promptly with cancelled=true.
     */
    external fun nativeRun(
        modelPath: String,
        prompt: String,
        nCtx: Int,
        nThreads: Int,
        nPredict: Int,
        cancelMailbox: ByteBuffer
    ): String
}
