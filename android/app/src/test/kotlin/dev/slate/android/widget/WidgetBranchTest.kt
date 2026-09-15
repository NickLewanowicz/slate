package dev.slate.android.widget

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.R
import dev.slate.android.data.CacheStore
import dev.slate.android.data.CachedSlate
import dev.slate.android.data.QueueStore
import dev.slate.android.interact.InteractionManager
import dev.slate.android.spec.SpecParser
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
 * Widget branch coverage beyond WidgetRendererTest: waiting/empty placeholders,
 * the todo ListView hidden when no collection is planned, and WidgetTapHandler's
 * guard branches for missing/unknown extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetBranchTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var cache: CacheStore
    private lateinit var queue: QueueStore
    private val parser = SpecParser()

    @Before
    fun setUp() = runTest {
        val name = "wbranch-${System.nanoTime()}"
        cache = CacheStore(context, "$name-cache")
        queue = QueueStore(context, name)
    }

    private fun render(
        bucket: SizeBucket,
        specJson: String?,
        preview: Boolean = true,
    ): FrameLayout {
        val cached = specJson?.let { CachedSlate("home", it, "h", "h", System.currentTimeMillis()) }
        val spec = cached?.let { parser.parseOrNull(it.rawJson) }
        val rv = WidgetRenderer.render(
            context = context,
            bucket = bucket,
            spec = spec,
            cached = cached,
            preview = preview,
        )
        val parent = FrameLayout(context)
        parent.addView(rv.apply(context, parent))
        return parent
    }

    private fun collectTexts(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView && view.visibility == View.VISIBLE) out += view.text?.toString() ?: ""
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) out += collectTexts(view.getChildAt(i))
        }
        return out
    }

    // ---- waiting / empty placeholders ----

    @Test
    fun `cached-but-unparsable spec renders the Slate waiting header with the empty body`() {
        val parent = render(SizeBucket.MEDIUM, specJson = "not json at all")
        assertEquals("Slate", parent.findViewById<TextView>(R.id.widget_title).text.toString())
        // cached != null → the "empty" personality body, not the loading spinner
        assertNotNull(parent.findViewById<TextView>(R.id.widget_empty_text))
    }

    @Test
    fun `no cache at all renders the loading placeholder`() {
        val parent = render(SizeBucket.MEDIUM, specJson = null)
        assertEquals("Slate", parent.findViewById<TextView>(R.id.widget_title).text.toString())
    }

    // ---- todo list visibility per bucket ----

    private val dashboardJson = """{"id":"dash","version":2,"title":"D","children":[
        {"type":"statusRow","label":"Next","value":"up"},
        {"type":"statusRow","label":"Budget","value":"78%"},
        {"type":"statusRow","label":"Third","value":"x"},
        {"type":"statusRow","label":"Fourth","value":"y"},
        {"type":"todoList","id":"focus","items":[
            {"id":"a","label":"one"},{"id":"b","label":"two"},{"id":"c","label":"three"}]}]}"""

    @Test
    fun `compact bucket keeps the todo list hidden and shows the status digest`() {
        val parent = render(SizeBucket.COMPACT, dashboardJson)
        val list = parent.findViewById<android.widget.ListView>(R.id.widget_todo_list)
        assertEquals(View.GONE, list.visibility)
        val body = collectTexts(parent.findViewById<View>(R.id.widget_body))
        assertTrue(body.contains("Next"))
        assertTrue("compact caps at 3 rows", !body.contains("Fourth"))
    }

    @Test
    fun `preview full render keeps the todo rows inline inside the body`() {
        val parent = render(SizeBucket.FULL, dashboardJson, preview = true)
        val body = collectTexts(parent.findViewById<View>(R.id.widget_body))
        assertTrue(body.contains("one"))
        assertTrue(body.contains("three"))
    }

    // ---- WidgetTapHandler guard branches ----

    private class RecordingUpdater : WidgetUpdater {
        var calls = 0
        override suspend fun updateAll() {
            calls++
        }

        override suspend fun updateOne(appWidgetId: Int) {
            calls += 10
        }
    }

    private fun tapIntent(vararg extras: Pair<String, String>): Intent =
        Intent(WidgetClicks.ACTION_WIDGET_TAP).apply {
            extras.forEach { (k, v) -> putExtra(k, v) }
        }

    @Test
    fun `todo tap without an item id is ignored`() = runTest {
        cache.put(CachedSlate("home", """{"id":"home","version":2,"title":"T","children":[]}""", "h", "h", 1L))
        var flushes = 0
        val handler = WidgetTapHandler(
            InteractionManager(queue, cache, parser),
            RecordingUpdater(),
        ) { flushes++ }

        handler.handle(context, tapIntent(WidgetClicks.EXTRA_KIND to WidgetClicks.KIND_TODO, WidgetClicks.EXTRA_SLATE_ID to "home", WidgetClicks.EXTRA_ELEMENT_ID to "list"))
        assertTrue(queue.all().isEmpty())
        assertEquals(0, flushes)

        handler.handle(context, tapIntent(WidgetClicks.EXTRA_KIND to WidgetClicks.KIND_TODO, WidgetClicks.EXTRA_SLATE_ID to "home", WidgetClicks.EXTRA_ITEM_ID to "item"))
        assertTrue(queue.all().isEmpty())
        assertEquals(0, flushes)
    }

    @Test
    fun `option tap with an unknown question or option is ignored`() = runTest {
        val specJson = """{"id":"home","version":2,"title":"T","children":[
            {"type":"question","id":"q1","prompt":"?","options":[{"id":"yes","label":"Yes"}]}]}"""
        cache.put(CachedSlate("home", specJson, "h", "h", 1L))
        var flushes = 0
        val handler = WidgetTapHandler(
            InteractionManager(queue, cache, parser),
            RecordingUpdater(),
        ) { flushes++ }

        // question not in the spec
        handler.handle(context, tapIntent(WidgetClicks.EXTRA_KIND to WidgetClicks.KIND_OPTION, WidgetClicks.EXTRA_SLATE_ID to "home", WidgetClicks.EXTRA_ELEMENT_ID to "missing", WidgetClicks.EXTRA_OPTION_ID to "yes"))
        assertTrue(queue.all().isEmpty())

        // option not offered
        handler.handle(context, tapIntent(WidgetClicks.EXTRA_KIND to WidgetClicks.KIND_OPTION, WidgetClicks.EXTRA_SLATE_ID to "home", WidgetClicks.EXTRA_ELEMENT_ID to "q1", WidgetClicks.EXTRA_OPTION_ID to "nope"))
        assertTrue(queue.all().isEmpty())

        // spec missing entirely
        handler.handle(context, tapIntent(WidgetClicks.EXTRA_KIND to WidgetClicks.KIND_OPTION, WidgetClicks.EXTRA_SLATE_ID to "ghost", WidgetClicks.EXTRA_ELEMENT_ID to "q1", WidgetClicks.EXTRA_OPTION_ID to "yes"))
        assertTrue(queue.all().isEmpty())

        assertEquals(0, flushes)
    }
}
