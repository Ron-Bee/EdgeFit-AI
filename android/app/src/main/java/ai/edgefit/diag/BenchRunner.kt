package ai.edgefit.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/** Phase machine. Pure transition table (unit-tested). */
object BenchPhases {
    const val IDLE = "IDLE"
    const val RUNNING = "RUNNING"
    private val TERMINAL =
        setOf(BenchContract.STATUS_COMPLETED, BenchContract.STATUS_CANCELLED,
            BenchContract.STATUS_FAILED, BenchContract.STATUS_TIMEOUT)

    fun can(from: String, to: String): Boolean =
        (from == IDLE && to == RUNNING) ||
            (from == RUNNING && to in TERMINAL) ||
            (from in TERMINAL && to == IDLE)

    fun isTerminal(phase: String): Boolean = phase in TERMINAL
}

/**
 * Benchmark orchestrator: preflight guards, one worker thread, watchdog
 * timeout, cooperative cancellation, measurement assembly. Exactly one
 * [BenchResult] per run, delivered on the worker thread.
 */
class BenchRunner(private val context: Context) {
    @Volatile var phase: String = BenchPhases.IDLE
        private set
    /** Display execution state; see BenchExecutionState. */
    @Volatile var execState: String = BenchExecutionState.IDLE
        private set
    private var worker: Thread? = null
    private var mailbox: ByteBuffer? = null
    private var progressBox: ByteBuffer? = null
    private val finished = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    /** User/lifecycle cancellation; also aborts admission recheck. */
    @Volatile private var cancelRequested = false
    @Volatile private var midThermal: String? = null
    @Volatile private var liveThermal: String? = null
    @Volatile private var livePssKb: Int = -1
    @Volatile private var wallStartMs: Long = 0L
    @Volatile private var progressListener: ((BenchLiveUpdate) -> Unit)? = null
    private val lock = Any()

    /**
     * Live updates at ~4 Hz on the sampler thread. The activity must
     * marshal to the main thread. Listener exceptions are swallowed so a
     * UI bug can never kill timeout enforcement.
     */
    fun setProgressListener(listener: ((BenchLiveUpdate) -> Unit)?) {
        progressListener = listener
    }

    fun start(
        modelFile: File,
        modelSha256: String,
        displayName: String,
        listener: (BenchResult) -> Unit
    ): Boolean = startWithConfig(
        modelFile, modelSha256, displayName, null, listener)

    /**
     * Narrow authorized configuration bridge (EF-AGENT-01): runs the
     * identical native workload with a host-validated config instead
     * of the fixed default. Null keeps legacy default behavior
     * byte-for-byte. The executed config is recorded in the result.
     */
    fun startWithConfig(
        modelFile: File,
        modelSha256: String,
        displayName: String,
        config: BenchConfigData?,
        listener: (BenchResult) -> Unit
    ): Boolean {
        synchronized(lock) {
            if (!BenchPhases.can(phase, BenchPhases.RUNNING)) return false
            phase = BenchPhases.RUNNING
        }
        finished.set(false)
        timedOut.set(false)
        cancelRequested = false
        midThermal = null
        liveThermal = null
        livePssKb = -1
        execState = BenchExecutionState.VALIDATING_MODEL
        val box = LlamaBridge.newCancelMailbox()
        mailbox = box
        val prog = LlamaBridge.newProgressMailbox()
        progressBox = prog
        worker = Thread({
            val result = runBenchmark(
                modelFile, modelSha256, displayName, box, prog, config)
            synchronized(lock) { phase = result.status }
            execState = result.status
            emitLive()
            listener(result)
        }, "edgefit-bench")
        worker!!.start()
        return true
    }

    fun cancel() {
        cancelRequested = true
        try {
            mailbox?.let { LlamaBridge.cancel(it) }
        } catch (_: Exception) {
        }
    }

    fun joinGracefully(timeoutMs: Long): Boolean {
        return try {
            worker?.join(timeoutMs)
            worker?.let { !it.isAlive } ?: true
        } catch (_: InterruptedException) {
            false
        }
    }

    fun reset(): Boolean = synchronized(lock) {
        if (!BenchPhases.can(phase, BenchPhases.IDLE)) return false
        phase = BenchPhases.IDLE
        execState = BenchExecutionState.IDLE
        true
    }

    /** Builds one live update from the mailbox; safe on any thread. */
    private fun emitLive() {
        val listener = progressListener ?: return
        val box = progressBox
        val snap = if (box == null) BenchProgressMailbox.invalid()
            else BenchProgressMailbox.read(box)
        val mapped = BenchExecutionState.fromNativeStage(snap.nativeStage)
        if (snap.valid && mapped != null &&
            BenchExecutionState.can(execState, mapped)
        ) {
            execState = mapped
        }
        val update = BenchLiveUpdate(
            execState = execState,
            progress = snap,
            elapsedMs = SystemClock.elapsedRealtime() - wallStartMs,
            thermal = liveThermal,
            pssKb = livePssKb
        )
        try {
            listener(update)
        } catch (_: Exception) {
        }
    }

    private fun runBenchmark(
        modelFile: File,
        modelSha256: String,
        displayName: String,
        box: ByteBuffer,
        prog: ByteBuffer,
        configOverride: BenchConfigData?
    ): BenchResult {
        val stamp = utcStamp()
        val runId = formatRunId(stamp, randomHex8())
        val collectedAt = utcNow()
        val profile = DeviceCapabilityCollector(context).collect()
        val device = profile.device
        val snapshot = BenchDeviceSnapshot(
            profileRef = BenchConfig.sha256Hex(
                ProfileSerializer.serialize(profile).toByteArray(Charsets.UTF_8)),
            model = device.model,
            sdkInt = device.sdkInt,
            supportedAbis = device.supportedAbis,
            logicalProcessors = device.logicalProcessors,
            totalRamBytes = device.totalRamBytes
        )
        val config = BenchConfig.resolveExecutionConfig(
            configOverride, device.logicalProcessors)
        // EF-MEM-01: anchor elapsed time at THIS run's entry so
        // early-return paths (refusals) never display a stale
        // previous-run start (the observed 02:09 artifact).
        wallStartMs = SystemClock.elapsedRealtime()
        var thermalBefore = sampleThermal()
        val pssBefore = processPssKb()
        val modelBytes = modelFile.length()
        val memInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getMemoryInfo(memInfo)
        // EF-MEM-03: start conditions recorded for EVERY
        // run, diagnostics or not. End fields fill after
        // the watchdog joins; failures keep start-only.
        var nativeStarted = false
        var memConditions = MemConditions.startOf(
            memInfo.totalMem, memInfo.availMem,
            memInfo.lowMemory, memInfo.threshold)

        // EF-MEM-02 run-scoped diagnostics. Each note is
        // one cheap platform sample (~sub-ms), gated on the
        // developer toggle; default-off cost is one boolean.
        // POST_NATIVE_INIT is intentionally unobservable
        // (native frees before returning); POST_NATIVE
        // bounds retention instead. No hot-path sampling.
        val diagSamples = mutableListOf<MemDiag.Sample>()
        fun diagNote(checkpoint: MemDiag.Checkpoint) {
            if (!MemDiag.Store.isEnabled()) return
            try {
                val sample = MemDiag.sampleNow(context, checkpoint)
                MemDiag.Store.note(sample)
                diagSamples.add(sample)
            } catch (_: Exception) {
            }
        }
        fun diagExport(): MemDiag.RunExport? {
            if (diagSamples.isEmpty()) return null
            val build = nativeBuildMap()
            return MemDiag.RunExport(
                diagSamples.toList(),
                build["variant"] ?: "unknown",
                build["native_opt"] ?: "unknown")
        }

        fun failedNow(
            reason: String,
            status: String = BenchContract.STATUS_FAILED,
            recheck: MemRecheckEvidence? = null,
            stage: String? = null
        ): BenchResult {
            diagNote(MemDiag.Checkpoint.FAILURE_HANDLED)
            return BenchResult(
                runId = runId, device = snapshot,
                model = BenchModelIdentity(displayName, modelBytes, modelSha256,
                    "unknown", emptyMap()),
                runtimeVersion = llamaVersionOrUnknown(),
                runtimeAbi = device.supportedAbis.firstOrNull() ?: "unknown",
                config = config, workload = BenchConfig.workload(),
                measurements = zeroMeasurements(thermalBefore, null, thermalBefore,
                    pssBefore, pssBefore),
                status = status, error = reason,
                appVersion = BuildConfig.VERSION_NAME, collectedAtUtc = collectedAt,
                xNativeBuild = nativeBuildMap(),
                xMemRecheck = recheck,
                xMemDiag = diagExport(),
                xMemConditions = memConditions,
                xFailure = FailureEvidence(
                    stage ?: execState, nativeStarted)
            )
        }

        val configErrors = BenchConfig.validate(config)
        if (configErrors.isNotEmpty()) return failedNow(configErrors.joinToString("; "))
        if (!LlamaBridge.available) {
            return failedNow("runtime unavailable: ${LlamaBridge.loadError}")
        }
        if (!modelFile.isFile) return failedNow("model file missing")
        diagNote(MemDiag.Checkpoint.PRE_ADMISSION)
        val guardErrors = BenchGuards.preflight(
            device.supportedAbis, memInfo.availMem, modelBytes, thermalBefore)
        // EF-MEM-03: memory never refuses (see
        // BenchGuards) — no recheck gate exists anymore.
        // Remaining guard errors (ABI/model/thermal)
        // refuse immediately with measured detail.
        val recheck: MemRecheckEvidence? = null
        if (guardErrors.isNotEmpty()) {
            val detail = BenchGuards.refusalDetail(
                memInfo.availMem, modelBytes)
            return failedNow(
                "${guardErrors.joinToString("; ")} ($detail)")
        }

        val wall0 = SystemClock.elapsedRealtime()
        wallStartMs = wall0
        liveThermal = thermalBefore
        livePssKb = pssBefore
        emitLive()
        val allocBefore = LlamaBridge.nativeAllocBytes()
        val watchdog = Thread({
            var sampled = false
            var tick = 0
            while (!finished.get()) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                tick++
                val elapsed = SystemClock.elapsedRealtime() - wall0
                if (!sampled && elapsed >= 2000) {
                    midThermal = sampleThermal()
                    sampled = true
                }
                // Live thermal/PSS at ~0.5 Hz; mailbox progress at ~4 Hz.
                if (tick % 8 == 0) {
                    liveThermal = sampleThermal()
                    livePssKb = processPssKb()
                }
                emitLive()
                if (elapsed >= config.timeoutMs) {
                    timedOut.set(true)
                    cancel()
                    return@Thread
                }
            }
        }, "edgefit-bench-watchdog")
        watchdog.isDaemon = true
        watchdog.start()

        var native: JSONObject? = null
        var nativeError: String? = null
        try {
            // Single funnel: native receives exactly the validated
            // config's args, in JNI order; the recorded result keeps
            // the same instance's values (see nativeArgs tests).
            val args = BenchConfig.nativeArgs(config)
            diagNote(MemDiag.Checkpoint.PRE_NATIVE)
            nativeStarted = true
            val raw = LlamaBridge.nativeRun(
                modelFile.absolutePath, BenchContract.WORKLOAD_PROMPT,
                args[0], args[1], args[2], box, prog)
            native = JSONObject(raw)
        } catch (e: Exception) {
            nativeError = "native call failed: ${e.message}"
        } finally {
            finished.set(true)
            try {
                watchdog.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        // Native freed model/context/sampler before
        // returning: this single point covers inference
        // completion AND native cleanup (K+L merged).
        diagNote(MemDiag.Checkpoint.POST_NATIVE)
        // Last genuine stage before FINALIZING: failure
        // evidence must name where work stopped, not the
        // teardown label.
        val nativeStage = execState
        memConditions = memEnd(memConditions)
        if (BenchExecutionState.can(execState, BenchExecutionState.FINALIZING)) {
            execState = BenchExecutionState.FINALIZING
        }
        val wallMs = SystemClock.elapsedRealtime() - wall0
        val allocAfter = LlamaBridge.nativeAllocBytes()
        val pssAfter = processPssKb()
        val thermalAfter = sampleThermal()

        if (nativeError != null) {
            return failedNow(nativeError, stage = nativeStage)
        }
        val out = native!!
        if (!out.optBoolean("ok", false)) {
            return failedNow(out.optString("error", "native error"),
                stage = nativeStage)
        }
        val cancelled = out.optBoolean("cancelled", false)
        val prefillMs = out.optLong("prefill_ms", 0)
        val genMs = out.optLong("gen_ms", 0)
        val generated = out.optInt("generated_tokens", 0)
        val prefillTokens = out.optInt("prefill_tokens", 0)
        val loadMs = (wallMs - prefillMs - genMs).coerceAtLeast(0)
        val metaJson = out.optJSONObject("meta")
        val meta = mutableMapOf<String, String>()
        if (metaJson != null) {
            val keys = metaJson.keys()
            while (keys.hasNext() && meta.size < 32) {
                val key = keys.next()
                meta[key] = metaJson.optString(key, "")
            }
        }
        val mem = benchMemory(pssBefore, pssAfter, allocBefore, allocAfter)
        val mid = midThermal ?: thermalBefore
        val thermal = BenchThermal(
            before = thermalBefore ?: "unknown",
            mid = mid ?: "unknown",
            after = thermalAfter ?: "unknown",
            available = thermalBefore != null && thermalAfter != null)
        val measurements = BenchMeasurements(
            loadMs = loadMs, prefillMs = prefillMs, prefillTokens = prefillTokens,
            genMs = genMs, generatedTokens = generated,
            tpsGen = BenchConfig.tps(generated, genMs),
            tpsPrefill = BenchConfig.tps(prefillTokens, prefillMs),
            eosStop = out.optBoolean("eos_stop", false), mem = mem, thermal = thermal)
        val (status, error) = when {
            cancelled && timedOut.get() ->
                BenchContract.STATUS_TIMEOUT to "watchdog timeout after ${config.timeoutMs} ms"
            cancelled -> BenchContract.STATUS_CANCELLED to "cancelled by user or lifecycle"
            else -> BenchContract.STATUS_COMPLETED to null
        }
        return BenchResult(
            runId = runId, device = snapshot,
            model = BenchModelIdentity(displayName, modelBytes, modelSha256,
                meta["general.quantization_version"] ?: "unknown", meta),
            runtimeVersion = llamaVersionOrUnknown(),
            runtimeAbi = device.supportedAbis.firstOrNull() ?: "unknown",
            config = config, workload = BenchConfig.workload(),
            measurements = measurements, status = status, error = error,
            appVersion = BuildConfig.VERSION_NAME, collectedAtUtc = collectedAt,
            xNativeBuild = nativeBuildMap(),
            xMemRecheck = recheck,
            xMemDiag = diagExport(),
            xMemConditions = memConditions,
            xFailure = if (status == BenchContract.STATUS_COMPLETED) {
                null
            } else {
                FailureEvidence(nativeStage, nativeStarted)
            })
    }

    /**
     * EF-MEM-03 cheap end-of-run re-read: one
     * getMemoryInfo, outside all timing windows.
     */
    private fun memEnd(start: MemConditions): MemConditions {
        val info = try {
            ActivityManager.MemoryInfo().also {
                (context.getSystemService(Context.ACTIVITY_SERVICE)
                    as ActivityManager).getMemoryInfo(it)
            }
        } catch (_: Exception) {
            return start
        }
        val avail = info.availMem.takeIf { it >= 0 }
        val total = info.totalMem.takeIf { it > 0 }
        val pct = if (avail != null && total != null &&
            avail <= total
        ) {
            100.0 * avail / total
        } else {
            null
        }
        return start.copy(availBytesEnd = avail, availPctEnd = pct)
    }

    // EF-MEM-03: the MEM-01 admission-recheck path is
    // retired with the blocking gate (memory never
    // refuses, so nothing rechecks). [MemRecheck] itself
    // stays for reading historical refusal evidence.

    private fun zeroMeasurements(
        before: String?, mid: String?, after: String?, pssBefore: Int, pssAfter: Int
    ): BenchMeasurements = BenchMeasurements(
        loadMs = 0, prefillMs = 0, prefillTokens = 0, genMs = 0, generatedTokens = 0,
        tpsGen = 0.0, tpsPrefill = 0.0, eosStop = false,
        mem = benchMemory(pssBefore, pssAfter, -1, -1),
        thermal = BenchThermal(before ?: "unknown", mid ?: "unknown",
            after ?: "unknown", before != null && after != null))

    private fun benchMemory(pssBefore: Int, pssAfter: Int, allocBefore: Long,
                            allocAfter: Long): BenchMemory {
        val ok = allocBefore >= 0 && allocAfter >= 0
        return BenchMemory(pssBefore, pssAfter,
            if (ok) allocAfter - allocBefore else 0,
            if (ok) MEM_METHOD else "$MEM_METHOD;alloc_unavailable")
    }

    private fun processPssKb(): Int {
        return try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss
        } catch (_: Exception) {
            0
        }
    }

    private fun sampleThermal(): String? {
        return try {
            if (Build.VERSION.SDK_INT < 29) return null
            val power = context.getSystemService(PowerManager::class.java)
            ProfileContract.thermalName(power.currentThermalStatus)
        } catch (_: Exception) {
            null
        }
    }

    private fun llamaVersionOrUnknown(): String {
        return try {
            if (LlamaBridge.available) LlamaBridge.nativeVersion() else "unavailable"
        } catch (_: Exception) {
            "unavailable"
        }
    }

    private fun utcNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private fun utcStamp(): String {
        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private fun nativeBuildMap(): Map<String, String> = mapOf(
        "variant" to BuildConfig.BUILD_TYPE,
        "native_opt" to BuildConfig.NATIVE_OPT
    )

    companion object {
        const val MEM_METHOD = "debug-memoryinfo+mallinfo2"

        fun formatRunId(stamp: String, randHex8: String): String =
            "BENCH-$stamp-$randHex8"

        private val random = SecureRandom()

        fun randomHex8(): String {
            val bytes = ByteArray(4)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
