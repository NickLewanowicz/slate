package dev.slate.android.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.data.CacheStore
import dev.slate.android.data.CachedSlate
import dev.slate.android.data.InteractionKind
import dev.slate.android.data.QueueStore
import dev.slate.android.interact.InteractionManager
import dev.slate.android.spec.SpecParser
import dev.slate.android.spec.TodoListElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Widget tap dispatch: fill-in extras → InteractionManager → widget refresh +
 * expedited flush. Fakes record flush calls (no WorkManager in unit tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetTapHandlerTest {

    private class RecordingUpdater : WidgetUpdater {
        var calls = 0
        override suspend fun updateAll() {
            calls++
        }

        override suspend fun updateOne(appWidgetId: Int) {
            calls += 10
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore
    private lateinit var interactions: InteractionManager
    private lateinit var updater: RecordingUpdater
    private var flushCalls = 0
    private lateinit var handler: WidgetTapHandler

    private val parser = SpecParser()

    private val specJson = """{"id":"home","version":2,"title":"T","children":[
        {"type":"todoList","id":"focus","items":[{"id":"doors","label":"Lock"}]},
        {"type":"question","id":"q1","prompt":"?","options":[{"id":"yes","label":"Yes"},{"id":"no","label":"No"}]}]}"""

    @Before
    fun setUp() = runTest {
        val name = "tap-${java.util.UUID.randomUUID()}"
        cache = CacheStore(context, name)
        queue = QueueStore(context, name)
        cache.put(CachedSlate("home", specJson, "h", "h", 1L))
        interactions = InteractionManager(queue, cache, parser)
        updater = RecordingUpdater()
        flushCalls = 0
        handler = WidgetTapHandler(interactions, updater) { flushCalls++ }
    }

    @Test
    fun `todo tap toggles cache, refreshes widget, flushes`() = runTest {
        handler.handle(
            context,
            Intent(WidgetClicks.ACTION_WIDGET_TAP)
                .putExtra(WidgetClicks.EXTRA_KIND, WidgetClicks.KIND_TODO)
                .putExtra(WidgetClicks.EXTRA_SLATE_ID, "home")
                .putExtra(WidgetClicks.EXTRA_ELEMENT_ID, "focus")
                .putExtra(WidgetClicks.EXTRA_ITEM_ID, "doors"),
        )

        val spec = parser.parse(cache.get("home")!!.rawJson)
        assertTrue(
            spec.children.filterIsInstance<TodoListElement>()
                .single().items.first { it.id == "doors" }.checked
        )
        assertEquals(1, queue.all().size)
        assertEquals(1, updater.calls)
        assertEquals(1, flushCalls)
    }

    @Test
    fun `option tap records answered marker and queues answer`() = runTest {
        handler.handle(
            context,
            Intent(WidgetClicks.ACTION_WIDGET_TAP)
                .putExtra(WidgetClicks.EXTRA_KIND, WidgetClicks.KIND_OPTION)
                .putExtra(WidgetClicks.EXTRA_SLATE_ID, "home")
                .putExtra(WidgetClicks.EXTRA_ELEMENT_ID, "q1")
                .putExtra(WidgetClicks.EXTRA_OPTION_ID, "yes"),
        )

        assertEquals("yes", queue.answeredFor("home")["q1"]?.optionId)
        val entry = queue.all().single()
        assertEquals(InteractionKind.answer, entry.kind)
        assertEquals("Yes", entry.optionLabel)
        assertEquals(1, flushCalls)
    }

    @Test
    fun `missing or unknown extras are ignored safely`() = runTest {
        handler.handle(context, Intent(WidgetClicks.ACTION_WIDGET_TAP))
        assertTrue(queue.all().isEmpty())
        handler.handle(
            context,
            Intent(WidgetClicks.ACTION_WIDGET_TAP).putExtra(WidgetClicks.EXTRA_KIND, "unknown"),
        )
        assertTrue(queue.all().isEmpty())
        assertEquals(0, flushCalls)
    }

    @Test
    fun `fill-in extras round trip through WidgetClicks`() {
        val clicks = WidgetClicks(context)
        val fill = clicks.todoFillIn("home", "focus", "doors")
        assertEquals("home", fill.getStringExtra(WidgetClicks.EXTRA_SLATE_ID))
        assertEquals("focus", fill.getStringExtra(WidgetClicks.EXTRA_ELEMENT_ID))
        assertEquals("doors", fill.getStringExtra(WidgetClicks.EXTRA_ITEM_ID))
        val option = clicks.optionFillIn("home", "q1", "yes")
        assertEquals("q1", option.getStringExtra(WidgetClicks.EXTRA_ELEMENT_ID))
        assertEquals("yes", option.getStringExtra(WidgetClicks.EXTRA_OPTION_ID))
        assertNotNull(clicks.broadcastTemplate)
        assertNotNull(clicks.detailDeepLink("home"))
    }
}
