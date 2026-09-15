package dev.slate.android.widget.renderers

import android.view.View
import android.widget.RemoteViews
import dev.slate.android.R
import dev.slate.android.spec.SlateElement
import dev.slate.android.spec.StatusRowElement
import dev.slate.android.spec.Tone
import dev.slate.android.widget.WidgetColors

/**
 * statusRow — 3dp tone accent bar + icon glyph + label (weight 1, ellipsized)
 * + tone-colored value, with an optional second caption detail line.
 */
class StatusRowRenderer : ElementRenderer {

    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews {
        val el = element as? StatusRowElement
            ?: return RemoteViews(ctx.context.packageName, dev.slate.android.R.layout.widget_spacer)
        val views = RemoteViews(ctx.context.packageName, R.layout.widget_status_row)
        val tone = el.tone

        views.setTextViewText(R.id.widget_status_icon, el.icon)
        views.setTextViewText(R.id.widget_status_label, el.label)

        // Accent bar: FrameLayout background, square is fine at 3dp.
        views.setInt(R.id.widget_status_accent, "setBackgroundColor", WidgetColors.toneColor(ctx.context, tone))

        // Value: right-aligned, tone-colored; neutral values render secondary.
        if (el.value.isNullOrBlank()) {
            views.setViewVisibility(R.id.widget_status_value, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_status_value, View.VISIBLE)
            views.setTextViewText(R.id.widget_status_value, el.value)
            views.setTextColor(
                R.id.widget_status_value,
                if (tone == Tone.NEUTRAL) WidgetColors.textSecondary(ctx.context)
                else WidgetColors.toneColor(ctx.context, tone)
            )
        }

        // Detail caption line.
        if (el.detail.isNullOrBlank()) {
            views.setViewVisibility(R.id.widget_status_detail, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_status_detail, View.VISIBLE)
            views.setTextViewText(R.id.widget_status_detail, el.detail)
        }
        return views
    }
}
