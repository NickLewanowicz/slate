package dev.slate.android.interact

import dev.slate.android.api.ApiConfig
import dev.slate.android.api.SlateApi
import dev.slate.android.api.SlateApiException
import dev.slate.android.data.AnsweredChoice
import dev.slate.android.data.CachedSlate
import dev.slate.android.data.CacheStore
import dev.slate.android.data.InteractionKind
import dev.slate.android.data.PendingInteraction
import dev.slate.android.data.QueueStore
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.QuestionOption
import dev.slate.android.spec.SlateSpec
import dev.slate.android.spec.SpecParser
import dev.slate.android.spec.TodoItem
import dev.slate.android.spec.TodoListElement
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Per-interaction delivery state shown as chips: Queued → Sending → Delivered. */
enum class DeliveryStatus { QUEUED, SENDING, DELIVERED, FAILED }

sealed class FlushOutcome {
    data object NothingQueued : FlushOutcome()
    data class Completed(
        val accepted: Int,
        val duplicates: Int,
        /** Batches dropped due to 4xx — caller must schedule a reconcile re-pull. */
        val droppedBatches: Int,
    ) : FlushOutcome() {
        val needsReconcile: Boolean get() = droppedBatches > 0
    }
}

/**
 * Tap → optimistic state + durable enqueue, atomically ordered:
 *   1. apply optimistic state to the cached spec / answered markers,
 *   2. persist cache,
 *   3. enqueue the interaction.
 * Queue entries are JSON in DataStore; removal happens ONLY on a confirmed 2xx
 * (4xx drops the batch and asks for a reconcile; network errors keep it for retry).
 */
class InteractionManager(
    private val queueStore: QueueStore,
    private val cacheStore: CacheStore,
    private val parser: SpecParser = SpecParser(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val uuid: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    private val _delivery = MutableStateFlow<Map<String, DeliveryStatus>>(emptyMap())
    val delivery: StateFlow<Map<String, DeliveryStatus>> = _delivery

    // ---- keys -----------------------------------------------------------

    fun questionKey(slateId: String, questionId: String) = "q:$slateId:$questionId"
    fun todoKey(slateId: String, listId: String, itemId: String) = "t:$slateId:$listId:$itemId"

    private fun keyOf(entry: PendingInteraction): String = when (entry.kind) {
        InteractionKind.answer, InteractionKind.text ->
            questionKey(entry.slateId, entry.questionId ?: entry.elementId ?: "?")
        InteractionKind.check, InteractionKind.uncheck ->
            todoKey(entry.slateId, entry.elementId ?: "?", entry.itemId ?: "?")
        InteractionKind.tap -> "tap:${entry.seq}"
    }

    // ---- pure spec transforms (unit tested) ------------------------------

    /** Pure: spec with one todo item's `checked` flipped. */
    fun withTodoChecked(spec: SlateSpec, listId: String, itemId: String, checked: Boolean): SlateSpec {
        val newChildren = spec.children.map { el ->
            if (el is TodoListElement && el.id == listId) {
                el.copy(items = el.items.map { item ->
                    if (item.id == itemId) item.copy(checked = checked) else item
                })
            } else el
        }
        return spec.copy(children = newChildren)
    }

    // ---- tap entry points -------------------------------------------------

    /** Toggle a todo item: optimistic flip + queue check/uncheck. Returns the new checked state. */
    suspend fun toggleTodo(slateId: String, listId: String, itemId: String): Boolean {
        val cached = cacheStore.get(slateId)
        val spec = cached?.let { parser.parseOrNull(it.rawJson) }
        val current = findItem(spec, listId, itemId)
        val newChecked = !(current?.checked ?: false)
        if (spec != null) {
            applyToCache(cached!!, withTodoChecked(spec, listId, itemId, newChecked))
        }
        queueStore.enqueue(
            PendingInteraction(
                seq = uuid(),
                slateId = slateId,
                kind = if (newChecked) InteractionKind.check else InteractionKind.uncheck,
                elementId = listId,
                itemId = itemId,
                clientAt = Instant.ofEpochMilli(clock()).toString(),
                createdAt = clock(),
            )
        )
        _delivery.value = _delivery.value + (todoKey(slateId, listId, itemId) to DeliveryStatus.QUEUED)
        return newChecked
    }

    /** Answer a question with a canned option: answered marker + queue answer. */
    suspend fun answerQuestion(slateId: String, question: QuestionElement, option: QuestionOption) {
        queueStore.markAnswered(
            slateId,
            AnsweredChoice(
                questionId = question.id,
                optionId = option.id,
                optionLabel = option.label,
                at = clock(),
            ),
        )
        queueStore.enqueue(
            PendingInteraction(
                seq = uuid(),
                slateId = slateId,
                kind = InteractionKind.answer,
                elementId = question.id,
                questionId = question.id,
                optionId = option.id,
                optionLabel = option.label,
                clientAt = Instant.ofEpochMilli(clock()).toString(),
                createdAt = clock(),
            )
        )
        _delivery.value = _delivery.value + (questionKey(slateId, question.id) to DeliveryStatus.QUEUED)
    }

    /** Free-text reply for allowText questions. */
    suspend fun sendText(slateId: String, question: QuestionElement, value: String) {
        if (value.isBlank()) return
        queueStore.markAnswered(
            slateId,
            AnsweredChoice(questionId = question.id, value = value, at = clock()),
        )
        queueStore.enqueue(
            PendingInteraction(
                seq = uuid(),
                slateId = slateId,
                kind = InteractionKind.text,
                elementId = question.id,
                questionId = question.id,
                value = value,
                clientAt = Instant.ofEpochMilli(clock()).toString(),
                createdAt = clock(),
            )
        )
        _delivery.value = _delivery.value + (questionKey(slateId, question.id) to DeliveryStatus.QUEUED)
    }

    // ---- flush -------------------------------------------------------------

    /**
     * Post queued interactions in batches of [MAX_BATCH]. Per batch:
     *  2xx  → remove those seqs (only place entries ever leave the queue on success),
     *  4xx  → drop batch + signal reconcile (invalid payload never fixes itself),
     *  5xx / network → rethrow so WorkManager retries with backoff.
     */
    suspend fun flushOnce(api: SlateApi, config: ApiConfig): FlushOutcome {
        val entries = queueStore.all()
        if (entries.isEmpty()) return FlushOutcome.NothingQueued

        var accepted = 0
        var duplicates = 0
        var droppedBatches = 0
        for (batch in batches(entries)) {
            batch.forEach { _delivery.value = _delivery.value + (keyOf(it) to DeliveryStatus.SENDING) }
            try {
                val ack = api.postInteractions(config, batch)
                queueStore.removeAll(batch.map { it.seq })
                // A batch can never confirm more than it sent (server bug guard);
                // duplicates still count as delivered (dedupe is by seq server-side).
                accepted += minOf(ack.accepted, batch.size)
                duplicates += ack.duplicate
                batch.forEach { _delivery.value = _delivery.value + (keyOf(it) to DeliveryStatus.DELIVERED) }
            } catch (e: SlateApiException) {
                if (e.isClientError) {
                    // 4xx: the server rejected the batch for good — drop + reconcile.
                    queueStore.removeAll(batch.map { it.seq })
                    droppedBatches++
                    batch.forEach { _delivery.value = _delivery.value + (keyOf(it) to DeliveryStatus.FAILED) }
                } else {
                    throw e
                }
            }
        }
        return FlushOutcome.Completed(accepted, duplicates, droppedBatches)
    }

    // ---- helpers -------------------------------------------------------------

    /** Locate a question element in the cached spec (for widget tap routing). */
    suspend fun findQuestion(slateId: String, questionId: String): QuestionElement? {
        val cached = cacheStore.get(slateId) ?: return null
        val spec = parser.parseOrNull(cached.rawJson) ?: return null
        return spec.children.filterIsInstance<QuestionElement>().firstOrNull { it.id == questionId }
    }

    private suspend fun applyToCache(cached: CachedSlate, spec: SlateSpec) {
        cacheStore.put(cached.copy(rawJson = parser.encode(spec)))
    }

    private fun findItem(spec: SlateSpec?, listId: String, itemId: String): TodoItem? =
        spec?.children?.filterIsInstance<TodoListElement>()
            ?.firstOrNull { it.id == listId }
            ?.items?.firstOrNull { it.id == itemId }

    companion object {
        /** schema/interactions.schema.json: maxItems 50 per batch. */
        const val MAX_BATCH = 50

        fun batches(entries: List<PendingInteraction>): List<List<PendingInteraction>> =
            if (entries.isEmpty()) emptyList() else entries.chunked(MAX_BATCH)
    }
}
