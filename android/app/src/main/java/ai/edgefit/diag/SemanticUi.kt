package ai.edgefit.diag

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.TextView

/**
 * UX-01BC-R2 semantic render glue. Maps
 * [InsightCards.Accent] to color tokens and applies the
 * primary/destructive button styles. Thin Android glue —
 * all meaning lives in the label text and tested
 * builders, never in color alone.
 */
object SemanticUi {

    fun accentColor(
        activity: Activity,
        accent: InsightCards.Accent
    ): Int {
        val res = when (accent) {
            InsightCards.Accent.MEASURED -> R.color.edgefit_measured
            InsightCards.Accent.PREDICTED -> R.color.edgefit_predicted
            InsightCards.Accent.ERROR -> R.color.edgefit_error
            InsightCards.Accent.NONE -> R.color.edgefit_text
        }
        return activity.resources.getColor(res, activity.theme)
    }

    /** Blue filled primary action with white text. */
    fun stylePrimary(activity: Activity, button: Button) {
        button.backgroundTintList = ColorStateList.valueOf(
            activity.resources.getColor(
                R.color.edgefit_primary, activity.theme))
        button.setTextColor(activity.resources.getColor(
            R.color.edgefit_on_primary, activity.theme))
    }

    /** Destructive action: error-red text, neutral body. */
    fun styleDestructive(activity: Activity, button: Button) {
        button.setTextColor(activity.resources.getColor(
            R.color.edgefit_error, activity.theme))
    }

    /**
     * Colors one [token] occurrence inside [full] on the
     * view, bold. No-op when the token is absent, so
     * callers stay safe across format changes.
     */
    fun tintToken(
        view: TextView,
        full: String,
        token: String,
        color: Int
    ) {
        val start = full.indexOf(token)
        if (token.isEmpty() || start < 0) {
            view.text = full
            return
        }
        val spanned = SpannableString(full)
        spanned.setSpan(
            ForegroundColorSpan(color), start, start + token.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = spanned
    }

    fun bold(view: TextView) {
        view.setTypeface(view.typeface, Typeface.BOLD)
    }
}
