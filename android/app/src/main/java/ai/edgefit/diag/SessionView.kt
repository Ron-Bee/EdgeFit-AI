package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal local-sessions dialog: list stored sessions, bind one
 * authorized session to the next run, delete sessions, and create
 * offline-test sessions with hand-entered predictions. The offline
 * form exists so the attach path is phone-testable before EF-BYOK-01;
 * its records are labeled offline_test, never live Meta predictions.
 * Main thread only; text builders are pure and unit-tested.
 */
object SessionView {

    fun parsePredicted(text: String): Double? {
        val value = text.trim().toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0) return null
        return value
    }

    /**
     * Predict-gate (AUD-01): a byok session with no recorded
     * prediction may request one while SESSION_CREATED (agent
     * experiment shells start here) or PREDICTION_PENDING (retry
     * after a failed attempt). Recorded, authorized, compared, and
     * non-byok sessions are never offered a prediction. Pure for
     * unit tests; the activity transitions CREATED exactly once on
     * confirm and commits durably before any benchmark can bind.
     */
    fun canPredict(record: SessionRecord): Boolean =
        record.mode == "byok" &&
            (record.state == SessionState.SESSION_CREATED ||
                record.state == SessionState.PREDICTION_PENDING) &&
            record.prediction == null

    @Suppress("UNCHECKED_CAST")
    fun buildSummary(record: SessionRecord): String {
        val lines = mutableListOf(
            "${record.sessionId}  ${record.state}",
            "mode=${record.mode} model=${record.modelDisplay}"
        )
        val prediction = record.prediction as? Map<String, Any?>
        if (prediction == null) {
            lines.add("prediction: not recorded")
        } else {
            lines.add("prediction: ${prediction["predicted_gen_tps"]} tok/s " +
                "(gen), ${prediction["predicted_prefill_tps"]} tok/s " +
                "(prefill) [${prediction["source_type"]}]")
        }
        val benchmark = record.benchmark as? Map<String, Any?>
        if (benchmark == null) {
            lines.add("benchmark: not attached")
        } else {
            lines.add("benchmark: ${benchmark["run_id"]} ${benchmark["status"]}")
        }
        val comparison = record.comparison as? Map<String, Any?>
        if (comparison != null) {
            lines.add(comparisonLine(comparison))
        }
        if (record.recoveryNotes.isNotEmpty()) {
            lines.add("recovery: ${record.recoveryNotes.joinToString("; ")}")
        }
        return lines.joinToString("\n")
    }

    @Suppress("UNCHECKED_CAST")
    fun comparisonLine(comparison: Map<String, Any?>): String {
        if (comparison["comparable"] != true) {
            return "comparison: noncomparable (${comparison["reason"]})"
        }
        // Values arrive pre-rounded; toString keeps them short and exact.
        val gen = (comparison["gen_signed"] as? Number)?.toDouble()
        val pct = (comparison["gen_pct"] as? Number)?.toDouble()
        return "comparison: gen ${signed(gen)} tok/s (${signed(pct)}%)"
    }

    private fun signed(value: Double?): String {
        if (value == null || !value.isFinite()) return "?"
        val text = if (value == 0.0) "0.0" else value.toString()
        return if (text.startsWith("-")) text else "+$text"
    }

    fun buildActiveLine(activeId: String?, record: SessionRecord?): String {
        if (activeId == null || record == null) {
            return "Session: none bound (standalone physical measurement)"
        }
        return "Session: ${record.sessionId} ${record.state}"
    }

    /**
     * @param load fresh session list, newest first.
     * @param activeId currently bound session, or null.
     * @param onCreate creates + authorizes an offline-test session.
     * @param onBind binds an authorized session to the next run.
     * @param onDelete deletes a session. Each returns a status message.
     * @param onPredict retries a live prediction on a PENDING byok
     * session; @param onFollowUp starts follow-up on a compared byok
     * session. Both dismiss this dialog first (they open their own).
     */
    fun show(
        activity: Activity,
        load: () -> List<SessionRecord>,
        activeId: () -> String?,
        onCreate: (Double, Double) -> String,
        onBind: (String) -> String,
        onDelete: (String) -> String,
        onPredict: ((String) -> Unit)? = null,
        onFollowUp: ((String) -> Unit)? = null
    ) {
        var dialog: AlertDialog? = null
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val notice = TextView(activity).apply { textSize = 14f }
        fun refresh() {
            list.removeAllViews()
            val bound = activeId()
            val sessions = try {
                load()
            } catch (e: Exception) {
                notice.text = "Session list failed: ${e.message}"
                return
            }
            if (sessions.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = "No local sessions yet."
                    textSize = 14f
                })
            }
            for (record in sessions) {
                val row = TextView(activity).apply {
                    text = (if (record.sessionId == bound) "[BOUND]\n" else "") +
                        buildSummary(record)
                    textSize = 13f
                    setTextIsSelectable(true)
                    setPadding(0, dp(4), 0, dp(2))
                }
                list.addView(row)
                val buttons = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                fun addButton(label: String, action: () -> Unit): Button {
                    val button = Button(activity).apply {
                        text = label
                        setOnClickListener { action() }
                    }
                    buttons.addView(button, LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    return button
                }
                addButton("Bind") {
                    notice.text = onBind(record.sessionId)
                    refresh()
                }.isEnabled = record.state ==
                    SessionState.BENCHMARK_AUTHORIZED &&
                    record.sessionId != bound
                if (onPredict != null && canPredict(record)) {
                    addButton("Predict") {
                        dialog?.dismiss()
                        onPredict(record.sessionId)
                    }
                }
                if (onFollowUp != null && record.mode == "byok" &&
                    record.followup == null &&
                    (record.state == SessionState.COMPARISON_READY ||
                        record.state == SessionState.FOLLOWUP_PENDING)
                ) {
                    addButton("Follow-Up") {
                        dialog?.dismiss()
                        onFollowUp(record.sessionId)
                    }
                }
                addButton("Delete") {
                    notice.text = onDelete(record.sessionId)
                    refresh()
                }
                list.addView(buttons)
            }
        }
        val genInput = EditText(activity).apply {
            hint = "Predicted gen tok/s (e.g. 15.0)"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val prefillInput = EditText(activity).apply {
            hint = "Predicted prefill tok/s (e.g. 20.0)"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val create = Button(activity).apply {
            text = "New Offline Test Session"
            setOnClickListener {
                val gen = parsePredicted(genInput.text.toString())
                val prefill = parsePredicted(prefillInput.text.toString())
                if (gen == null || prefill == null) {
                    notice.text = "Enter finite predicted rates above zero."
                    return@setOnClickListener
                }
                notice.text = onCreate(gen, prefill)
                refresh()
            }
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(TextView(activity).apply {
                text = "Offline test only — not a Meta prediction. " +
                    "Stage a model first; the session binds its hash and " +
                    "the current benchmark config."
                textSize = 13f
            })
            addView(genInput)
            addView(prefillInput)
            addView(create)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(notice)
            addView(list)
            addView(form)
        }
        refresh()
        dialog = AlertDialog.Builder(activity)
            .setTitle("Local Sessions")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
    }
}
