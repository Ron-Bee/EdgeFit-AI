package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * UX-01BC section renderer: headers + label/value rows +
 * optional monospace technical details. Thin glue —
 * content comes from [InsightCards]. Presented in the
 * Aero dialog theme on dark surfaces; all meaning still
 * lives in the label text, never in color alone.
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

    /**
     * Aero dark-surface accent for shared dialog rows.
     * Shared with [ModelCatalogView] so measured values
     * read identically everywhere.
     */
    internal fun aeroAccentColor(
        context: Context,
        accent: InsightCards.Accent
    ): Int = when (accent) {
        InsightCards.Accent.MEASURED ->
            context.getColor(R.color.aero_measured)
        InsightCards.Accent.PREDICTED ->
            context.getColor(R.color.aero_predicted)
        InsightCards.Accent.ERROR ->
            context.getColor(R.color.aero_error)
        InsightCards.Accent.NONE -> AeroUi.inkColor(context)
    }

    fun show(
        activity: Activity,
        title: String,
        sections: List<InsightCards.Section>,
        actions: List<Action> = emptyList(),
        // Lifecycle persistence: lets the host clear its
        // open-sheet tracker on any dismiss path. Optional;
        // existing callers unaffected.
        onDismiss: (() -> Unit)? = null
    ) {
        val themed = ContextThemeWrapper(
            activity, R.style.Theme_EdgeFit_Aero_Dialog)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val ink = AeroUi.inkColor(themed)
        val secondary = AeroUi.secondaryColor(themed)
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        for (section in sections) {
            content.addView(TextView(themed).apply {
                text = section.title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ink)
                setPadding(0, dp(10), 0, dp(2))
            })
            for (row in section.rows) {
                content.addView(TextView(themed).apply {
                    text = row.label
                    textSize = 12f
                    setTextColor(secondary)
                    setPadding(0, dp(6), 0, 0)
                })
                content.addView(TextView(themed).apply {
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
                        setTextColor(aeroAccentColor(themed, row.accent))
                    }
                    setTextIsSelectable(true)
                })
            }
            if (section.details != null) {
                content.addView(TextView(themed).apply {
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
            content.addView(Button(themed).apply {
                text = action.label
                if (action.primary) {
                    AeroUi.stylePrimary(themed, this)
                } else {
                    AeroUi.styleSecondary(themed, this)
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
        dialog = AlertDialog.Builder(themed)
            .setTitle(title)
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton("Close", null)
            .show()
        if (onDismiss != null) {
            dialog?.setOnDismissListener { onDismiss() }
        }
    }
}
