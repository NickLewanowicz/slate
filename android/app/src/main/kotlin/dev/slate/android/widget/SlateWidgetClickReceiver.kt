package dev.slate.android.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.slate.android.di.appContainer
import dev.slate.android.interact.InteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Single broadcast template receiver for ALL widget taps. Fill-in intents carry
 * {slateId, kind, elementId, itemId/optionId}. goAsync() bridges the broadcast
 * lifecycle to the coroutine doing DataStore writes + expedited flush enqueue.
 */
class SlateWidgetClickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetClicks.ACTION_WIDGET_TAP) return
        val result = goAsync()
        val container = context.appContainer()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                container.widgetTapHandler.handle(context, intent)
            } catch (t: Throwable) {
                // Never crash the host process from a widget tap.
                Log.e("SlateWidget", "Tap handling failed", t)
            } finally {
                result.finish()
            }
        }
    }
}

/** Tap dispatch — unit tested without broadcast machinery. */
class WidgetTapHandler(
    private val interactions: InteractionManager,
    private val widgetUpdater: WidgetUpdater,
    private val flushNow: () -> Unit,
) {

    suspend fun handle(context: Context, intent: Intent) {
        val slateId = intent.getStringExtra(WidgetClicks.EXTRA_SLATE_ID) ?: DEFAULT_WIDGET_SLATE_ID
        when (intent.getStringExtra(WidgetClicks.EXTRA_KIND)) {
            WidgetClicks.KIND_TODO -> {
                val listId = intent.getStringExtra(WidgetClicks.EXTRA_ELEMENT_ID) ?: return
                val itemId = intent.getStringExtra(WidgetClicks.EXTRA_ITEM_ID) ?: return
                interactions.toggleTodo(slateId, listId, itemId)
                afterTap()
            }
            WidgetClicks.KIND_OPTION -> {
                val questionId = intent.getStringExtra(WidgetClicks.EXTRA_ELEMENT_ID) ?: return
                val optionId = intent.getStringExtra(WidgetClicks.EXTRA_OPTION_ID) ?: return
                val question = interactions.findQuestion(slateId, questionId) ?: return
                val option = question.options.firstOrNull { it.id == optionId } ?: return
                interactions.answerQuestion(slateId, question, option)
                afterTap()
            }
        }
    }

    /** Optimistic state is already persisted: re-render the widget, flush expedited. */
    private suspend fun afterTap() {
        runCatching { widgetUpdater.updateAll() }
        flushNow()
    }
}
