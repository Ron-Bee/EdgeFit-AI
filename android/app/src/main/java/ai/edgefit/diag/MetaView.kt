package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * BYOK interface: key custody, per-action authorization, and
 * prediction/follow-up display. Secrets never appear in any text
 * builder output — at most a last-4 suffix renders. Main thread only;
 * text builders are pure and unit-tested. Presented in the Aero
 * dialog theme; key handling and authorization semantics unchanged.
 */
object MetaView {

    fun keyStatusLine(
        hasSession: Boolean,
        sessionSuffix: String?,
        hasPersisted: Boolean
    ): String = when {
        hasSession -> "Meta API key: session-only key set " +
            "(${sessionSuffix ?: "…????"}). Dies with the process."
        hasPersisted -> "Meta API key: securely saved key on file. " +
            "It decrypts only when you authorize a request."
        else -> "Meta API key: none configured."
    }

    fun authorizeText(
        action: String,
        fields: List<String>,
        keyNote: String
    ): String = (listOf(
        "Authorize this Meta request?",
        "",
        "Action: $action",
        "Model: ${MetaApi.MODEL}",
        "Endpoint: ${MetaApi.ENDPOINT} (HTTPS only)",
        "Credential: $keyNote",
        "This sends the fields below to Meta for inference. Provider " +
            "charges or quota consumption may apply; EdgeFit cannot " +
            "state an exact cost.",
        "",
        "Fields sent:"
    ) + fields.map { "- $it" } + listOf(
        "",
        "Nothing else on this device is transmitted. No retries, no " +
            "background polling."
    )).joinToString("\n")

    @Suppress("UNCHECKED_CAST")
    fun predictionText(record: SessionRecord): String {
        val prediction = record.prediction as? Map<String, Any?>
            ?: return "No prediction recorded."
        val lines = mutableListOf(
            "Live Meta prediction (${record.sessionId})",
            "gen: ${prediction["predicted_gen_tps"]} tok/s",
            "prefill: ${prediction["predicted_prefill_tps"]} tok/s",
            "source: ${prediction["source_type"]}",
            "recorded: ${prediction["created_at_utc"]}"
        )
        val raw = prediction["raw_json"] as? String
        val full = raw?.let {
            try {
                SessionJson.parse(it) as? Map<String, Any?>
            } catch (_: Exception) {
                null
            }
        }
        if (full != null) {
            lines.add("confidence: ${full["confidence"]}")
            lines.add("rationale: ${full["rationale"]}")
            (full["uncertainty_notes"] as? List<*>)?.let {
                lines.add("uncertainty: ${it.joinToString("; ")}")
            }
            (full["limits"] as? List<*>)?.let {
                lines.add("limits: ${it.joinToString("; ")}")
            }
        }
        return lines.joinToString("\n")
    }

    @Suppress("UNCHECKED_CAST")
    fun followUpText(record: SessionRecord): String {
        val followup = record.followup as? Map<String, Any?>
            ?: return "No follow-up recorded."
        val recommendation = followup["recommendation"] as? Map<String, Any?>
            ?: return "Follow-up stored without assessment."
        val lines = mutableListOf(
            "Meta follow-up (${record.sessionId})",
            "direction: ${recommendation["direction"]}",
            "supports_conclusion: " +
                "${recommendation["supports_conclusion"]}",
            "summary: ${recommendation["summary"]}"
        )
        (recommendation["limitations"] as? List<*>)?.let {
            lines.add("limitations: ${it.joinToString("; ")}")
        }
        val experiment = recommendation["next_experiment"] as? Map<String, Any?>
        if (experiment == null) {
            lines.add("next_experiment: none proposed")
        } else {
            lines.add("next_experiment: ${experiment["description"]}")
            lines.add("proposal_valid: " +
                "${recommendation["proposal_valid"]}" +
                (recommendation["proposal_rejection"]?.let { " ($it)" } ?: ""))
        }
        lines.add("provenance: ${followup["provenance"]}")
        return lines.joinToString("\n")
    }

    /**
     * Key settings + prediction entry. Callbacks run on the UI thread;
     * long work (network, seal) must leave the thread in the caller.
     */
    fun showKeyDialog(
        activity: Activity,
        status: () -> String,
        onSave: (String, Boolean) -> String,
        onForget: () -> String,
        onPredict: () -> Unit,
        // Lifecycle persistence: an in-memory key draft (never
        // persisted) plus dismiss tracking. Optional;
        // existing callers unaffected.
        initialKey: String? = null,
        onKeyChanged: ((String) -> Unit)? = null,
        initialRemember: Boolean = false,
        onRememberChanged: ((Boolean) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val themed = ContextThemeWrapper(
            activity, R.style.Theme_EdgeFit_Aero_Dialog)
        var dialog: AlertDialog? = null
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val ink = AeroUi.inkColor(themed)
        val secondary = AeroUi.secondaryColor(themed)
        val statusView = TextView(themed).apply {
            text = status()
            textSize = 14f
            setTextColor(ink)
        }
        val notice = TextView(themed).apply {
            textSize = 14f
            setTextColor(ink)
        }
        val keyInput = EditText(themed).apply {
            hint = "Paste Meta API key"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSingleLine = true
            setTextColor(ink)
            setHintTextColor(secondary)
            if (!initialKey.isNullOrEmpty()) setText(initialKey)
            if (onKeyChanged != null) {
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?, start: Int, count: Int, after: Int
                    ) = Unit
                    override fun onTextChanged(
                        s: CharSequence?, start: Int, before: Int, count: Int
                    ) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        onKeyChanged(s?.toString().orEmpty())
                    }
                })
            }
        }
        val remember = CheckBox(themed).apply {
            text = "Remember securely on this device (Keystore-encrypted)"
            setTextColor(ink)
            isChecked = initialRemember
            if (onRememberChanged != null) {
                setOnCheckedChangeListener { _, checked ->
                    onRememberChanged(checked)
                }
            }
        }
        val save = Button(themed).apply {
            text = "Save Key"
            AeroUi.stylePrimary(themed, this)
            setOnClickListener {
                val key = keyInput.text.toString()
                keyInput.setText("")
                notice.text = onSave(key, remember.isChecked)
                remember.isChecked = false
                statusView.text = status()
            }
        }
        val forget = Button(themed).apply {
            AeroUi.styleSecondary(themed, this)
            text = "Forget Key"
            setTextColor(themed.getColor(R.color.aero_error))
            setOnClickListener {
                notice.text = onForget()
                statusView.text = status()
            }
        }
        val predict = Button(themed).apply {
            text = "Predict with Meta"
            AeroUi.stylePrimary(themed, this)
            setOnClickListener {
                dialog?.dismiss()
                onPredict()
            }
        }
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(statusView)
            addView(TextView(themed).apply {
                text = "Session-only by default: the key lives in memory " +
                    "and dies with the app. Saving is explicit and " +
                    "encrypted. The key is sent to Meta only to " +
                    "authenticate requests you confirm below — never to " +
                    "EdgeFit, never in exports or logs."
                textSize = 13f
                setTextColor(secondary)
            })
            addView(keyInput)
            addView(remember)
            addView(save)
            addView(forget)
            addView(notice)
            addView(TextView(themed).apply {
                text = "Prediction uses the staged model and current " +
                    "benchmark config. Stage a model first."
                textSize = 13f
                setTextColor(secondary)
            })
            addView(predict)
        }
        dialog = AlertDialog.Builder(themed)
            .setTitle("Meta API Settings")
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
        if (onDismiss != null) {
            dialog?.setOnDismissListener { onDismiss() }
        }
    }

    /** Per-action authorization gate. Confirm leaves via [onConfirm]. */
    fun showAuthorizeDialog(
        activity: Activity,
        text: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
        onDismiss: (() -> Unit)? = null
    ) {
        val themed = ContextThemeWrapper(
            activity, R.style.Theme_EdgeFit_Aero_Dialog)
        val body = TextView(themed).apply {
            this.text = text
            textSize = 14f
            setTextColor(AeroUi.inkColor(themed))
            val pad = (16 * resources.displayMetrics.density + 0.5f).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val authDialog = AlertDialog.Builder(themed)
            .setTitle("Authorize Meta Request")
            .setView(ScrollView(themed).apply { addView(body) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .show()
        if (onDismiss != null) {
            authDialog.setOnDismissListener { onDismiss() }
        }
    }

    fun showResultDialog(
        activity: Activity,
        title: String,
        text: String,
        onDismiss: (() -> Unit)? = null
    ) {
        val themed = ContextThemeWrapper(
            activity, R.style.Theme_EdgeFit_Aero_Dialog)
        val body = TextView(themed).apply {
            this.text = text
            textSize = 14f
            setTextColor(AeroUi.inkColor(themed))
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density + 0.5f).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val resultDialog = AlertDialog.Builder(themed)
            .setTitle(title)
            .setView(ScrollView(themed).apply { addView(body) })
            .setPositiveButton("Close", null)
            .show()
        if (onDismiss != null) {
            resultDialog.setOnDismissListener { onDismiss() }
        }
    }
}
