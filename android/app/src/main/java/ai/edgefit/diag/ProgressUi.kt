package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * UX-01BC truthful progress: one indeterminate indicator plus
 * an operation message. Non-cancelable on purpose — the
 * underlying request has no cancel path, so the UI never
 * claims cancellation. Dismissed only by real completion or
 * failure. Thin glue over AlertDialog.
 */
object ProgressUi {
    class Handle(
        private val dialog: AlertDialog,
        private val label: TextView
    ) {
        fun update(message: String) {
            label.text = message
        }

        fun dismiss() {
            try {
                dialog.dismiss()
            } catch (_: Exception) {
            }
        }
    }

    fun show(activity: Activity, title: String, message: String): Handle {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density + 0.5f).toInt()
        val label = TextView(activity).apply {
            text = message
            textSize = 14f
            setPadding(pad, pad / 2, pad, pad)
        }
        val bar = ProgressBar(
            activity, null, android.R.attr.progressBarStyleLarge
        ).apply {
            isIndeterminate = true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(bar)
            addView(label)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setCancelable(false)
            .show()
        return Handle(dialog, label)
    }
}
