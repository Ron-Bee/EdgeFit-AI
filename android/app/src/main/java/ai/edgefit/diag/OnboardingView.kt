package ai.edgefit.diag

import android.app.Activity
import android.app.AlertDialog
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * EF-UX-01D first-run guide: three pages, one primary
 * action each, always skippable. Fully offline; the dialog
 * never triggers network, key, download, benchmark, or
 * paid work. Content comes from [Onboarding.pages] (pure,
 * unit-tested); this object only renders and navigates.
 * Main thread only.
 */
object OnboardingView {
    /**
     * @param startPage page to open on (rotation restore).
     * @param onPage reports navigation for state saving.
     * @param onDone(startBenchmark) fires once: true when
     * the developer taps the final primary action
     * (caller navigates to Benchmark), false on Skip or
     * backing out of page one.
     */
    fun show(
        activity: Activity,
        startPage: Int,
        onPage: (Int) -> Unit = {},
        onDone: (Boolean) -> Unit
    ) {
        var dialog: AlertDialog? = null
        var page = startPage.coerceIn(0, Onboarding.PAGE_COUNT - 1)
        var done = false
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val ink = activity.resources.getColor(
            R.color.edgefit_text, activity.theme)
        val secondary = activity.resources.getColor(
            R.color.edgefit_text_secondary, activity.theme)
        val title = TextView(activity).apply {
            textSize = 20f
            setTextColor(ink)
            SemanticUi.bold(this)
        }
        val indicator = TextView(activity).apply {
            textSize = 13f
            setTextColor(secondary)
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val primary = Button(activity).apply {
            SemanticUi.stylePrimary(activity, this)
        }
        val back = Button(activity).apply { text = "Back" }
        val skip = Button(activity).apply { text = "Skip" }
        fun finish(start: Boolean) {
            if (done) return
            done = true
            dialog?.dismiss()
            onDone(start)
        }
        fun render() {
            val data = Onboarding.pages()[page]
            title.text = data.title
            indicator.text =
                "${page + 1} of ${Onboarding.PAGE_COUNT}"
            body.removeAllViews()
            for (line in data.lines) {
                body.addView(TextView(activity).apply {
                    text = "•  $line"
                    textSize = 15f
                    setTextColor(ink)
                    setPadding(0, dp(4), 0, dp(4))
                })
            }
            primary.text = data.primary
            back.isEnabled = page > 0
            onPage(page)
        }
        primary.setOnClickListener {
            if (page == Onboarding.PAGE_COUNT - 1) finish(true)
            else {
                page = Onboarding.nextPage(page)
                render()
            }
        }
        back.setOnClickListener {
            page = Onboarding.prevPage(page)
            render()
        }
        skip.setOnClickListener { finish(false) }
        val secondaryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(back, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(skip, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(indicator)
            addView(body)
            addView(primary, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(secondaryRow)
        }
        dialog = AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply {
                addView(content)
            })
            .setCancelable(false)
            .setOnKeyListener { _, keyCode, event ->
                // Hardware Back walks pages; backing out of
                // page one is an explicit Skip.
                if (keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    if (page > 0) {
                        page = Onboarding.prevPage(page)
                        render()
                    } else {
                        finish(false)
                    }
                    true
                } else {
                    false
                }
            }
            .create()
        render()
        dialog.show()
    }
}
