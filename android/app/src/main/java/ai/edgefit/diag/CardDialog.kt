package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * UX-01BC section renderer: headers + label/value rows +
 * optional monospace technical details. Thin glue —
 * content comes from [InsightCards].
 */
object CardDialog {
    /**
     * EF-UX-02 next-action button. Tapping dismisses this
     * dialog first, then runs [onTap] (which opens the next
     * screen or starts the next authorized step).
     */
    data class Action(
        val label: String,
        val primary: Boolean = false,
        val onTap: () -> Unit
    )

    fun show(
        activity: Activity,
        title: String,
        sections: List<InsightCards.Section>,
        actions: List<Action> = emptyList()
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val ink = activity.resources.getColor(
            R.color.edgefit_text, activity.theme)
        val secondary = activity.resources.getColor(
            R.color.edgefit_text_secondary, activity.theme)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        for (section in sections) {
            content.addView(TextView(activity).apply {
                text = section.title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ink)
                setPadding(0, dp(10), 0, dp(2))
            })
            for (row in section.rows) {
                content.addView(TextView(activity).apply {
                    text = row.label
                    textSize = 12f
                    setTextColor(secondary)
                    setPadding(0, dp(6), 0, 0)
                })
                content.addView(TextView(activity).apply {
                    text = row.value
                    if (row.accent == InsightCards.Accent.NONE) {
                        textSize = 14f
                        setTextColor(ink)
                    } else {
                        // Accented metric/state values read
                        // larger and bold; the label text
                        // still carries the meaning.
                        textSize = 16f
                        setTypeface(typeface,
                            android.graphics.Typeface.BOLD)
                        setTextColor(SemanticUi.accentColor(
                            activity, row.accent))
                    }
                    setTextIsSelectable(true)
                })
            }
            if (section.details != null) {
                content.addView(TextView(activity).apply {
                    text = section.details
                    textSize = 12f
                    setTextColor(secondary)
                    setTextIsSelectable(true)
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
        // Next-action buttons stack vertically at full
        // width: labels are never truncated or clipped.
        var dialog: AlertDialog? = null
        for (action in actions) {
            content.addView(Button(activity).apply {
                text = action.label
                if (action.primary) {
                    SemanticUi.stylePrimary(activity, this)
                }
                setOnClickListener {
                    dialog?.dismiss()
                    action.onTap()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
    }
}
