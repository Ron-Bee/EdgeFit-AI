package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView

/**
 * Read-only Standard Benchmark Workload view. The displayed prompt is
 * [BenchContract.WORKLOAD_PROMPT] itself — the same constant passed to
 * native inference — so display and execution cannot diverge. The SHA
 * is computed at display time, never hardcoded.
 */
object BenchWorkloadView {

    /** Pure text builder; every line is unit-tested. */
    fun buildText(
        config: BenchConfigData,
        modelLabel: String?,
        prefillTokens: Int?
    ): String {
        val prompt = BenchContract.WORKLOAD_PROMPT
        val lines = mutableListOf(
            "Standard Benchmark Workload",
            "",
            "Prompt (exact text sent to the model):",
            prompt,
            "",
            "Workload ID: ${BenchContract.WORKLOAD_ID}",
            "Prompt SHA-256: ${BenchConfig.sha256Hex(prompt.toByteArray(Charsets.UTF_8))}",
            "Prompt characters: ${prompt.length}",
            "Output-token limit: ${config.nPredict}",
            "Context size: ${config.nCtx}",
            "Sampling: ${config.sampler}, temperature ${config.temperature}",
            "Seed: ${config.seed}",
            "Model: ${modelLabel ?: "not staged yet"}",
            "Prompt tokens: ${prefillTokens?.toString() ?: "measured at runtime"}"
        )
        lines.add("")
        lines.add(
            "Note: the same text may tokenize into different token " +
                "counts on different models and tokenizers; the measured " +
                "prompt-token count is authoritative for each run."
        )
        return lines.joinToString("\n")
    }

    /** Shows the workload in a read-only dialog. Main thread only. */
    fun show(
        activity: Activity,
        config: BenchConfigData,
        modelLabel: String?,
        prefillTokens: Int?
    ) {
        val text = TextView(activity).apply {
            this.text = buildText(config, modelLabel, prefillTokens)
            textSize = 14f
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density + 0.5f).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroller = ScrollView(activity).apply { addView(text) }
        AlertDialog.Builder(activity)
            .setTitle("Standard Benchmark Workload")
            .setView(scroller)
            .setPositiveButton("Close", null)
            .show()
    }
}
