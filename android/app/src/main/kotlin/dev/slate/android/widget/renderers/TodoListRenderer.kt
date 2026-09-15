package dev.slate.android.widget.renderers

import android.widget.RemoteViews
import dev.slate.android.R
import dev.slate.android.data.InteractionKind
import dev.slate.android.spec.SlateElement
import dev.slate.android.spec.TodoItem
import dev.slate.android.spec.TodoListElement
import dev.slate.android.spec.Tone
import dev.slate.android.widget.WidgetColors
import dev.slate.android.widget.WidgetClicks

/**
 * todoList — fixed-height 44dp rows (ListView items in the full bucket, inline
 * fragments elsewhere). Each row carries a fill-in intent; tapping toggles
 * optimistically through [dev.slate.android.interact.InteractionManager].
 */
class TodoListRenderer : ElementRenderer {

    fun rowViews(ctx: RenderContext, list: TodoListElement, item: TodoItem): RemoteViews {
        val views = RemoteViews(ctx.context.packageName, R.layout.widget_todo_row)
        val glyph = if (item.checked) "☑" else "☐"
        views.setTextViewText(R.id.widget_todo_check, glyph)
        views.setTextViewText(R.id.widget_todo_label, item.label)
        views.setContentDescription(
            R.id.widget_todo_label,
            "${item.label}, ${if (item.checked) "checked" else "not checked"}",
        )
        views.setTextColor(
            R.id.widget_todo_check,
            if (item.checked) WidgetColors.toneColor(ctx.context, Tone.OK) else WidgetColors.textSecondary(ctx.context)
        )
        views.setTextColor(
            R.id.widget_todo_label,
            if (item.tone == Tone.NEUTRAL) WidgetColors.textPrimary(ctx.context)
            else WidgetColors.toneColor(ctx.context, item.tone)
        )
        val accentColor = if (item.tone == Tone.NEUTRAL) WidgetColors.textSecondary(ctx.context)
        else WidgetColors.toneColor(ctx.context, item.tone)
        views.setInt(R.id.widget_todo_accent, "setBackgroundColor", accentColor)
        views.setOnClickFillInIntent(
            R.id.widget_todo_row,
            ctx.clicks.todoFillIn(ctx.slateId, list.id, item.id),
        )
        return views
    }

    /** Inline fragment (compact/medium buckets and extra todoLists in full). */
    fun renderInline(ctx: RenderContext, list: TodoListElement): RemoteViews {
        val container = RemoteViews(ctx.context.packageName, R.layout.widget_body_slot)
        val visible = list.items.take(MAX_INLINE_ROWS)
        visible.forEach { item -> container.addView(R.id.widget_body_slot, rowViews(ctx, list, item)) }
        return container
    }

    override fun render(element: SlateElement, ctx: RenderContext): RemoteViews =
        (element as? TodoListElement)?.let { renderInline(ctx, it) }
            ?: UnknownRenderer().render(element, ctx)

    companion object {
        const val MAX_INLINE_ROWS = 6
        const val ROW_HEIGHT_DP = 48f

        /** Visible rows drives the fixed ListView height: 44dp × min(items, 6). */
        fun visibleRowCount(items: Int): Int = items.coerceIn(0, MAX_INLINE_ROWS)
    }
}

/** InteractionKind used by tap handling for todo rows (check ↔ uncheck). */
fun checkedToKind(checked: Boolean): InteractionKind =
    if (checked) InteractionKind.check else InteractionKind.uncheck
