package dev.slate.android.widget

import android.util.TypedValue
import android.widget.RemoteViews
import dev.slate.android.R
import dev.slate.android.data.AnsweredChoice
import dev.slate.android.data.CachedSlate
import dev.slate.android.spec.SlateSpec
import dev.slate.android.spec.TodoListElement
import dev.slate.android.widget.renderers.RenderContext
import dev.slate.android.widget.renderers.RendererRegistry
import dev.slate.android.widget.renderers.TodoListRenderer

/**
 * Assembles the widget RemoteViews: accent bar (aggregate tone) → header
 * (title + freshness dot + relative time) → body per size bucket. The first
 * todoList in the FULL bucket renders via a root-level ListView with fixed
 * 44dp rows (RemoteCollectionItems); in-app previews render it inline instead.
 */
object WidgetRenderer {

    fun render(
        context: android.content.Context,
        bucket: SizeBucket,
        spec: SlateSpec?,
        cached: CachedSlate?,
        answered: Map<String, AnsweredChoice> = emptyMap(),
        appWidgetId: Int = 0,
        preview: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RemoteViews {
        val clicks = WidgetClicks(context)
        val root = RemoteViews(context.packageName, R.layout.widget_root)
        // RemoteViews updates replay INCREMENTALLY on the launcher's live view tree:
        // every addView slot MUST be cleared first or old renders stack up forever.
        root.removeAllViews(R.id.widget_header_slot)
        root.removeAllViews(R.id.widget_body_stack)
        root.removeAllViews(R.id.widget_body_after)

        // The widget renders from the CACHE ONLY (MVP_SPEC: server-down → cached
        // spec): no cached envelope means nothing trustworthy to show → waiting.
        if (spec == null || cached == null) {
            renderWaiting(root, context, cached)
            return root
        }

        val aggregate = SlateSpec.aggregateTone(spec)
        val expired = spec.isExpired(nowEpochMs)

        // Tone accent bar + header.
        root.setInt(R.id.widget_accent_bar, "setBackgroundColor", WidgetColors.toneColor(context, aggregate))
        val header = RemoteViews(context.packageName, R.layout.widget_header)
        header.setTextViewText(R.id.widget_title, spec.title.ifBlank { spec.id })
        val freshnessLabel = buildString {
            append(FreshnessFormatter.relative(cached?.fetchedAt, nowEpochMs))
            if (expired) append(" · stale")
        }
        header.setTextViewText(R.id.widget_freshness, freshnessLabel)
        header.setInt(
            R.id.widget_freshness_dot,
            "setBackgroundColor",
            WidgetColors.toneColor(context, if (expired) dev.slate.android.spec.Tone.NEUTRAL else aggregate),
        )
        header.setOnClickPendingIntent(R.id.widget_title, clicks.detailDeepLink(spec.id))
        header.setContentDescription(
            R.id.widget_freshness_dot,
            "${if (expired) "stale" else aggregate.name}, $freshnessLabel",
        )
        root.addView(R.id.widget_header_slot, header)

        // Bucket planning + per-element renderers.
        val plan = WidgetPlanner.plan(spec, bucket)
        val ctx = RenderContext(
            context = context,
            bucket = bucket,
            slateId = spec.id,
            appWidgetId = appWidgetId,
            answered = answered,
            expired = expired,
            clicks = clicks,
            preview = preview,
        )
        val registry = RendererRegistry()

        plan.bodyBefore.forEach { el -> root.addView(R.id.widget_body_stack, registry.render(el, ctx)) }
        plan.bodyAfter.forEach { el -> root.addView(R.id.widget_body_after, registry.render(el, ctx)) }

        // todoList: ListView collection in the full widget; inline rows in previews.
        val todo = plan.todoCollection
        if (todo != null) {
            if (preview || bucket != SizeBucket.FULL) {
                root.addView(R.id.widget_body_stack, registry.todoRenderer().renderInline(ctx, todo))
            } else {
                root.setRemoteAdapter(R.id.widget_todo_list, collectionItems(ctx, registry, todo))
                root.setViewLayoutHeight(
                    R.id.widget_todo_list,
                    TodoListRenderer.visibleRowCount(todo.items.size) * TodoListRenderer.ROW_HEIGHT_DP,
                    TypedValue.COMPLEX_UNIT_DIP,
                )
                root.setViewVisibility(R.id.widget_todo_list, android.view.View.VISIBLE)
                root.setPendingIntentTemplate(R.id.widget_todo_list, clicks.broadcastTemplate)
            }
        } else {
            root.setViewVisibility(R.id.widget_todo_list, android.view.View.GONE)
        }

        // Empty spec → personality, not a void.
        if (plan.isEmpty) {
            root.addView(R.id.widget_body_stack, RemoteViews(context.packageName, R.layout.widget_empty))
        }
        return root
    }

    private fun renderWaiting(root: RemoteViews, context: android.content.Context, cached: CachedSlate?) {
        root.setInt(
            R.id.widget_accent_bar,
            "setBackgroundColor",
            WidgetColors.toneColor(context, dev.slate.android.spec.Tone.NEUTRAL),
        )
        val header = RemoteViews(context.packageName, R.layout.widget_header)
        header.setTextViewText(R.id.widget_title, "Slate")
        header.setTextViewText(R.id.widget_freshness, context.getString(R.string.widget_freshness_unavailable))
        header.setInt(
            R.id.widget_freshness_dot,
            "setBackgroundColor",
            WidgetColors.toneColor(context, dev.slate.android.spec.Tone.NEUTRAL),
        )
        root.addView(R.id.widget_header_slot, header)
        val waitingLayout = if (cached == null) R.layout.widget_loading else R.layout.widget_empty
        root.addView(R.id.widget_body_stack, RemoteViews(context.packageName, waitingLayout))
    }

    /** Fixed-height ListView content: one RemoteViews per todo row. */
    private fun collectionItems(
        ctx: RenderContext,
        registry: RendererRegistry,
        todo: TodoListElement,
    ): RemoteViews.RemoteCollectionItems {
        val renderer = registry.todoRenderer()
        val builder = RemoteViews.RemoteCollectionItems.Builder()
            .setViewTypeCount(1)
            .setHasStableIds(true)
        todo.items.forEachIndexed { index, item ->
            builder.addItem(item.id.hashCode().toLong(), renderer.rowViews(ctx, todo, item))
        }
        return builder.build()
    }
}
