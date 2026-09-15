package dev.slate.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.slate.android.di.AppContainer
import dev.slate.android.di.appContainer

/**
 * Home-screen widget. Renders synchronously from the DataStore cache (broadcast
 * receivers are synchronous and short-lived); network work belongs to
 * SyncWorker. updatePeriodMillis=0 — WorkManager owns scheduling, and
 * SLATE_SYNC_DONE broadcasts re-render after every sync.
 */
class SlateWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val container = context.appContainer()
        appWidgetIds.forEach { id -> renderOne(context, container, id) }
    }

    /** Re-render on resize — the bucket may change (compact ↔ medium ↔ full). */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        renderOne(context, context.appContainer(), appWidgetId)
    }

    /** Re-render after syncs (WorkManager also updates directly; this covers other producers). */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == dev.slate.android.sync.ACTION_SLATE_SYNC_DONE) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, SlateWidgetProvider::class.java)
            )
            val container = context.appContainer()
            ids.forEach { id -> renderOne(context, container, id) }
        }
    }

    private fun renderOne(context: Context, container: AppContainer, appWidgetId: Int) {
        runCatching {
            WidgetRendererSync.renderForWidget(
                context = context,
                manager = AppWidgetManager.getInstance(context) ?: return,
                appWidgetId = appWidgetId,
                cacheStore = container.cacheStore,
                queueStore = container.queueStore,
                parser = container.specParser,
            )
        }
    }
}
