package ai.edgefit.diag

import android.app.Activity
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
            InsightCards.Accent.MEASURED -> R.color.aero_measured
            InsightCards.Accent.PREDICTED -> R.color.aero_predicted
            InsightCards.Accent.ERROR -> R.color.aero_error
            InsightCards.Accent.NONE -> R.color.aero_text_primary
        }
        return activity.resources.getColor(res, activity.theme)
    }

    /** Aero primary action (delegates to the kit). */
    fun stylePrimary(activity: Activity, button: Button) {
        AeroUi.stylePrimary(activity, button)
    }

    /** Destructive action: error-red text, neutral body. */
    fun styleDestructive(activity: Activity, button: Button) {
        // Dark-surface destructive: secondary body (the
        // manifest theme would otherwise supply a primary
        // fill), then error-red text.
        AeroUi.styleSecondary(activity, button)
        button.setTextColor(activity.resources.getColor(
            R.color.aero_error, activity.theme))
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
