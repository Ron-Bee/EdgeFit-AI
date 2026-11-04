package ai.edgefit.diag

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * EF-ANDROID-AERO-OVERHAUL-01 shared view builders. Thin
 * Android glue over the [AeroUi] kit and the pure
 * decision/derivation objects: status chips, evidence
 * rows, and the Decision Card. Builders read
 * already-derived values only — no loading, no logic, no
 * behavior changes. Colors come from `aero_*` tokens;
 * label text always carries the meaning, never color
 * alone.
 */
object EdgeFitCards {

    /** Chip accent families for dark surfaces. */
    enum class ChipKind {
        MEASURED,
        ESTIMATED,
        NEUTRAL,
        WARNING,
        ERROR,
        /** Sky: running agent sessions. Label still names the state. */
        ACTIVE
    }

    /**
     * Pure state-to-chip mapping (unit-tested). Measured
     * reads teal, estimates violet, hard failures error
     * red, insufficient evidence amber, everything else
     * neutral — the chip label still names the state.
     */
    fun chipKindFor(state: AeroDecision.HeroState): ChipKind =
        when (state) {
            AeroDecision.HeroState.MEASURED ->
                ChipKind.MEASURED
            AeroDecision.HeroState.ESTIMATED_FIT ->
                ChipKind.ESTIMATED
            AeroDecision.HeroState.NOT_SUPPORTED ->
                ChipKind.ERROR
            AeroDecision.HeroState.INSUFFICIENT_EVIDENCE ->
                ChipKind.WARNING
            AeroDecision.HeroState.NO_MODEL,
            AeroDecision.HeroState.STAGED_UNMEASURED ->
                ChipKind.NEUTRAL
        }

    fun chipColor(context: Context, kind: ChipKind): Int =
        when (kind) {
            ChipKind.MEASURED ->
                context.getColor(R.color.aero_measured)
            ChipKind.ESTIMATED ->
                context.getColor(R.color.aero_predicted)
            ChipKind.WARNING ->
                context.getColor(R.color.aero_warning)
            ChipKind.ERROR -> AeroUi.errorColor(context)
            ChipKind.ACTIVE -> AeroUi.accentColor(context)
            ChipKind.NEUTRAL -> AeroUi.secondaryColor(context)
        }

    /** Pill chip: bordered overlay, tinted label. */
    fun statusChip(
        context: Context,
        text: String,
        kind: ChipKind
    ): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val tint = chipColor(context, kind)
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(tint)
            contentDescription = text
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(context.getColor(R.color.aero_overlay))
                setStroke(dp(1), tint)
                cornerRadius = context.resources.getDimension(
                    R.dimen.aero_radius_pill)
            }
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
    }

    /**
     * One labeled evidence row: secondary caption over a
     * wrapping body value. A non-null origin tints the
     * value (measured teal / estimate violet); untinted
     * rows render ink. Screen readers hear "label: value".
     */
    fun evidenceRow(
        context: Context,
        row: AeroDecision.EvidenceRow
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(AeroUi.captionText(context, row.label))
            addView(AeroUi.bodyText(context, row.value).apply {
                row.origin?.let {
                    setTextColor(AeroUi.originColor(context, it))
                }
                contentDescription =
                    "${row.label}: ${row.value}"
            })
        }

    /**
     * Priority One Decision Card over one derived
     * [AeroDecision.DecisionCard]: brand overline, hero
     * model name, state chip, evidence rows, Meta AI
     * insight, and one primary button. The button only
     * fires the caller's existing handler ([onPrimary]) —
     * this builder introduces no navigation or execution.
     */
    fun decisionCard(
        context: Context,
        card: AeroDecision.DecisionCard,
        primaryLabel: String,
        onPrimary: () -> Unit
    ): LinearLayout {
        val gap = context.resources.getDimensionPixelSize(
            R.dimen.aero_space_8)
        fun params(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply {
                    topMargin = gap
                }
        return AeroUi.glowCard(context).apply {
            contentDescription =
                "${card.heroName} — ${card.stateLabel}"
            addView(TextView(context).apply {
                text = "RECOMMENDED MODEL"
                textSize = 12f
                letterSpacing = 0.12f
                isSingleLine = true
                setTextColor(
                    AeroUi.secondaryColor(context))
            })
            addView(TextView(context).apply {
                text = card.heroName
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(AeroUi.inkColor(context))
            }, params())
            addView(AeroUi.captionText(
                context, card.heroSub), params())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(statusChip(context, card.stateLabel,
                    chipKindFor(card.state)))
            }, params())
            for (row in card.rows) {
                addView(evidenceRow(context, row), params())
            }
            card.guidance?.let { guidance ->
                addView(AeroUi.captionText(context,
                    "Catalog guidance"), params())
                addView(AeroUi.bodyText(context, guidance))
            }
            addView(AeroUi.captionText(context,
                "META AI INSIGHT"), params())
            addView(AeroUi.bodyText(context, card.metaInsight)
                .apply {
                    if (!card.metaPresent) {
                        setTextColor(
                            AeroUi.secondaryColor(context))
                    }
                })
            addView(Button(context).apply {
                text = primaryLabel
                AeroUi.stylePrimary(context, this)
                setOnClickListener { onPrimary() }
            }, params().apply {
                topMargin = gap * 2
            })
        }
    }

    /**
     * Priority Two comparison block over one derived
     * [AeroCompare.CompareBlock]: Meta-estimate row in
     * violet, measured row in teal, stored-error
     * difference row, each labeled in text (never color
     * alone). A refused comparison renders neutral with
     * its stored reason in amber.
     */
    fun comparisonBlock(
        context: Context,
        block: AeroCompare.CompareBlock
    ): LinearLayout {
        val gap = context.resources.getDimensionPixelSize(
            R.dimen.aero_space_8)
        fun params(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply {
                    topMargin = gap
                }
        fun figureRow(
            label: String,
            caption: String,
            gen: String,
            prefill: String,
            origin: AeroResult.Origin?,
            valueColor: Int? = null
        ): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(AeroUi.captionText(context, label))
                addView(TextView(context).apply {
                    text = "Generation $gen · Prefill $prefill"
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(valueColor
                        ?: origin?.let {
                            AeroUi.originColor(context, it)
                        } ?: AeroUi.secondaryColor(context))
                    contentDescription =
                        "$label: Generation $gen, " +
                            "Prefill $prefill"
                })
                addView(AeroUi.captionText(context, caption))
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "PREDICTED VS MEASURED"
                textSize = 12f
                letterSpacing = 0.12f
                isSingleLine = true
                setTextColor(
                    AeroUi.secondaryColor(context))
            })
            addView(figureRow("Meta estimate",
                "Stored pre-run prediction (ESTIMATE)",
                block.predictedGen, block.predictedPrefill,
                AeroResult.Origin.ESTIMATED
                    .takeIf { block.hasPrediction }),
                params())
            addView(figureRow("Measured on this device",
                "Completed local benchmark",
                block.measuredGen, block.measuredPrefill,
                AeroResult.Origin.MEASURED
                    .takeIf { block.hasMeasurement }),
                params())
            addView(figureRow("Difference",
                block.deltaNote,
                block.deltaGen, block.deltaPrefill,
                null,
                valueColor = if (block.comparable) {
                    AeroUi.inkColor(context)
                } else {
                    context.getColor(R.color.aero_warning)
                }), params())
        }
    }

    /**
     * Priority Four journey strip over one derived
     * [AeroJourney.Journey]: four numbered nodes joined
     * by connectors (filled sky when evidenced, hollow
     * otherwise), stage captions for every lit stage
     * plus the first unreached one, then consent,
     * receipt, and server lines. The receipt-dialog
     * honesty line appears only once a receipt exists —
     * exactly when the confusion risk exists.
     */
    fun journeyStrip(
        context: Context,
        journey: AeroJourney.Journey
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val dotSize = dp(20)
        fun dotBackground(lit: Boolean): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (lit) {
                    setColor(AeroUi.accentColor(context))
                } else {
                    setColor(context.getColor(
                        R.color.aero_overlay))
                    setStroke(dp(1), AeroUi.secondaryColor(
                        context))
                }
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "EVIDENCE JOURNEY"
                textSize = 12f
                letterSpacing = 0.12f
                isSingleLine = true
                setTextColor(
                    AeroUi.secondaryColor(context))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val total = journey.nodes.size
                journey.nodes.forEachIndexed { index, node ->
                    if (index > 0) {
                        addView(View(context).apply {
                            setBackgroundColor(
                                context.getColor(
                                    R.color
                                        .aero_card_border))
                        }, LinearLayout.LayoutParams(
                            0, dp(2), 0.5f).apply {
                            topMargin = dp(9)
                        })
                    }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity
                            .CENTER_HORIZONTAL
                        contentDescription =
                            "Stage ${index + 1} of " +
                                "$total: ${node.label} — " +
                                "${node.caption}"
                        addView(TextView(context).apply {
                            text = "${index + 1}"
                            textSize = 11f
                            setTypeface(typeface,
                                Typeface.BOLD)
                            gravity = android.view.Gravity
                                .CENTER
                            setTextColor(if (node.lit) {
                                context.getColor(
                                    R.color.aero_ink)
                            } else {
                                AeroUi.secondaryColor(
                                    context)
                            })
                            background =
                                dotBackground(node.lit)
                        }, LinearLayout.LayoutParams(
                            dotSize, dotSize))
                        addView(TextView(context).apply {
                            text = node.label
                            textSize = 11f
                            maxLines = 2
                            gravity = android.view.Gravity
                                .CENTER
                            setTextColor(if (node.lit) {
                                AeroUi.inkColor(context)
                            } else {
                                AeroUi.secondaryColor(
                                    context)
                            })
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams
                                .MATCH_PARENT,
                            LinearLayout.LayoutParams
                                .WRAP_CONTENT))
                    }, LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams
                            .WRAP_CONTENT, 1f))
                }
            })
            val gap = context.resources.getDimensionPixelSize(
                R.dimen.aero_space_8)
            var newsShown = false
            journey.nodes.forEach { node ->
                // Every evidenced stage plus the first
                // unreached one; later planned stages
                // stay quiet until they carry news.
                if (!node.lit && newsShown) return@forEach
                if (!node.lit) newsShown = true
                addView(AeroUi.captionText(context,
                    "${node.label} — ${node.caption}"))
            }
            val submitted = journey.nodes.getOrNull(1)?.lit
                ?: false
            addView(AeroUi.bodyText(context,
                journey.consentLine))
            journey.receiptLine?.let {
                addView(AeroUi.bodyText(context, it))
            }
            addView(AeroUi.bodyText(context,
                journey.serverLine))
            if (submitted) {
                addView(AeroUi.captionText(context,
                    AeroJourney.HONESTY))
            }
            for (index in 1 until childCount) {
                (getChildAt(index).layoutParams as?
                    LinearLayout.LayoutParams)?.apply {
                    topMargin = gap
                }
            }
        }
    }

    /**
     * Priority Three workflow stepper over one derived
     * [AeroAgentSteps.Workflow]: vertical timeline of
     * numbered dots joined by a connector, each with a
     * label and a state caption. Status display only —
     * approval controls stay in the workspace dialog.
     */
    fun stepStrip(
        context: Context,
        workflow: AeroAgentSteps.Workflow
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val dotSize = dp(24)
        fun dotBackground(
            state: AeroAgentSteps.StepState
        ): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            when (state) {
                AeroAgentSteps.StepState.DONE ->
                    setColor(
                        context.getColor(
                            R.color.aero_success))
                AeroAgentSteps.StepState.CURRENT ->
                    setColor(AeroUi.accentColor(context))
                AeroAgentSteps.StepState.TODO -> {
                    setColor(context.getColor(
                        R.color.aero_overlay))
                    setStroke(dp(1), AeroUi.secondaryColor(
                        context))
                }
            }
        }
        fun stateWord(state: AeroAgentSteps.StepState): String =
            when (state) {
                AeroAgentSteps.StepState.DONE -> "done"
                AeroAgentSteps.StepState.CURRENT -> "current"
                AeroAgentSteps.StepState.TODO -> "not reached"
            }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "WORKFLOW — ${workflow.sessionCaption}"
                textSize = 12f
                letterSpacing = 0.12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(
                    AeroUi.secondaryColor(context))
            })
            val total = workflow.steps.size
            workflow.steps.forEachIndexed { index, step ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    contentDescription =
                        "Step ${index + 1} of $total: " +
                            "${step.label} — ${step.caption} " +
                            "— ${stateWord(step.state)}"
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity
                            .CENTER_HORIZONTAL
                        addView(TextView(context).apply {
                            text = "${index + 1}"
                            textSize = 12f
                            setTypeface(typeface,
                                Typeface.BOLD)
                            gravity = android.view.Gravity
                                .CENTER
                            setTextColor(if (step.state ==
                                AeroAgentSteps.StepState
                                    .TODO
                            ) {
                                AeroUi.secondaryColor(
                                    context)
                            } else {
                                context.getColor(
                                    R.color.aero_ink)
                            })
                            background =
                                dotBackground(step.state)
                        }, LinearLayout.LayoutParams(
                            dotSize, dotSize))
                        addView(View(context).apply {
                            setBackgroundColor(
                                context.getColor(
                                    R.color
                                        .aero_card_border))
                            visibility =
                                if (index == total - 1) {
                                    View.GONE
                                } else {
                                    View.VISIBLE
                                }
                        }, LinearLayout.LayoutParams(
                            dp(2), 0, 1f))
                    }, LinearLayout.LayoutParams(
                        dotSize,
                        LinearLayout.LayoutParams
                            .MATCH_PARENT))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, dp(12))
                        addView(TextView(context).apply {
                            text = step.label
                            textSize = 14f
                            setTypeface(typeface,
                                Typeface.BOLD)
                            setTextColor(if (step.state ==
                                AeroAgentSteps.StepState
                                    .TODO
                            ) {
                                AeroUi.secondaryColor(
                                    context)
                            } else {
                                AeroUi.inkColor(context)
                            })
                        })
                        addView(AeroUi.captionText(
                            context, step.caption))
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams
                            .MATCH_PARENT,
                        LinearLayout.LayoutParams
                            .WRAP_CONTENT))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}
