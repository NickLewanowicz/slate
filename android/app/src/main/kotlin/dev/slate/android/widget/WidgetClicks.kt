package dev.slate.android.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.slate.android.MainActivity

/**
 * Widget tap plumbing: ONE mutable broadcast PendingIntent template
 * (SlateWidgetClickReceiver) + per-view fill-in intents carrying
 * {appWidgetId, slateId, elementId, itemId/optionId}. Header / "reply in app"
 * rows deep-link into MainActivity instead.
 */
class WidgetClicks(private val context: Context) {

    private val templateIntent: Intent
        get() = Intent(context, SlateWidgetClickReceiver::class.java).setAction(ACTION_WIDGET_TAP)

    /** The single broadcast template. FLAG_MUTABLE is required for fill-in resolution. */
    val broadcastTemplate: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            0,
            templateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** Fill-in for a todo row tap. */
    fun todoFillIn(slateId: String, listId: String, itemId: String): Intent =
        templateIntent.fill(slateId).apply {
            putExtra(EXTRA_KIND, KIND_TODO)
            putExtra(EXTRA_ELEMENT_ID, listId)
            putExtra(EXTRA_ITEM_ID, itemId)
        }

    /** Fill-in for a question option tap. */
    fun optionFillIn(slateId: String, questionId: String, optionId: String): Intent =
        templateIntent.fill(slateId).apply {
            putExtra(EXTRA_KIND, KIND_OPTION)
            putExtra(EXTRA_ELEMENT_ID, questionId)
            putExtra(EXTRA_OPTION_ID, optionId)
        }

    /**
     * Explicit PendingIntent for a question-option button. Options are regular
     * views (not collection items), so setOnClickFillInIntent alone is a no-op —
     * each option carries its own immutable broadcast with full extras.
     */
    fun optionPendingIntent(
        appWidgetId: Int,
        slateId: String,
        questionId: String,
        optionId: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        ("$appWidgetId/$slateId/$questionId/$optionId").hashCode(),
        templateIntent.fill(slateId).apply {
            putExtra(EXTRA_KIND, KIND_OPTION)
            putExtra(EXTRA_ELEMENT_ID, questionId)
            putExtra(EXTRA_OPTION_ID, optionId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Deep link into the detail screen (header + "reply in app" row). */
    fun detailDeepLink(slateId: String): PendingIntent = PendingIntent.getActivity(
        context,
        ("detail/$slateId").hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse("slate://detail/$slateId"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun Intent.fill(slateId: String): Intent = apply {
        putExtra(EXTRA_SLATE_ID, slateId)
    }

    companion object {
        const val ACTION_WIDGET_TAP = "dev.slate.android.WIDGET_TAP"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SLATE_ID = "slateId"
        const val EXTRA_ELEMENT_ID = "elementId"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_OPTION_ID = "optionId"
        const val KIND_TODO = "todo"
        const val KIND_OPTION = "option"
    }
}
