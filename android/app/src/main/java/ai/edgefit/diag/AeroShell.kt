package ai.edgefit.diag

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * EF-UX-AERO-01 Step 2 app-shell kit: Aero top bar with
 * branding and title hierarchy, floating dock, and
 * card/button styling over the Step 1 tokens. Thin
 * Android glue — destinations, dialogs, and behavior
 * stay in MainActivity and the existing views.
 */
object AeroUi {

    fun pageColor(context: Context): Int =
        context.getColor(R.color.aero_page)

    fun inkColor(context: Context): Int =
        context.getColor(R.color.aero_text_primary)

    fun secondaryColor(context: Context): Int =
        context.getColor(R.color.aero_text_secondary)

    /** Sky accent for highlights on dark surfaces. */
    fun accentColor(context: Context): Int =
        context.getColor(R.color.aero_sky)

    fun errorColor(context: Context): Int =
        context.getColor(R.color.aero_error)

    /**
     * EF-UX-AERO-01 Step 4 origin accent for dark
     * surfaces. Measured reads cyan, predicted and
     * estimated read violet, unavailable and refused
     * comparisons read secondary — but the label text
     * always carries the meaning, never color alone.
     */
    fun originColor(
        context: Context,
        origin: AeroResult.Origin
    ): Int = when (origin) {
        AeroResult.Origin.MEASURED ->
            context.getColor(R.color.aero_measured)
        AeroResult.Origin.PREDICTED,
        AeroResult.Origin.ESTIMATED ->
            context.getColor(R.color.aero_predicted)
        AeroResult.Origin.UNAVAILABLE,
        AeroResult.Origin.NOT_COMPARABLE ->
            secondaryColor(context)
    }

    /**
     * EF-UX-AERO-01 Step 5 workspace kind accent for
     * dark surfaces. Measured reads cyan, predictions
     * violet, recommendations sky, approved experiments
     * green, unavailable secondary — but the kind label
     * always carries the meaning, never color alone.
     */
    fun agentKindColor(
        context: Context,
        kind: AeroAgent.CardKind
    ): Int = when (kind) {
        AeroAgent.CardKind.MEASURED ->
            context.getColor(R.color.aero_measured)
        AeroAgent.CardKind.PREDICTION ->
            context.getColor(R.color.aero_predicted)
        AeroAgent.CardKind.RECOMMENDATION ->
            accentColor(context)
        AeroAgent.CardKind.APPROVED_EXPERIMENT ->
            context.getColor(R.color.aero_success)
        AeroAgent.CardKind.UNAVAILABLE ->
            secondaryColor(context)
    }

    /** Azure gradient primary action, white label. */
    fun stylePrimary(context: Context, button: Button) {
        button.background =
            context.getDrawable(R.drawable.aero_button_primary_bg)
        button.backgroundTintList = null
        button.setTextColor(context.getColorStateList(
            R.color.aero_button_primary_text))
        button.minHeight = context.resources
            .getDimensionPixelSize(R.dimen.aero_button_height)
    }

    /** Outlined secondary action, sky label. */
    fun styleSecondary(context: Context, button: Button) {
        button.background =
            context.getDrawable(R.drawable.aero_button_secondary_bg)
        button.backgroundTintList = null
        button.setTextColor(context.getColorStateList(
            R.color.aero_button_secondary_text))
        button.minHeight = context.resources
            .getDimensionPixelSize(R.dimen.aero_button_height)
    }

    /** Borderless ghost action for tertiary rows. */
    fun styleGhost(context: Context, button: Button) {
        button.background =
            context.getDrawable(R.drawable.aero_button_ghost_bg)
        button.backgroundTintList = null
        button.setTextColor(
            context.getColorStateList(R.color.aero_ghost_text))
        button.minHeight = context.resources.getDimensionPixelSize(
            R.dimen.aero_button_height_compact)
    }

    /** Section header: bold primary line over card groups. */
    fun sectionHeader(context: Context, title: String): TextView =
        TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(inkColor(context))
            val density = context.resources.displayMetrics.density
            setPadding(0, (12 * density + 0.5f).toInt(),
                0, (4 * density + 0.5f).toInt())
        }

    /** Raised card surface with standard padding. */
    fun card(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.aero_card_bg)
            elevation = context.resources
                .getDimension(R.dimen.aero_elevation_card)
            val pad = context.resources
                .getDimensionPixelSize(R.dimen.aero_card_padding)
            setPadding(pad, pad, pad, pad)
        }

    /** Raised glow card surface with standard padding. */
    fun glowCard(context: Context): LinearLayout =
        card(context).apply {
            background = context.getDrawable(
                R.drawable.aero_card_glow_bg)
        }

    /** Bold in-card title line (16sp primary). */
    fun cardTitle(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(inkColor(context))
        }

    /** Body copy on dark surfaces (14sp primary). */
    fun bodyText(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(inkColor(context))
        }

    /** Caption copy on dark surfaces (12sp secondary). */
    fun captionText(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(secondaryColor(context))
        }

    /**
     * Hero glow card: overline, title, subtitle. Static
     * brand copy comes from HomeDashboard.hero().
     */
    fun heroCard(
        context: Context,
        overline: String,
        title: String,
        subtitle: String
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        return glowCard(context).apply {
            addView(TextView(context).apply {
                text = overline
                textSize = 12f
                letterSpacing = 0.12f
                isSingleLine = true
                setTextColor(secondaryColor(context))
            })
            addView(TextView(context).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(inkColor(context))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply {
                    topMargin = dp(4)
                })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 14f
                setTextColor(accentColor(context))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply {
                    topMargin = dp(2)
                })
        }
    }

    /** Metric tile: bordered mini-card; value updates live. */
    data class Tile(
        val root: LinearLayout,
        val valueView: TextView
    )

    fun metricTile(
        context: Context,
        label: String,
        value: String
    ): Tile {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val valueView = TextView(context).apply {
            text = value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(inkColor(context))
            contentDescription =
                HomeDashboard.tileDescription(label, value)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable
                .GradientDrawable().apply {
                    shape = android.graphics.drawable
                        .GradientDrawable.RECTANGLE
                    setColor(context.getColor(R.color.aero_overlay))
                    setStroke(dp(1), context.getColor(
                        R.color.aero_card_border))
                    cornerRadius = context.resources.getDimension(
                        R.dimen.aero_radius_s)
                }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(secondaryColor(context))
            })
            addView(valueView)
        }
        return Tile(root, valueView)
    }

    /** Weighted horizontal row with 8dp gaps. */
    fun tileRow(
        context: Context,
        vararg cells: View
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val density = context.resources.displayMetrics.density
            val gap = (8 * density + 0.5f).toInt()
            cells.forEachIndexed { index, child ->
                addView(child, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f).apply {
                    if (index > 0) leftMargin = gap
                })
            }
        }
}

/**
 * Aero top bar: brand mark + EDGEFIT overline, then the
 * screen title and a one-line descriptor (title
 * hierarchy). Updated on every tab switch.
 */
class AeroTopBar(context: Context) : LinearLayout(context) {

    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.getDrawable(
            R.drawable.aero_header_gradient)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val markSize = context.resources
            .getDimensionPixelSize(R.dimen.aero_icon_mark)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_aero_mark)
            imageTintList = ColorStateList.valueOf(
                AeroUi.accentColor(context))
            importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(markSize, markSize).apply {
            marginEnd = dp(12)
        })
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        column.addView(TextView(context).apply {
            text = context.getString(R.string.aero_wordmark_short)
            textSize = 12f
            letterSpacing = 0.12f
            isSingleLine = true
            setTextColor(AeroUi.secondaryColor(context))
        })
        titleView = TextView(context).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(AeroUi.inkColor(context))
        }
        subtitleView = TextView(context).apply {
            textSize = 12f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(AeroUi.secondaryColor(context))
        }
        column.addView(titleView)
        column.addView(subtitleView)
        addView(column, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT))
        setTab(NavTab.HOME)
    }

    fun setTab(tab: NavTab) {
        titleView.text = NavTab.title(tab)
        subtitleView.text = NavTab.subtitle(tab)
        contentDescription =
            "${NavTab.title(tab)} — ${NavTab.subtitle(tab)}"
    }
}

/**
 * Aero floating dock: compact rounded deep-navy bar with
 * the five destinations. Selected item shows an azure
 * wash, sky icon, primary label, and a subtle glow dot
 * behind the icon. Every item is at least a 48dp touch
 * target; weight distribution keeps all five visible on
 * small screens. Static views, no animation loops.
 */
class AeroDockView(context: Context) : LinearLayout(context) {

    var onSelect: ((NavTab) -> Unit)? = null

    private data class Item(
        val root: LinearLayout,
        val glow: ImageView,
        val icon: ImageView,
        val label: TextView
    )

    private val items = mutableMapOf<NavTab, Item>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = context.getDrawable(R.drawable.aero_dock_bg)
        elevation = context.resources
            .getDimension(R.dimen.aero_elevation_dock)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        setPadding(dp(8), dp(6), dp(8), dp(6))
        val iconSize = context.resources
            .getDimensionPixelSize(R.dimen.aero_dock_icon)
        val frameSize = iconSize + dp(8)
        val touch = context.resources
            .getDimensionPixelSize(R.dimen.aero_icon_touch)
        val iconTint = context
            .getColorStateList(R.color.aero_nav_icon_tint)
        val labelTint = context
            .getColorStateList(R.color.aero_nav_label_text)
        for (tab in NavTab.values()) {
            val glow = ImageView(context).apply {
                setImageResource(R.drawable.aero_glow_dot)
                importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.GONE
            }
            val icon = ImageView(context).apply {
                setImageResource(NavTab.icon(tab))
                imageTintList = iconTint
                importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val frame = FrameLayout(context).apply {
                addView(glow, FrameLayout.LayoutParams(
                    frameSize, frameSize, Gravity.CENTER))
                addView(icon, FrameLayout.LayoutParams(
                    iconSize, iconSize, Gravity.CENTER))
            }
            val label = TextView(context).apply {
                text = NavTab.title(tab)
                textSize = 11f
                isSingleLine = true
                gravity = Gravity.CENTER
                setTextColor(labelTint)
                // Shrinks to 9sp on narrow screens so the
                // longest title never clips (API 26+).
                setAutoSizeTextTypeUniformWithConfiguration(
                    9, 11, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            val root = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = context.getDrawable(
                    R.drawable.aero_dock_item_bg)
                foreground = context.getDrawable(
                    R.drawable.aero_ripple)
                minimumWidth = touch
                minimumHeight = touch
                setPadding(0, dp(4), 0, dp(4))
                addView(frame, LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT))
                addView(label, LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT))
                setOnClickListener { onSelect?.invoke(tab) }
            }
            items[tab] = Item(root, glow, icon, label)
            addView(root, LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f))
        }
        setSelected(NavTab.HOME)
    }

    fun setSelected(tab: NavTab) {
        for ((candidate, item) in items) {
            val selected = candidate == tab
            item.root.isSelected = selected
            item.icon.isSelected = selected
            item.label.isSelected = selected
            item.glow.visibility =
                if (selected) View.VISIBLE else View.GONE
            item.root.contentDescription =
                NavTab.title(candidate) +
                    if (selected) ", selected" else ""
        }
    }
}
