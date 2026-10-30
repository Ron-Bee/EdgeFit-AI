package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * EF-UX-02 local-sessions browser: newest-first accordion rows
 * (tap to expand), Latest Session highlight, filter, fixed
 * creation action, bind/predict/follow-up/repeat/compare/
 * details/delete per record. The offline creation panel exists
 * so the attach path stays phone-testable; its records are
 * labeled offline_test, never live Meta predictions. Main
 * thread only; list logic lives in [SessionListUi] (pure,
 * unit-tested) and gating helpers below stay pure.
 */
object SessionView {

    /**
     * EF-SESSION-SCALE-01 page size. Collapsed rows are
     * cheap but not free; rendering stays bounded while
     * the full history remains one tap away. Filtering
     * and Latest always consider every record — only
     * view inflation is windowed.
     */
    const val PAGE_SIZE = 100

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

    /**
     * EF-FOLLOWUP-02 standalone-repeat gate: a completed,
     * predicted, non-agent-family session. Agent-family
     * repeats go through the proposal flow (budget-charged),
     * never through this button.
     */
    @Suppress("UNCHECKED_CAST")
    fun canRepeatStandalone(record: SessionRecord): Boolean {
        if (record.mode == "agent") return false
        if (record.parentSessionId != null) return false
        if (record.prediction == null) return false
        val bench = record.benchmark as? Map<String, Any?>
            ?: return false
        if (bench["status"] != BenchContract.STATUS_COMPLETED) {
            return false
        }
        val runId = bench["run_id"] as? String ?: return false
        return SessionStore.isValidRunId(runId)
    }

    /**
     * EF-MEM-03: a predicted session whose run never
     * completed can open the recovery assistant. Agent
     * parents recover through their experiments, not
     * directly — but experiment children qualify (the
     * assistant routes them to Agent Recovery).
     */
    @Suppress("UNCHECKED_CAST")
    fun canRecover(record: SessionRecord): Boolean {
        if (record.mode == "agent") return false
        if (record.prediction == null) return false
        val bench = record.benchmark as? Map<String, Any?>
            ?: return false
        val status = bench["status"] as? String
        return status == BenchContract.STATUS_FAILED ||
            status == BenchContract.STATUS_TIMEOUT ||
            status == BenchContract.STATUS_CANCELLED
    }

    /** EF-FOLLOWUP-02: any completed run can show its series. */
    @Suppress("UNCHECKED_CAST")
    fun canCompare(record: SessionRecord): Boolean {
        val bench = record.benchmark as? Map<String, Any?>
            ?: return false
        return bench["status"] == BenchContract.STATUS_COMPLETED
    }

    @Suppress("UNCHECKED_CAST")
    fun buildSummary(record: SessionRecord): String {
        val lines = mutableListOf(
            "[${InsightCards.sessionTypeLabel(record)}] " +
                "${record.sessionId} · ${record.state}",
            "created: " +
                SessionTimeline.formatLocal(record.createdAtUtc),
            "model: ${record.modelDisplay}"
        )
        val prediction = record.prediction as? Map<String, Any?>
        if (prediction == null) {
            lines.add("prediction: not recorded")
        } else {
            lines.add("predicted (ESTIMATE): " +
                "${InsightCards.tps(prediction["predicted_gen_tps"])} " +
                "gen, ${InsightCards.tps(prediction["predicted_prefill_tps"])} " +
                "prefill [${prediction["source_type"]}]")
        }
        val benchmark = record.benchmark as? Map<String, Any?>
        if (benchmark == null) {
            lines.add("measured: not attached")
        } else if (benchmark["status"] != BenchContract.STATUS_COMPLETED) {
            // A failed run is not a zero-throughput
            // observation: no measurement exists.
            lines.add("measured: none " +
                "(run ${benchmark["status"]} — no observation) " +
                "${benchmark["run_id"]}")
        } else {
            val measurements =
                benchmark["measurements"] as? Map<String, Any?>
            val measured = if (measurements == null) {
                "?"
            } else {
                InsightCards.tps(measurements["tps_gen"])
            }
            lines.add("measured: $measured (${benchmark["status"]}) " +
                "${benchmark["run_id"]}")
        }
        val comparison = record.comparison as? Map<String, Any?>
        if (comparison != null) {
            lines.add(comparisonLine(comparison))
        }
        lines.add("action: ${InsightCards.pendingAction(record)}")
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
     * EF-UX-02 accordion session browser: fixed header
     * (count + filter), scrollable collapsed rows, fixed
     * action area (create + close). Rows expand inline;
     * expanded bodies render lazily so long histories stay
     * cheap. Technical evidence stays behind Details.
     *
     * @param load fresh session list (re-sorted newest first
     * defensively, so Latest is always correct).
     * @param activeId currently bound session, or null.
     * @param onCreate creates + authorizes an offline-test session.
     * @param onBind binds an authorized session to the next run.
     * @param onDelete deletes a session (after in-dialog
     * confirmation). Bind/delete return a status message.
     * @param onPredict retries a live prediction on a byok
     * session without one; @param onFollowUp starts follow-up
     * on a compared byok session. @param onRepeat creates a
     * standalone repeat of a completed session. @param
     * onCompare shows the run's repeat-series comparison.
     * @param focusId session expanded on open (e.g. just
     * created). @param onOpenRepeat opens the repeat-prep
     * screen for a newly created repeat; this dialog
     * dismisses first so the result is unmistakable.
     * Predict/FollowUp/Compare/Repeat dismiss this dialog
     * first (each opens its own next screen).
     */
    fun show(
        activity: Activity,
        load: () -> List<SessionRecord>,
        activeId: () -> String?,
        onCreate: (Double, Double) -> SessionListUi.CreateOutcome,
        onBind: (String) -> String,
        onDelete: (String) -> String,
        onPredict: ((String) -> Unit)? = null,
        onFollowUp: ((String) -> Unit)? = null,
        onRepeat: ((String) -> SessionListUi.CreateOutcome)? = null,
        onCompare: ((String) -> Unit)? = null,
        focusId: String? = null,
        onOpenRepeat: ((String) -> Unit)? = null,
        onClose: (() -> Unit)? = null,
        onRecover: ((String) -> Unit)? = null,
        storageNote: String? = null
    ) {
        var dialog: AlertDialog? = null
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val ink = activity.resources.getColor(
            R.color.edgefit_text, activity.theme)
        val secondary = activity.resources.getColor(
            R.color.edgefit_text_secondary, activity.theme)
        val blue = activity.resources.getColor(
            R.color.edgefit_primary, activity.theme)
        var filter = SessionListUi.SessionFilter.ALL
        val expanded = mutableSetOf<String>()
        if (focusId != null) expanded.add(focusId)
        // EF-SESSION-SCALE-01 render window. Grows only
        // via the explicit Show-more control; the filter
        // resets it so a new query starts at the top.
        var shownLimit = PAGE_SIZE
        val notice = TextView(activity).apply {
            textSize = 14f
            setTextColor(ink)
        }
        val count = TextView(activity).apply {
            textSize = 13f
            setTextColor(secondary)
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(activity).apply { addView(list) }
        /** Renders card sections inline (expanded bodies). */
        fun renderSections(
            parent: LinearLayout,
            sections: List<InsightCards.Section>
        ) {
            for (section in sections) {
                parent.addView(TextView(activity).apply {
                    text = section.title
                    textSize = 14f
                    setTextColor(ink)
                    SemanticUi.bold(this)
                    setPadding(0, dp(8), 0, 0)
                })
                for (row in section.rows) {
                    parent.addView(TextView(activity).apply {
                        text = row.label
                        textSize = 12f
                        setTextColor(secondary)
                        setPadding(0, dp(4), 0, 0)
                    })
                    parent.addView(TextView(activity).apply {
                        text = row.value
                        setTextIsSelectable(true)
                        if (row.accent == InsightCards.Accent.NONE) {
                            textSize = 14f
                            setTextColor(ink)
                        } else {
                            textSize = 15f
                            SemanticUi.bold(this)
                            setTextColor(SemanticUi.accentColor(
                                activity, row.accent))
                        }
                    })
                }
            }
        }
        fun refresh(scrollTop: Boolean = false) {
            val keepY = scroll.scrollY
            list.removeAllViews()
            val bound = activeId()
            val sessions = try {
                SessionListUi.sortNewestFirst(load())
            } catch (e: Exception) {
                notice.text = "Session list failed: ${e.message}"
                return
            }
            val latest = SessionListUi.latestId(sessions)
            val shown =
                SessionListUi.filterSessions(sessions, filter)
            val baseCount = if (filter ==
                SessionListUi.SessionFilter.ALL
            ) {
                "${sessions.size} sessions"
            } else {
                "${shown.size} of ${sessions.size} · ${filter.label}"
            }
            count.text = if (storageNote != null) {
                "$baseCount · $storageNote"
            } else {
                baseCount
            }
            if (shown.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = SessionListUi.emptyStateText(filter)
                    textSize = 14f
                    setTextColor(secondary)
                    setPadding(0, dp(12), 0, dp(12))
                })
            }
            /** One expanded-body action-grid entry. */
            class Act(
                val label: String,
                val primary: Boolean = false,
                val destructive: Boolean = false,
                val enabled: Boolean = true,
                val run: (Button) -> Unit
            )
            // Windowed rendering: newest-first order is
            // computed over everything, but only the head
            // inflates views. Latest is always visible
            // (index 0) regardless of history size.
            val visible = shown.take(shownLimit)
            for (record in visible) {
                val isLatest = record.sessionId == latest
                val isOpen = expanded.contains(record.sessionId)
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = if (isLatest) {
                        GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(8).toFloat()
                            setStroke(dp(2), blue)
                            setColor(activity.resources.getColor(
                                R.color.edgefit_background,
                                activity.theme))
                        }
                    } else {
                        activity.getDrawable(R.drawable.card_bg)
                    }
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                if (isLatest) {
                    card.addView(TextView(activity).apply {
                        text = "Latest Session"
                        textSize = 12f
                        setTextColor(blue)
                        SemanticUi.bold(this)
                    })
                }
                var first = SessionListUi.rowLabel(record)
                if (record.sessionId == bound) first += " · BOUND"
                var second = SessionListUi.shortModelName(
                    record.modelDisplay) + " · " +
                    SessionListUi.friendlyState(record)
                SessionListUi.primaryMeasurement(record)?.let {
                    second += " · $it"
                }
                val head = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = dp(48)
                    isClickable = true
                    isFocusable = true
                }
                head.addView(TextView(activity).apply {
                    text = if (isOpen) "▾ " else "▸ "
                    textSize = 16f
                    setTextColor(blue)
                })
                val title = TextView(activity).apply {
                    textSize = 14f
                    setTextColor(ink)
                }
                // Friendly state tinted by semantic accent;
                // the text still carries the meaning.
                SemanticUi.tintToken(title, "$first\n$second",
                    SessionListUi.friendlyState(record),
                    SemanticUi.accentColor(activity,
                        InsightCards.statusAccent(record.state)))
                head.addView(title, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                head.contentDescription = "$first. $second. " +
                    if (isOpen) "Expanded." else "Collapsed."
                head.setOnClickListener {
                    if (isOpen) expanded.remove(record.sessionId)
                    else expanded.add(record.sessionId)
                    refresh()
                }
                card.addView(head)
                if (isOpen) {
                    val body = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    // Inline everything but technical
                    // evidence (Details shows the full
                    // record); a Next section names the
                    // pending action.
                    val inline = mutableListOf<InsightCards.Section>()
                    for (section in
                        InsightCards.sessionSections(record)
                    ) {
                        inline.add(section)
                        if (section.title == "Session") {
                            inline.add(InsightCards.Section("Next",
                                listOf(InsightCards.Row("Next action",
                                    InsightCards.pendingAction(
                                        record)))))
                        }
                    }
                    renderSections(body, inline.filter {
                        it.title != "Technical evidence"
                    })
                    val acts = mutableListOf<Act>()
                    acts.add(Act("Bind",
                        enabled = record.state ==
                            SessionState.BENCHMARK_AUTHORIZED &&
                            record.sessionId != bound,
                        run = {
                            notice.text = onBind(record.sessionId)
                            refresh()
                        }))
                    if (onPredict != null && canPredict(record)) {
                        acts.add(Act("Predict", primary = true,
                            run = {
                                dialog?.dismiss()
                                onPredict(record.sessionId)
                            }))
                    }
                    if (onFollowUp != null &&
                        SessionListUi.canFollowUp(record)
                    ) {
                        acts.add(Act("Follow-Up", primary = true,
                            run = {
                                dialog?.dismiss()
                                onFollowUp(record.sessionId)
                            }))
                    }
                    if (onRepeat != null &&
                        canRepeatStandalone(record)
                    ) {
                        acts.add(Act("Repeat", run = { button ->
                            // Double-tap guard: the host call
                            // is synchronous; refresh rebuilds
                            // the grid right after, so a second
                            // tap cannot create a duplicate.
                            button.isEnabled = false
                            val outcome =
                                onRepeat(record.sessionId)
                            val newId = outcome.newSessionId
                            if (newId != null &&
                                onOpenRepeat != null
                            ) {
                                dialog?.dismiss()
                                onOpenRepeat(newId)
                            } else {
                                notice.text = outcome.message
                                if (newId != null) {
                                    expanded.add(newId)
                                }
                                refresh(scrollTop = newId != null)
                            }
                        }))
                    }
                    if (onCompare != null && canCompare(record)) {
                        acts.add(Act("Compare", run = {
                            dialog?.dismiss()
                            onCompare(record.sessionId)
                        }))
                    }
                    if (onRecover != null && canRecover(record)) {
                        acts.add(Act("Recover", run = {
                            dialog?.dismiss()
                            onRecover(record.sessionId)
                        }))
                    }
                    acts.add(Act("Details", run = {
                        CardDialog.show(activity, "Session Detail",
                            InsightCards.sessionSections(record))
                    }))
                    acts.add(Act("Delete", destructive = true,
                        run = {
                            AlertDialog.Builder(activity)
                                .setTitle("Delete " +
                                    "${SessionListUi.rowLabel(record)}?")
                                .setMessage("Remove this session " +
                                    "record? This cannot be undone.")
                                .setPositiveButton("Delete") { _, _ ->
                                    notice.text =
                                        onDelete(record.sessionId)
                                    expanded.remove(record.sessionId)
                                    refresh()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }))
                    // Two buttons per row at full width:
                    // labels are never truncated.
                    for (pair in acts.chunked(2)) {
                        val buttonRow = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                        }
                        for (act in pair) {
                            val button = Button(activity).apply {
                                text = act.label
                                isEnabled = act.enabled
                                isSingleLine = true
                            }
                            if (act.primary) {
                                SemanticUi.stylePrimary(
                                    activity, button)
                            }
                            if (act.destructive) {
                                SemanticUi.styleDestructive(
                                    activity, button)
                            }
                            button.setOnClickListener {
                                act.run(button)
                            }
                            buttonRow.addView(button,
                                LinearLayout.LayoutParams(0,
                                    LinearLayout.LayoutParams
                                        .WRAP_CONTENT, 1f))
                        }
                        body.addView(buttonRow)
                    }
                    card.addView(body)
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, 0, dp(8))
                list.addView(card, params)
            }
            val remaining = shown.size - visible.size
            if (remaining > 0) {
                list.addView(Button(activity).apply {
                    text = "Show more ($remaining remaining)"
                    setOnClickListener {
                        shownLimit += PAGE_SIZE
                        refresh()
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            scroll.post {
                scroll.scrollY = if (scrollTop) 0 else keepY
            }
        }
        // Fixed header: count + filter. The list below
        // scrolls; header and action area stay put.
        val filters = SessionListUi.SessionFilter.values()
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity,
                android.R.layout.simple_spinner_item,
                filters.map { it.label }).also {
                it.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item)
            }
            contentDescription = "Filter sessions"
            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?,
                        position: Int, id: Long
                    ) {
                        val next = filters[position]
                        if (next != filter) {
                            filter = next
                            shownLimit = PAGE_SIZE
                            refresh(scrollTop = true)
                        }
                    }

                    override fun onNothingSelected(
                        parent: AdapterView<*>?
                    ) {
                    }
                }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(count, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        // Bounded list height keeps the action area on
        // screen however long the history grows.
        val maxList = (activity.resources.displayMetrics
            .heightPixels * 0.55).toInt()
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, maxList)
        /** Compact creation panel; success focuses the new row. */
        fun showCreatePanel() {
            val panelNotice = TextView(activity).apply {
                textSize = 13f
                setTextColor(secondary)
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
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(16)
                setPadding(pad, 0, pad, 0)
                addView(TextView(activity).apply {
                    text = "Offline test only — not a Meta " +
                        "prediction. Stage a model first; the " +
                        "session binds its hash and the current " +
                        "benchmark config."
                    textSize = 13f
                    setTextColor(secondary)
                })
                addView(genInput)
                addView(prefillInput)
                addView(panelNotice)
            }
            val createDialog = AlertDialog.Builder(activity)
                .setTitle("New Offline Test Session")
                .setView(panel)
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create()
            // Validate before dismissing: bad input keeps
            // the panel open with an explanation.
            createDialog.setOnShowListener {
                createDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        val gen =
                            parsePredicted(genInput.text.toString())
                        val prefill = parsePredicted(
                            prefillInput.text.toString())
                        if (gen == null || prefill == null) {
                            panelNotice.text = "Enter finite " +
                                "predicted rates above zero."
                            return@setOnClickListener
                        }
                        val outcome = onCreate(gen, prefill)
                        createDialog.dismiss()
                        notice.text = outcome.message
                        val newId = outcome.newSessionId
                        if (newId != null) expanded.add(newId)
                        refresh(scrollTop = newId != null)
                    }
            }
            createDialog.show()
        }
        val actionArea = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        actionArea.addView(Button(activity).apply {
            text = "New Offline Test Session"
            SemanticUi.stylePrimary(activity, this)
            setOnClickListener { showCreatePanel() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        actionArea.addView(Button(activity).apply {
            text = "Close"
            setOnClickListener { dialog?.dismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(notice)
            addView(header)
            addView(scroll)
            addView(actionArea)
        }
        refresh()
        dialog = AlertDialog.Builder(activity)
            .setTitle("Local Sessions")
            .setView(content)
            .show()
        onClose?.let { callback ->
            dialog?.setOnDismissListener { callback() }
        }
    }
}
