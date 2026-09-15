package dev.slate.android.widget.renderers

import android.util.TypedValue
import android.view.Gravity as AndroidGravity
import android.widget.RemoteViews
import dev.slate.android.R
import dev.slate.android.spec.Align
import dev.slate.android.spec.ColumnElement
import dev.slate.android.spec.Gravity
import dev.slate.android.spec.RowElement
import dev.slate.android.spec.SlateElement
import dev.slate.android.spec.TextElement

/** column — vertical stack with optional gap spacers between children. */
class ColumnRenderer(private val registry: RendererRegistry) : ElementRenderer {

    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews {
        val el = element as? ColumnElement
            ?: return RemoteViews(ctx.context.packageName, R.layout.widget_body_slot)
        val container = RemoteViews(ctx.context.packageName, R.layout.widget_body_slot)
        container.setInt(R.id.widget_body_slot, "setGravity", gravityFor(el.align))
        if (el.padding > 0) {
            val pad = dp(ctx, el.padding)
            container.setViewPadding(R.id.widget_body_slot, pad, 0, pad, 0)
        }
        el.children.forEachIndexed { index, child ->
            container.addView(R.id.widget_body_slot, registry.render(child, ctx))
            if (el.gap > 0 && index < el.children.size - 1) {
                container.addView(R.id.widget_body_slot, verticalGap(ctx, el.gap))
            }
        }
        return container
    }

    private fun gravityFor(align: Align): Int = when (align) {
        Align.START -> AndroidGravity.START
        Align.CENTER -> AndroidGravity.CENTER_HORIZONTAL
        Align.END -> AndroidGravity.END
    }

    internal fun dp(ctx: RenderContext, value: Int): Int =
        (value * ctx.context.resources.displayMetrics.density).toInt()
}

/**
 * row — horizontal stack. gravity start: the LAST text child stretches and
 * ellipsizes (per schema); spaceBetween: weight-1 flex spacers push children
 * apart; center/end: container gravity.
 */
class RowRenderer(private val registry: RendererRegistry) : ElementRenderer {

    private val textRenderer = TextRenderer()

    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews {
        val el = element as? RowElement
            ?: return RemoteViews(ctx.context.packageName, R.layout.widget_row_slot)
        val container = RemoteViews(ctx.context.packageName, R.layout.widget_row_slot)
        container.setInt(R.id.widget_row_slot, "setGravity", gravityFor(el.gravity))
        val stretchIndex = if (el.gravity == Gravity.START) lastTextIndex(el.children) else -1

        el.children.forEachIndexed { index, child ->
            val fragment = when {
                index == stretchIndex && child is TextElement ->
                    textRenderer.render(child, ctx, stretch = true)
                else -> registry.render(child, ctx)
            }
            container.addView(R.id.widget_row_slot, fragment)
            val isLast = index == el.children.size - 1
            if (!isLast) {
                when {
                    el.gravity == Gravity.SPACE_BETWEEN ->
                        container.addView(R.id.widget_row_slot, flexSpacer(ctx))
                    el.gap > 0 ->
                        container.addView(R.id.widget_row_slot, horizontalGap(ctx, el.gap))
                }
            }
        }
        return container
    }

    private fun lastTextIndex(children: List<SlateElement>): Int =
        children.indexOfLast { it is TextElement }

    private fun gravityFor(gravity: Gravity): Int = when (gravity) {
        Gravity.START -> AndroidGravity.START
        Gravity.CENTER -> AndroidGravity.CENTER_HORIZONTAL
        Gravity.END -> AndroidGravity.END
        Gravity.SPACE_BETWEEN -> AndroidGravity.START
    }

    private fun flexSpacer(ctx: RenderContext): RemoteViews =
        RemoteViews(ctx.context.packageName, R.layout.widget_hspacer_weighted)

    private fun horizontalGap(ctx: RenderContext, gap: Int): RemoteViews {
        val views = RemoteViews(ctx.context.packageName, R.layout.widget_hspacer)
        views.setViewLayoutWidth(R.id.widget_hspacer, gap.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.widget_hspacer, 1f, TypedValue.COMPLEX_UNIT_DIP)
        return views
    }

    internal fun dp(ctx: RenderContext, value: Int): Int =
        (value * ctx.context.resources.displayMetrics.density).toInt()
}

private fun verticalGap(ctx: RenderContext, gap: Int): RemoteViews {
    val views = RemoteViews(ctx.context.packageName, R.layout.widget_hspacer)
    views.setViewLayoutWidth(R.id.widget_hspacer, 1f, TypedValue.COMPLEX_UNIT_DIP)
    views.setViewLayoutHeight(R.id.widget_hspacer, gap.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
    return views
}
