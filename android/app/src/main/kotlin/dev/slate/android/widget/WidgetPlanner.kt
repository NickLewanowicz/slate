package dev.slate.android.widget

import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.SlateElement
import dev.slate.android.spec.SlateSpec
import dev.slate.android.spec.StatusRowElement
import dev.slate.android.spec.TodoListElement

/**
 * Pure size-bucket planning: which elements appear per bucket.
 *  - COMPACT: title + up to [COMPACT_STATUS_ROWS] statusRows digest + freshness;
 *    when the slate has no statusRows, the first [COMPACT_FALLBACK] elements show instead.
 *  - MEDIUM: header + statusRows + first question (compact); when the slate has
 *    no statusRows the leading [COMPACT_FALLBACK] fallback elements stay visible
 *    alongside the first question.
 *  - FULL: everything; the first todoList with at least [MIN_COLLECTION_ITEMS]
 *    items becomes the fixed-height ListView collection (shorter lists and any
 *    later todoLists render inline, capped).
 */
object WidgetPlanner {

    const val COMPACT_STATUS_ROWS = 3
    const val COMPACT_FALLBACK = 2

    /**
     * The ListView collection (RemoteCollectionItems) only pays off once a list
     * is 3+ rows — shorter lists render inline instead of claiming a
     * fixed-height 44dp-per-row block of the home screen.
     */
    const val MIN_COLLECTION_ITEMS = 3

    data class Plan(
        val bodyBefore: List<SlateElement>,
        val todoCollection: TodoListElement?,
        val bodyAfter: List<SlateElement>,
    ) {
        val isEmpty: Boolean get() = bodyBefore.isEmpty() && todoCollection == null && bodyAfter.isEmpty()
    }

    fun plan(spec: SlateSpec, bucket: SizeBucket): Plan {
        val children = spec.children
        return when (bucket) {
            SizeBucket.COMPACT -> {
                val statusRows = children.filterIsInstance<StatusRowElement>().take(COMPACT_STATUS_ROWS)
                val body = statusRows.ifEmpty { children.take(COMPACT_FALLBACK) }
                Plan(bodyBefore = body, todoCollection = null, bodyAfter = emptyList())
            }
            SizeBucket.MEDIUM -> {
                val statusRows = children.filterIsInstance<StatusRowElement>()
                val question = children.filterIsInstance<QuestionElement>().firstOrNull()
                val body = if (statusRows.isNotEmpty()) {
                    statusRows + listOfNotNull(question)
                } else {
                    (children.take(COMPACT_FALLBACK) + listOfNotNull(question)).distinct()
                }
                Plan(bodyBefore = body, todoCollection = null, bodyAfter = emptyList())
            }
            SizeBucket.FULL -> {
                val firstTodoIndex =
                    children.indexOfFirst { it is TodoListElement && it.items.size >= MIN_COLLECTION_ITEMS }
                if (firstTodoIndex < 0) {
                    Plan(children, null, emptyList())
                } else {
                    Plan(
                        bodyBefore = children.take(firstTodoIndex),
                        todoCollection = children[firstTodoIndex] as TodoListElement,
                        bodyAfter = children.drop(firstTodoIndex + 1),
                    )
                }
            }
        }
    }
}
