package dev.slate.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Durable queue semantics: JSON on disk, entries leave ONLY via removeAll
 * (2xx confirmations / deliberate 4xx drops).
 *
 * Note on "process death" simulation: DataStore forbids two live instances for
 * the same file in one process, so we prove durability by asserting the JSON
 * FILE content (what a fresh process would read) plus re-reading via the
 * per-file singleton instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingQueueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Unique store name per test → full DataStore isolation. */
    private fun freshName() = "queue-${UUID.randomUUID()}"

    private fun queueFile(name: String): File = File(context.filesDir, "$name.json")

    private fun entry(seq: String = UUID.randomUUID().toString(), kind: InteractionKind = InteractionKind.check) =
        PendingInteraction(
            seq = seq,
            slateId = "home",
            kind = kind,
            elementId = "focus",
            itemId = "doors",
            clientAt = "2026-09-14T21:00:00Z",
            createdAt = 1L,
        )

    @Test
    fun `queue is JSON on disk - never pipe-delimited`() = runTest {
        val name = freshName()
        val store = QueueStore(context, name)
        store.enqueue(entry())
        val file = queueFile(name)
        assertTrue("queue file must exist", file.exists())
        val raw = file.readText()
        assertTrue("queue file must be JSON", raw.trimStart().startsWith("{"))
        assertTrue(raw.contains("\"seq\""))
        assertFalse("pipe-delimited strings are forbidden", raw.contains("|"))
    }

    @Test
    fun `queue entries persist to disk (what a fresh process reads)`() = runTest {
        val name = freshName()
        QueueStore(context, name).enqueueAll(listOf(entry("seq-1"), entry("seq-2")))

        // What a restarted process would parse from disk:
        val snapshot = JsonDataStoreSerializer(QueueSnapshot.serializer(), QueueSnapshot())
            .readFrom(queueFile(name).inputStream())
        assertEquals(listOf("seq-1", "seq-2"), snapshot.queue.map { it.seq })
    }

    @Test
    fun `removeAll removes exactly the confirmed seqs`() = runTest {
        val store = QueueStore(context, freshName())
        store.enqueueAll(listOf(entry("a"), entry("b"), entry("c")))
        store.removeAll(setOf("b"))
        assertEquals(listOf("a", "c"), store.all().map { it.seq })
    }

    @Test
    fun `only-remove-on-2xx is honored by never auto-removing on enqueue`() = runTest {
        val store = QueueStore(context, freshName())
        repeat(3) { store.enqueue(entry()) }
        assertEquals(3, store.all().size)
        store.enqueue(entry())
        assertEquals(4, store.all().size)
    }

    @Test
    fun `seq uniqueness from uuid factory`() {
        val seqs = (1..100).map { entry().seq }.toSet()
        assertEquals(100, seqs.size)
    }

    @Test
    fun `answered markers persist per slate and clear on spec change`() = runTest {
        val store = QueueStore(context, freshName())
        store.markAnswered("home", AnsweredChoice("q-fail", optionId = "retry", optionLabel = "Retry", at = 1))
        store.markAnswered("other", AnsweredChoice("q-x", value = "hello", at = 2))
        assertEquals("retry", store.answeredFor("home")["q-fail"]?.optionId)
        assertEquals("hello", store.answeredFor("other")["q-x"]?.value)

        store.clearAnswered("home")
        assertFalse(store.answeredFor("home").containsKey("q-fail"))
        assertTrue("other slate untouched", store.answeredFor("other").containsKey("q-x"))
    }

    @Test
    fun `answered state persists to disk`() = runTest {
        val name = freshName()
        QueueStore(context, name).markAnswered("home", AnsweredChoice("q", optionId = "o1", at = 3))
        val snapshot = JsonDataStoreSerializer(QueueSnapshot.serializer(), QueueSnapshot())
            .readFrom(queueFile(name).inputStream())
        assertEquals("o1", snapshot.answered[QueueSnapshot.answeredKey("home", "q")]?.optionId)
    }

    @Test
    fun `corrupted queue file falls back to default snapshot`() = runTest {
        val name = freshName()
        queueFile(name).writeText("{ garbage not json")
        val store = QueueStore(context, name)
        assertTrue("corruption falls back to empty queue", store.all().isEmpty())
    }

    @Test
    fun `same-file instances are shared not duplicated`() = runTest {
        // Constructing twice on the same name must not throw
        // ("multiple DataStores active for the same file").
        val name = freshName()
        QueueStore(context, name).enqueue(entry("shared"))
        QueueStore(context, name).enqueue(entry("shared-2"))
        assertEquals(2, QueueStore(context, name).all().size)
    }


    @Test
    fun `retainAnswered keeps markers for surviving questions and other slates`() = runTest {
        val store = QueueStore(context, name = freshName())
        store.markAnswered("home", AnsweredChoice(questionId = "keep", optionId = "yes", at = 1))
        store.markAnswered("home", AnsweredChoice(questionId = "gone", optionId = "no", at = 2))
        store.markAnswered("other", AnsweredChoice(questionId = "q", optionId = "maybe", at = 3))

        store.retainAnswered("home", listOf("keep"))

        val kept = store.answeredForAllSlates()
        assertTrue("kept question survives", kept.containsKey("home/keep"))
        assertTrue("dropped question is gone", !kept.containsKey("home/gone"))
        assertTrue("other slates untouched", kept.containsKey("other/q"))
    }
}
