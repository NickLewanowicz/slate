package dev.slate.android.widget.renderers

import android.view.View
import android.widget.RemoteViews
import dev.slate.android.R
import dev.slate.android.data.AnsweredChoice
import dev.slate.android.spec.OptionStyle
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.QuestionOption
import dev.slate.android.spec.SlateElement
import dev.slate.android.spec.Tone
import dev.slate.android.widget.WidgetColors

/**
 * question — prompt heading + option buttons 2-per-row (primary = filled accent,
 * danger = filled red, quiet = outlined neutral). Answered questions render a
 * disabled state with "✓ <label>" on the chosen option. allowText questions get
 * a "💬 Reply in app" row that deep-links into the companion app.
 */
class QuestionRenderer : ElementRenderer {

    fun answeredFor(ctx: RenderContext, question: QuestionElement): AnsweredChoice? =
        ctx.answered[question.id]

    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews =
        (element as? QuestionElement)?.let { renderQuestion(ctx, it) }
            ?: UnknownRenderer().render(element, ctx)

    fun renderQuestion(ctx: RenderContext, question: QuestionElement): RemoteViews {
        val views = RemoteViews(ctx.context.packageName, R.layout.widget_question)
        views.setTextViewText(R.id.widget_question_prompt, question.prompt)
        views.removeAllViews(R.id.widget_question_options)

        val answered = answeredFor(ctx, question)
        val maxOptions = if (ctx.compactQuestions) MAX_OPTIONS_COMPACT else MAX_OPTIONS_FULL
        question.options.take(maxOptions).chunked(OPTIONS_PER_ROW).forEach { pair ->
            val row = RemoteViews(ctx.context.packageName, R.layout.widget_option_row)
            applyOption(row, ctx, question, pair[0], answered, R.id.widget_option_left)
            if (pair.size > 1) {
                applyOption(row, ctx, question, pair[1], answered, R.id.widget_option_right)
            } else {
                row.setViewVisibility(R.id.widget_option_right, View.INVISIBLE)
            }
            views.addView(R.id.widget_question_options, row)
        }

        if (question.allowText && answered == null) {
            views.setViewVisibility(R.id.widget_question_reply_row, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_question_reply_row,
                ctx.clicks.detailDeepLink(ctx.slateId),
            )
        } else {
            views.setViewVisibility(R.id.widget_question_reply_row, View.GONE)
        }
        return views
    }

    private fun applyOption(
        row: RemoteViews,
        ctx: RenderContext,
        question: QuestionElement,
        option: QuestionOption,
        answered: AnsweredChoice?,
        viewId: Int,
    ) {
        val isChosen = answered != null && answered.optionId == option.id
        row.setViewVisibility(viewId, View.VISIBLE)
        when {
            answered == null -> {
                row.setTextViewText(viewId, option.label)
                applyStyle(row, ctx, viewId, option.style)
                if (ctx.expired) {
                    // Expired surface: questions stay visible but answer nothing (P9 finding).
                    row.setFloat(viewId, "setAlpha", 0.4f)
                } else if (!ctx.preview) {
                    row.setOnClickPendingIntent(
                        viewId,
                        ctx.clicks.optionPendingIntent(ctx.appWidgetId, ctx.slateId, question.id, option.id),
                    )
                } else {
                    // In-app preview buttons are mockups, not live controls (P4/P6 finding).
                    row.setFloat(viewId, "setAlpha", 0.45f)
                }
            }
            isChosen -> {
                // The chosen option keeps its identity: "✓ <label>" + ok outline.
                row.setTextViewText(viewId, "✓ ${option.label}")
                row.setInt(viewId, "setBackgroundResource", R.drawable.widget_option_answered)
                row.setFloat(viewId, "setAlpha", 1f)
                row.setTextColor(viewId, WidgetColors.toneColor(ctx.context, Tone.OK))
            }
            else -> {
                // Untaken options fade to a disabled look; taps are removed.
                row.setTextViewText(viewId, option.label)
                applyStyle(row, ctx, viewId, OptionStyle.QUIET)
                row.setFloat(viewId, "setAlpha", 0.4f)
            }
        }
    }

    private fun applyStyle(row: RemoteViews, ctx: RenderContext, viewId: Int, style: OptionStyle) {
        when (style) {
            OptionStyle.PRIMARY -> {
                row.setInt(viewId, "setBackgroundResource", R.drawable.widget_option_primary)
                row.setTextColor(viewId, WidgetColors.optionTextOnFilled(ctx.context))
            }
            OptionStyle.DANGER -> {
                // Outline (not filled): destructive options must be less salient than
                // the primary — red fill invited accidental taps (P4/P6 finding).
                row.setInt(viewId, "setBackgroundResource", R.drawable.widget_option_quiet)
                row.setTextColor(viewId, WidgetColors.toneColor(ctx.context, dev.slate.android.spec.Tone.ERROR))
            }
            OptionStyle.QUIET -> {
                row.setInt(viewId, "setBackgroundResource", R.drawable.widget_option_quiet)
                row.setTextColor(viewId, WidgetColors.textPrimary(ctx.context))
            }
        }
    }

    companion object {
        const val OPTIONS_PER_ROW = 2
        const val MAX_OPTIONS_COMPACT = 2
        const val MAX_OPTIONS_FULL = 4
    }
}
