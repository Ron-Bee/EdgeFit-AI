package ai.edgefit.diag

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Dedicated dark benchmark execution screen. Every number shown comes
 * from live sampler updates or the measured [BenchResult]; nothing is
 * simulated. All methods must be called on the main thread.
 */
class BenchScreenView(context: Context) : LinearLayout(context) {

    var onCancel: (() -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onWorkload: (() -> Unit)? = null

    private val samples = BenchTpsSamples()
    private var lastState: String = BenchExecutionState.IDLE
    private var targetTokens: Int = 0

    private val stageView: TextView
    private val modelView: TextView
    private val tokensView: TextView
    private val bar: ProgressBar
    private val tpsView: TextView
    private val elapsedView: TextView
    private val vitalsView: TextView
    private val chart: TpsChartView
    private val resultView: TextView
    private val cancelButton: Button
    private val saveButton: Button
    private val workloadButton: Button
    private val backButton: Button
    private var resultRowRef: LinearLayout? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF101216.toInt())
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        fun label(sizeSp: Float, color: Int): TextView =
            TextView(context).apply {
                textSize = sizeSp
                setTextColor(color)
            }

        stageView = label(20f, TEXT).apply {
            text = "Benchmark"
            contentDescription = "Benchmark stage"
        }
        modelView = label(13f, SECONDARY)
        tokensView = label(34f, TEXT).apply {
            contentDescription = "Generated tokens"
        }
        bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
            .apply { max = 1000 }
        tpsView = label(16f, ACCENT)
        elapsedView = label(16f, TEXT)
        vitalsView = label(13f, SECONDARY)
        chart = TpsChartView(context).apply {
            contentDescription = "Live generation throughput chart (provisional)"
        }
        resultView = label(14f, TEXT).apply { visibility = GONE }
        cancelButton = Button(context).apply {
            text = "Cancel"
            minimumHeight = dp(48)
            setOnClickListener { onCancel?.invoke() }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
        }
        saveButton = Button(context).apply {
            text = "Save"
            minimumHeight = dp(48)
            setOnClickListener { onSave?.invoke() }
        }
        workloadButton = Button(context).apply {
            text = "Workload"
            minimumHeight = dp(48)
            setOnClickListener { onWorkload?.invoke() }
        }
        backButton = Button(context).apply {
            text = "Back"
            minimumHeight = dp(48)
            setOnClickListener { onBack?.invoke() }
        }
        row.addView(saveButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(workloadButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(backButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(stageView)
            addView(modelView)
            addView(Space(context, dp(12)))
            addView(tokensView)
            addView(bar, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT))
            addView(tpsView)
            addView(elapsedView)
            addView(Space(context, dp(8)))
            addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, dp(140)))
            addView(Space(context, dp(8)))
            addView(vitalsView)
            addView(Space(context, dp(8)))
            addView(resultView)
            addView(Space(context, dp(8)))
            addView(cancelButton, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT))
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT))
        }
        val scroller = ScrollView(context).apply { addView(column) }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT))
        resultRowRef = row
    }

    /** Run header from the staged model and fixed config. No paths shown. */
    fun showRun(
        displayName: String,
        sizeBytes: Long,
        sha256: String,
        config: BenchConfigData,
        runtimeVersion: String,
        runtimeAbi: String
    ) {
        samples.clear()
        lastState = BenchExecutionState.IDLE
        targetTokens = config.nPredict
        resultView.visibility = GONE
        resultRowRef?.visibility = GONE
        cancelButton.visibility = VISIBLE
        cancelButton.isEnabled = true
        modelView.text = "%s (%d MB)\nSHA %s · ctx %d · %d threads · %d tokens · %s/%s".format(
            displayName, sizeBytes / 1048576, sha256.take(12), config.nCtx,
            config.nThreads, config.nPredict, runtimeVersion, runtimeAbi
        )
        stageView.text = "Benchmark: starting…"
        tokensView.text = "0 / %d".format(targetTokens)
        bar.progress = 0
        tpsView.text = "— tok/s (provisional)"
        elapsedView.text = "Elapsed 00:00"
        vitalsView.text = "Thermal: … · Memory: …"
        chart.setData(emptyList(), targetTokens)
    }

    /** One sampler update. Provisional values are labeled as such. */
    fun render(update: BenchLiveUpdate) {
        val snap = update.progress
        lastState = update.execState
        if (snap.valid && snap.targetTokens > 0) {
            targetTokens = snap.targetTokens
        }
        stageView.text = "Benchmark: " + BenchUiText.stageLabel(update.execState)
        val generated = snap.generatedTokens
        tokensView.text = "%d / %d".format(generated, targetTokens)
        bar.max = 1000
        bar.progress = if (targetTokens > 0) {
            (generated * 1000 / targetTokens).coerceIn(0, 1000)
        } else {
            0
        }
        val tps = BenchProgressMath.provisionalTps(generated, update.elapsedMs)
        tpsView.text = if (update.execState == BenchExecutionState.GENERATING &&
            generated > 0
        ) {
            BenchUiText.tpsLabel(tps) + " (provisional)"
        } else {
            "— tok/s (provisional)"
        }
        elapsedView.text = "Elapsed " + BenchUiText.elapsedLabel(update.elapsedMs)
        val thermal = update.thermal ?: "unavailable"
        val mem = if (update.pssKb >= 0) "%d MB".format(update.pssKb / 1024)
            else "unavailable"
        vitalsView.text = "Thermal: %s · Memory (PSS): %s".format(thermal, mem)
        if (update.execState == BenchExecutionState.GENERATING) {
            samples.add(update.elapsedMs, generated)
            chart.setData(samples.snapshot(), targetTokens)
        }
        contentDescription = "Benchmark %s, %d of %d tokens".format(
            BenchUiText.stageLabel(update.execState), generated, targetTokens
        )
    }

    /** Terminal transition. Only measured values; partial is never shown
     * as completed. */
    fun showResult(result: BenchResult) {
        cancelButton.visibility = GONE
        resultRowRef?.visibility = VISIBLE
        val measurements = result.measurements
        stageView.text = "Benchmark: " +
            BenchUiText.stageLabel(result.status)
        tokensView.text = "%d / %d".format(
            measurements.generatedTokens, result.config.nPredict)
        bar.progress = if (result.status == BenchContract.STATUS_COMPLETED) {
            1000
        } else {
            (measurements.generatedTokens * 1000 /
                result.config.nPredict.coerceAtLeast(1)).coerceIn(0, 1000)
        }
        tpsView.text = "Measured " +
            BenchUiText.tpsLabel(measurements.tpsGen)
        val lines = mutableListOf(
            "Status: ${result.status}",
            "Load: ${measurements.loadMs} ms",
            "Prefill: ${measurements.prefillTokens} tokens in " +
                "${measurements.prefillMs} ms " +
                "(${BenchUiText.tpsLabel(measurements.tpsPrefill)})",
            "Generation: ${measurements.generatedTokens} tokens in " +
                "${measurements.genMs} ms " +
                "(${BenchUiText.tpsLabel(measurements.tpsGen)})",
            "Thermal: ${measurements.thermal.before} / " +
                "${measurements.thermal.mid} / ${measurements.thermal.after}"
        )
        if (result.error != null) {
            lines.add(BenchUiText.safeError(result.error))
        }
        resultView.text = lines.joinToString("\n")
        resultView.visibility = VISIBLE
    }

    fun setRunning(running: Boolean) {
        cancelButton.isEnabled = running
    }

    /** Minimal tokens-vs-time plot from sampler data only. */
    private class TpsChartView(context: Context) : View(context) {
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SECONDARY
            strokeWidth = 2f
            textSize = 28f
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            style = Paint.Style.FILL
        }
        private var points: List<BenchTpsSamples.Sample> = emptyList()
        private var target: Int = 0

        fun setData(data: List<BenchTpsSamples.Sample>, targetTokens: Int) {
            points = data
            target = targetTokens
            contentDescription = if (data.isEmpty()) {
                "Throughput chart: no samples yet"
            } else {
                "Throughput chart: %d samples, %d tokens".format(
                    data.size, data.last().generated)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            if (width <= 0 || height <= 0) return
            val pad = 64f
            canvas.drawLine(pad, height - pad, width - 16f, height - pad, axis)
            canvas.drawLine(pad, 16f, pad, height - pad, axis)
            if (points.size < 2) {
                canvas.drawText("awaiting samples", pad + 8f, height / 2f, axis)
                return
            }
            val maxT = points.last().elapsedMs.coerceAtLeast(1).toFloat()
            val maxN = target.coerceAtLeast(1).toFloat()
            var prevX = 0f
            var prevY = 0f
            var first = true
            for (sample in points) {
                val x = pad + (width - pad - 16f) *
                    (sample.elapsedMs / maxT)
                val y = (height - pad) - (height - pad - 16f) *
                    (sample.generated / maxN)
                if (!first) canvas.drawLine(prevX, prevY, x, y, line)
                prevX = x
                prevY = y
                first = false
            }
            canvas.drawCircle(prevX, prevY, 8f, dot)
            canvas.drawText("%d tok".format(points.last().generated),
                pad + 8f, 44f, axis)
        }
    }

    private class Space(context: Context, private val heightPx: Int) :
        View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec), heightPx)
        }
    }

    companion object {
        private const val TEXT = 0xFFEDEFF2.toInt()
        private const val SECONDARY = 0xFFA7B0BC.toInt()
        private const val ACCENT = 0xFF82B4FF.toInt()
    }
}
