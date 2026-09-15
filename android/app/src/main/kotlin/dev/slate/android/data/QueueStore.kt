package dev.slate.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Interaction kinds per schema/interactions.schema.json. */
@Serializable
enum class InteractionKind {
    answer, check, uncheck, text, tap
}

/**
 * One queued tap, mirroring schema/interactions.schema.json. `seq` is the
 * idempotency key (random UUID per entry, never reused after a 2xx).
 */
@Serializable
data class PendingInteraction(
    val seq: String,
    val slateId: String,
    val kind: InteractionKind,
    val elementId: String? = null,
    val questionId: String? = null,
    val optionId: String? = null,
    val optionLabel: String? = null,
    val itemId: String? = null,
    val value: String? = null,
    val clientAt: String,
    val createdAt: Long,
)

/** What the user chose for a question; persisted per (slateId, questionId) until the spec changes. */
@Serializable
data class AnsweredChoice(
    val questionId: String,
    val optionId: String? = null,
    val optionLabel: String? = null,
    val value: String? = null,
    val at: Long,
)

@Serializable
data class QueueSnapshot(
    val queue: List<PendingInteraction> = emptyList(),
    val answered: Map<String, AnsweredChoice> = emptyMap(), // key: "$slateId/$questionId"
) {
    companion object {
        fun answeredKey(slateId: String, questionId: String): String = "$slateId/$questionId"
    }
}

/**
 * Durable pending-interaction queue + per-question answered markers, persisted
 * as JSON in DataStore (NEVER pipe-delimited strings). Survives process death:
 * entries are removed ONLY on a confirmed 2xx (see InteractionManager).
 */
class QueueStore(context: Context, name: String = DEFAULT_NAME) {

    private val dataStore: DataStore<QueueSnapshot> =
        Stores.json(
            file = Stores.file(context, name, ".json"),
            cache = stores,
            serializer = JsonDataStoreSerializer(QueueSnapshot.serializer(), QueueSnapshot()),
            default = QueueSnapshot(),
        )

    val snapshot: Flow<QueueSnapshot> = dataStore.data

    /** Queue entries in tap order. */
    val queueFlow: Flow<List<PendingInteraction>> = snapshot.map { it.queue }

    suspend fun enqueue(entry: PendingInteraction) = enqueueAll(listOf(entry))

    suspend fun enqueueAll(entries: List<PendingInteraction>) {
        if (entries.isEmpty()) return
        dataStore.updateData { s -> s.copy(queue = s.queue + entries) }
    }

    /** Remove exactly these seqs — called only after a confirmed 2xx (or a deliberate 4xx drop). */
    suspend fun removeAll(seqs: Collection<String>) {
        if (seqs.isEmpty()) return
        dataStore.updateData { s ->
            s.copy(queue = s.queue.filterNot { it.seq in seqs })
        }
    }

    suspend fun all(): List<PendingInteraction> = dataStore.data.first().queue

    suspend fun markAnswered(slateId: String, choice: AnsweredChoice) {
        dataStore.updateData { s ->
            s.copy(answered = s.answered + (QueueSnapshot.answeredKey(slateId, choice.questionId) to choice))
        }
    }

    suspend fun answeredFor(slateId: String): Map<String, AnsweredChoice> =
        dataStore.data.first().answered.filterKeys { it.startsWith("$slateId/") }
            .mapKeys { it.key.removePrefix("$slateId/") }

    suspend fun answeredForAllSlates(): Map<String, AnsweredChoice> =
        dataStore.data.first().answered

    /** Clear answered markers for a slate — called when its spec changed server-side. */
    suspend fun clearAnswered(slateId: String) {
        dataStore.updateData { s ->
            s.copy(answered = s.answered.filterKeys { !it.startsWith("$slateId/") })
        }
    }

    /** Keep answered markers only for questions still present in the re-pushed spec
     *  (a nightly refresh that edits one row must not re-open every question). */
    suspend fun retainAnswered(slateId: String, keepQuestionIds: Collection<String>) {
        dataStore.updateData { s ->
            val prefix = "$slateId/"
            s.copy(answered = s.answered.filterKeys { key ->
                !key.startsWith(prefix) || key.removePrefix(prefix) in keepQuestionIds
            })
        }
    }

    /** Blocking read for widget render paths. */
    fun snapshotBlocking(): QueueSnapshot = kotlinx.coroutines.runBlocking { dataStore.data.first() }

    companion object {
        const val DEFAULT_NAME = "slate_queue"
        private val stores = java.util.concurrent.ConcurrentHashMap<String, DataStore<QueueSnapshot>>()
    }
}
