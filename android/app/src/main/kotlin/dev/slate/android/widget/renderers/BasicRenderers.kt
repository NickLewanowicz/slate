package dev.slate.android.widget.renderers

import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import dev.slate.android.R
import dev.slate.android.spec.DividerElement
import dev.slate.android.spec.ProgressElement
import dev.slate.android.spec.SpecTextStyle
import dev.slate.android.spec.SlateElement
import dev.slate.android.spec.SpacerElement
import dev.slate.android.spec.TextElement
import dev.slate.android.spec.Tone
import dev.slate.android.spec.UnknownElement
import dev.slate.android.widget.WidgetColors

/** text — style maps to size/weight/lines per the schema description; tone colors the text. */
class TextRenderer : ElementRenderer {

    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews =
        render(element, ctx, stretch = false)

    fun render(element: SlateElement, ctx: RenderContext, stretch: Boolean): RemoteViews {
        val el = element as? TextElement ?: return plainFallback(ctx, stretch)
        val layout = if (stretch) R.layout.widget_text_stretch else R.layout.widget_text
        val views = RemoteViews(ctx.context.packageName, layout)
        val styleInfo: Triple<Float, Boolean, Int> = when (el.style) {
            SpecTextStyle.TITLE -> Triple(20f, true, 1)
            SpecTextStyle.HEADING -> Triple(16f, false, 2)
            SpecTextStyle.BODY -> Triple(14f, false, 3)
            SpecTextStyle.CAPTION -> Triple(12f, false, 1)
        }
        val (sizeSp, bold, defaultLines) = styleInfo
        // Bold styles use a bold variant layout (setPaintFlags fake-bold renders poorly).
        val effectiveLayout = if (bold && !stretch) R.layout.widget_text_bold else layout
        val finalViews = if (effectiveLayout != layout) {
            RemoteViews(ctx.context.packageName, effectiveLayout)
        } else {
            views
        }
        finalViews.setTextViewText(R.id.widget_text, el.text)
        finalViews.setTextViewTextSize(R.id.widget_text, TypedValue.COMPLEX_UNIT_SP, sizeSp)
        finalViews.setTextColor(
            R.id.widget_text,
            when {
                el.tone != Tone.NEUTRAL -> WidgetColors.toneColor(ctx.context, el.tone)
                el.style == SpecTextStyle.CAPTION -> WidgetColors.textSecondary(ctx.context)
                else -> WidgetColors.textPrimary(ctx.context)
            }
        )
        val lines = (el.maxLines ?: defaultLines).coerceIn(1, 10)
        finalViews.setInt(R.id.widget_text, "setMaxLines", lines)
        return finalViews
    }

    private fun plainFallback(ctx: RenderContext, stretch: Boolean): RemoteViews {
        val layout = if (stretch) R.layout.widget_text_stretch else R.layout.widget_text
        val views = RemoteViews(ctx.context.packageName, layout)
        views.setTextViewText(R.id.widget_text, "")
        return views
    }
}

/** spacer — fixed-height empty FrameLayout (bare View is forbidden in RemoteViews). */
class SpacerRenderer : ElementRenderer {
    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews {
        val el = element as? SpacerElement
        val views = RemoteViews(ctx.context.packageName, R.layout.widget_spacer)
        val height = (el?.height ?: 8).coerceIn(2, 48).toFloat()
        views.setViewLayoutHeight(R.id.widget_spacer, height, TypedValue.COMPLEX_UNIT_DIP)
        return views
    }
}

/** divider — 1dp FrameLayout line; inset dividers are narrower. */
class DividerRenderer : ElementRenderer {
    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews {
        val el = element as? DividerElement
        val views = RemoteViews(ctx.context.packageName, R.layout.widget_divider)
        if (el?.inset == true) {
            views.setViewLayoutWidth(R.id.widget_divider, 200f, TypedValue.COMPLEX_UNIT_DIP)
        }
        return views
    }
}

/** progress — label + determinate/indeterminate bar tinted by tone. */
class ProgressRenderer : ElementRenderer {

    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews {
        val el = element as? ProgressElement
            ?: return RemoteViews(ctx.context.packageName, R.layout.widget_spacer)
        val views = RemoteViews(ctx.context.packageName, R.layout.widget_progress)
        if (el.label.isNullOrBlank()) {
            views.setViewVisibility(R.id.widget_progress_label, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_progress_label, View.VISIBLE)
            views.setTextViewText(R.id.widget_progress_label, el.label)
        }
        if (el.indeterminate || el.value == null) {
            views.setProgressBar(R.id.widget_progress_bar, 100, 0, true)
        } else {
            views.setProgressBar(R.id.widget_progress_bar, 100, el.value.toInt().coerceIn(0, 100), false)
        }
        views.setColorStateList(
            R.id.widget_progress_bar,
            "setProgressTintList",
            android.content.res.ColorStateList.valueOf(WidgetColors.toneColor(ctx.context, el.tone)),
        )
        return views
    }
}

/** unknown — degrade to a caption naming the unsupported type; never crash. */
class UnknownRenderer : ElementRenderer {
    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews {
        val type = (element as? UnknownElement)?.type ?: element.typeName
        val views = RemoteViews(ctx.context.packageName, R.layout.widget_unknown)
        views.setTextViewText(R.id.widget_unknown_text, "[unsupported: $type]")
        return views
    }
}
