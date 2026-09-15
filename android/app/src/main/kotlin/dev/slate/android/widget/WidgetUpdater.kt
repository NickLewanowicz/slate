package dev.slate.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import dev.slate.android.data.CacheStore
import dev.slate.android.data.QueueStore
import dev.slate.android.spec.SpecParser

/** The slate a widget binds to (per-widget picker is P1). */
const val DEFAULT_WIDGET_SLATE_ID = "home"

/** Abstraction over AppWidgetManager updates so workers/tests can observe or fake them. */
interface WidgetUpdater {
    suspend fun updateAll()
    suspend fun updateOne(appWidgetId: Int)
}

/** Renders widgets from the local cache — never from the network. */
class CacheWidgetUpdater(
    private val context: Context,
    private val cacheStore: CacheStore,
    private val queueStore: QueueStore,
    private val parser: SpecParser = SpecParser(),
) : WidgetUpdater {

    override suspend fun updateAll() {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, SlateWidgetProvider::class.java))
        ids.forEach { renderInto(manager, it) }
    }

    override suspend fun updateOne(appWidgetId: Int) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        renderInto(manager, appWidgetId)
    }

    suspend fun renderInto(manager: AppWidgetManager, appWidgetId: Int) {
        val bucket = bucketFor(manager, appWidgetId)
        val cached = cacheStore.get(DEFAULT_WIDGET_SLATE_ID)
        val spec = cached?.let { parser.parseOrNull(it.rawJson) }
        val answered = queueStore.answeredFor(DEFAULT_WIDGET_SLATE_ID)
        val views = WidgetRenderer.render(
            context = context,
            bucket = bucket,
            spec = spec,
            cached = cached,
            answered = answered,
            appWidgetId = appWidgetId,
        )
        WidgetRendererSync.updateIfBound(context, manager, appWidgetId, views)
    }

    fun bucketFor(manager: AppWidgetManager, appWidgetId: Int): SizeBucket =
        SizeBucket.fromOptions(manager.getAppWidgetOptions(appWidgetId))
}

/** Blocking render used directly inside AppWidgetProvider callbacks. */
object WidgetRendererSync {

    /**
     * Push views to a widget only when the id is actually bound: AppWidgetManager
     * returns null provider info for unknown ids and some implementations NPE.
     */
    fun updateIfBound(context: Context, manager: AppWidgetManager, appWidgetId: Int, views: RemoteViews) {
        val bound = manager.getAppWidgetIds(ComponentName(context, SlateWidgetProvider::class.java))
        if (bound != null && bound.contains(appWidgetId)) {
            manager.updateAppWidget(appWidgetId, views)
        }
    }

    fun renderForWidget(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        cacheStore: CacheStore,
        queueStore: QueueStore,
        parser: SpecParser = SpecParser(),
    ): RemoteViewsResult {
        val options: Bundle? = manager.getAppWidgetOptions(appWidgetId)
        val bucket = SizeBucket.fromOptions(options)
        val cached = cacheStore.getBlocking(DEFAULT_WIDGET_SLATE_ID)
        val spec = cached?.let { parser.parseOrNull(it.rawJson) }
        val answered = queueStore.snapshotBlocking().answered
            .filterKeys { it.startsWith("$DEFAULT_WIDGET_SLATE_ID/") }
            .mapKeys { it.key.removePrefix("$DEFAULT_WIDGET_SLATE_ID/") }
        val views = WidgetRenderer.render(
            context = context,
            bucket = bucket,
            spec = spec,
            cached = cached,
            answered = answered,
            appWidgetId = appWidgetId,
        )
        updateIfBound(context, manager, appWidgetId, views)
        return RemoteViewsResult(bucket, spec != null)
    }

    data class RemoteViewsResult(val bucket: SizeBucket, val hasSpec: Boolean)
}
