package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
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
 * downloads, runs, or phones home here.
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
        onReturnToStaging: () -> Unit
    ) {
        var dialog: AlertDialog? = null
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        // Current-model overview, always first.
        list.addView(TextView(activity).apply {
            text = "Current model"
            textSize = 16f
        })
        for (section in InsightCards.currentModelSections(current)) {
            list.addView(TextView(activity).apply {
                textSize = 13f
                setTextIsSelectable(true)
                text = section.rows.joinToString("\n") {
                    "${it.label}: ${it.value}"
                }
            })
        }
        list.addView(TextView(activity).apply {
            text = "Recommended candidates"
            textSize = 16f
            setPadding(0, dp(8), 0, 0)
        })
        if (cards.isEmpty()) {
            list.addView(TextView(activity).apply {
                text = "No justified recommendation for this device."
                textSize = 14f
            })
        }
        val baseline = ModelContext.baselineName(current)
        for (card in cards) {
            val frame = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = activity.getDrawable(R.drawable.card_bg)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val statusLine =
                InsightCards.suitabilityLabel(card.assessment.status)
            val row = TextView(activity).apply {
                textSize = 13f
                setTextIsSelectable(true)
                setPadding(0, dp(4), 0, dp(2))
            }
            SemanticUi.tintToken(
                row,
                InsightCards.modelCardLine(card.entry,
                    card.assessment, card.recommendation),
                statusLine,
                SemanticUi.accentColor(activity,
                    InsightCards.suitabilityAccent(
                        card.assessment.status)))
            frame.addView(row)
            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun addButton(label: String, action: () -> Unit) {
                val button = Button(activity).apply {
                    text = label
                    isSingleLine = true
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
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(TextView(activity).apply {
                text = "Curated candidates worth investigating. " +
                    "Sizes are listed upper bounds; hashes are " +
                    "unverified; nothing here has been measured " +
                    "on this device unless stated. Selecting a " +
                    "different GGUF starts a new measurement " +
                    "context — not a same-artifact repeat. " +
                    "Previous measurements stay preserved."
                textSize = 13f
            })
            addView(list)
            addView(Button(activity).apply {
                text = "Return to Model Selection"
                setOnClickListener {
                    dialog?.dismiss()
                    onReturnToStaging()
                }
            })
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("Recommended Models")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
    }
}
