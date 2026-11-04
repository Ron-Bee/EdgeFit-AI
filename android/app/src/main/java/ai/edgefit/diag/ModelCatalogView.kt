package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * EF-MODEL-01B contextual catalog dialog. Everything
 * arrives precomputed: the current-model context, one
 * assessed + recommended card per entry, and three
 * working actions (Details, View Source, View Local
 * Evidence) plus Return to Model Selection. Nothing
 * downloads, runs, or phones home here. Presented in
 * the Aero dialog theme; measured values keep their
 * distinct accent from estimates.
 */
object ModelCatalogView {

    data class Card(
        val entry: ModelCatalog.Entry,
        val assessment: ModelSuitability.Assessment,
        val recommendation: ModelRecommend.Recommendation,
        val evidence: ModelRecommend.MatchedEvidence?,
        val sizeDiff: ModelContext.SizeDiff?
    )

    fun show(
        activity: Activity,
        current: ModelContext.CurrentModel,
        cards: List<Card>,
        onViewSource: (String) -> Unit,
        onViewEvidence: (String) -> Unit,
        onReturnToStaging: () -> Unit,
        onDismiss: (() -> Unit)? = null
    ) {
        val themed = ContextThemeWrapper(
            activity, R.style.Theme_EdgeFit_Aero_Dialog)
        var dialog: AlertDialog? = null
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val ink = AeroUi.inkColor(themed)
        val list = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
        }
        // Current-model overview, always first.
        list.addView(AeroUi.sectionHeader(themed, "Current model"))
        for (section in InsightCards.currentModelSections(current)) {
            list.addView(TextView(themed).apply {
                textSize = 13f
                setTextColor(ink)
                setTextIsSelectable(true)
                text = section.rows.joinToString("\n") {
                    "${it.label}: ${it.value}"
                }
            })
        }
        list.addView(AeroUi.sectionHeader(themed, "Recommended candidates"))
        if (cards.isEmpty()) {
            list.addView(TextView(themed).apply {
                text = "No justified recommendation for this device."
                textSize = 14f
                setTextColor(ink)
            })
        }
        val baseline = ModelContext.baselineName(current)
        for (card in cards) {
            val frame = AeroUi.card(themed)
            val statusLine =
                InsightCards.suitabilityLabel(card.assessment.status)
            val row = TextView(themed).apply {
                textSize = 13f
                setTextColor(ink)
                setTextIsSelectable(true)
                setPadding(0, dp(4), 0, dp(2))
            }
            SemanticUi.tintToken(
                row,
                InsightCards.modelCardLine(card.entry,
                    card.assessment, card.recommendation),
                statusLine,
                CardDialog.aeroAccentColor(themed,
                    InsightCards.suitabilityAccent(
                        card.assessment.status)))
            frame.addView(row)
            val buttons = LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun addButton(label: String, action: () -> Unit) {
                val button = Button(themed).apply {
                    text = label
                    isSingleLine = true
                    AeroUi.styleSecondary(themed, this)
                    setOnClickListener { action() }
                }
                buttons.addView(button, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            addButton("Details") {
                CardDialog.show(
                    activity,
                    "Model Detail",
                    InsightCards.modelEntrySections(
                        card.entry, card.assessment,
                        card.recommendation, card.evidence,
                        card.sizeDiff, baseline))
            }
            addButton("View Source") {
                onViewSource(card.entry.sourceUrl)
            }
            // Exact evidence only: the button exists iff a
            // same-hash COMPLETED run was matched.
            if (card.evidence != null) {
                addButton("Evidence") {
                    onViewEvidence(card.evidence.sessionId)
                }
            }
            frame.addView(buttons)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, dp(8))
            list.addView(frame, params)
        }
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(TextView(themed).apply {
                text = "Curated candidates worth investigating. " +
                    "Sizes are listed upper bounds; hashes are " +
                    "unverified; nothing here has been measured " +
                    "on this device unless stated. Selecting a " +
                    "different GGUF starts a new measurement " +
                    "context — not a same-artifact repeat. " +
                    "Previous measurements stay preserved."
                textSize = 13f
                setTextColor(ink)
            })
            addView(list)
            addView(Button(themed).apply {
                text = "Return to Model Selection"
                AeroUi.stylePrimary(themed, this)
                setOnClickListener {
                    dialog?.dismiss()
                    onReturnToStaging()
                }
            })
        }
        dialog = AlertDialog.Builder(themed)
            .setTitle("Recommended Models")
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
        if (onDismiss != null) {
            dialog?.setOnDismissListener { onDismiss() }
        }
    }
}
