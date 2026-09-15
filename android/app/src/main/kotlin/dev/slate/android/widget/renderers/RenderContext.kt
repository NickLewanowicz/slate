package dev.slate.android.widget.renderers

import android.content.Context
import android.widget.RemoteViews
import dev.slate.android.data.AnsweredChoice
import dev.slate.android.spec.SlateElement
import dev.slate.android.spec.UnknownElement
import dev.slate.android.widget.SizeBucket
import dev.slate.android.widget.WidgetClicks

/**
 * Everything a per-element renderer needs to produce its RemoteViews fragment.
 * Renderers are pure functions of (element, context-state) → RemoteViews.
 */
class RenderContext(
    val context: Context,
    val bucket: SizeBucket,
    val slateId: String,
    val appWidgetId: Int,
    val answered: Map<String, AnsweredChoice>,
    val expired: Boolean,
    val clicks: WidgetClicks,
    /** Preview rendering (in-app embed) always renders todoLists inline, never via ListView. */
    val preview: Boolean,
) {
    /** Questions render compact (fewer options) outside the full bucket. */
    val compactQuestions: Boolean get() = bucket != SizeBucket.FULL
}

/** One element → one RemoteViews fragment. Registered in [RendererRegistry] by "type". */
fun interface ElementRenderer {
    fun render(element: SlateElement, ctx: RenderContext): RemoteViews
}

/** Dispatch map from element type → renderer. No god class: each type has its own renderer. */
class RendererRegistry {

    private val text = TextRenderer()
    private val statusRow = StatusRowRenderer()
    private val todoList = TodoListRenderer()
    private val question = QuestionRenderer()
    private val progress = ProgressRenderer()
    private val divider = DividerRenderer()
    private val spacer = SpacerRenderer()
    private val column = ColumnRenderer(this)
    private val row = RowRenderer(this)
    private val unknown = UnknownRenderer()

    private val renderers: Map<String, ElementRenderer> = mapOf(
        "text" to text,
        "statusRow" to statusRow,
        "todoList" to todoList,
        "question" to question,
        "progress" to progress,
        "divider" to divider,
        "spacer" to spacer,
        "column" to column,
        "row" to row,
        "unknown" to unknown,
    )

    fun render(element: SlateElement, ctx: RenderContext): RemoteViews {
        if (element is UnknownElement) return unknown.render(element, ctx)
        return renderers[element.typeName]?.render(element, ctx)
            ?: unknown.render(UnknownElement(element.typeName), ctx)
    }

    /** Direct access for callers that pre-dispatch (e.g. todoList → ListView collection items). */
    fun todoRenderer(): TodoListRenderer = todoList
    fun questionRenderer(): QuestionRenderer = question
}
