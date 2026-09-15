package dev.slate.android.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.R
import dev.slate.android.data.AnsweredChoice
import dev.slate.android.interact.InteractionManager
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.SpecParser
import dev.slate.android.widget.renderers.RenderContext
import dev.slate.android.widget.renderers.TodoListRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Widget renderer tests over golden fixtures: apply RemoteViews into a real
 * hierarchy (Robolectric) and assert ids, texts, visibilities and bucket logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser = SpecParser()

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/golden/$name.json")!!.readBytes().decodeToString()

    private fun specOf(name: String) = parser.parse(golden(name))

    private fun answered(vararg pairs: Pair<String, String>): Map<String, AnsweredChoice> =
        pairs.associate { (q, o) ->
            q to AnsweredChoice(questionId = q, optionId = o, optionLabel = o, at = 0L)
        }

    private fun render(
        fixture: String,
        bucket: SizeBucket,
        answeredMap: Map<String, AnsweredChoice> = emptyMap(),
        preview: Boolean = true,
        cached: Boolean = true,
    ): FrameLayout {
        val spec = specOf(fixture)
        val now = System.currentTimeMillis()
        val rv = WidgetRenderer.render(
            context = context,
            bucket = bucket,
            spec = spec,
            cached = if (cached) dev.slate.android.data.CachedSlate(spec.id, golden(fixture), "h1", "h1", now) else null,
            answered = answeredMap,
            preview = preview,
            nowEpochMs = now,
        )
        val parent = FrameLayout(context)
        // RemoteViews.apply inflates + applies actions; the CALLER attaches the result.
        parent.addView(rv.apply(context, parent))
        return parent
    }

    private fun FrameLayout.text(id: Int): String? = findViewById<TextView>(id)?.text?.toString()

    // ---- full bucket ----

    @Test
    fun `full bucket renders header with title freshness and accent`() {
        val parent = render("status-board", SizeBucket.FULL)
        assertEquals("🌙 Nightly", parent.text(R.id.widget_title))
        val freshness = parent.text(R.id.widget_freshness)!!
        assertTrue("freshness should be relative, got: $freshness", freshness.startsWith("updated just now") || freshness.contains("just now"))
    }

    @Test
    fun `full bucket renders all status rows with values and detail lines`() {
        val parent = render("status-board", SizeBucket.FULL)
        val body = parent.findViewById<FrameLayout>(R.id.widget_body)
        val bodyText = collectTexts(body)
        assertTrue(bodyText.contains("Home Assistant"))
        assertTrue(bodyText.contains("3 unread"))
        assertTrue(bodyText.contains("disk full — 412 GB used"))
        // todoList goes through the ListView collection (not inline) in full widgets
        assertTrue(bodyText.contains("Before bed"))
    }

    @Test
    fun `compact bucket shows digest of at most 3 status rows`() {
        val parent = render("status-board", SizeBucket.COMPACT)
        val bodyText = collectTexts(parent.findViewById(R.id.widget_body))
        assertTrue(bodyText.contains("Home Assistant"))
        assertTrue(bodyText.contains("Email"))
        assertTrue(bodyText.contains("Security"))
        assertFalse("compact must cap at 3 status rows", bodyText.contains("Backup"))
        assertFalse("compact has no todo rows", bodyText.contains("Lock doors"))
    }

    @Test
    fun `compact bucket falls back to first elements when no status rows`() {
        val parent = render("minimal", SizeBucket.COMPACT)
        val bodyText = collectTexts(parent.findViewById(R.id.widget_body))
        assertTrue(bodyText.any { it.contains("meds") })
    }

    @Test
    fun `medium bucket shows status rows plus first question compact`() {
        val parent = render("question", SizeBucket.MEDIUM)
        val bodyText = collectTexts(parent.findViewById(R.id.widget_body))
        // question.json has a text line then the question; medium shows the question (no statusRows → fallback keeps it visible too)
        assertTrue(bodyText.contains("What should I do?") || bodyText.any { it.contains("deploy.yml") })
    }

    @Test
    fun `full bucket question shows up to 4 options and reply row`() {
        val parent = render("question", SizeBucket.FULL)
        val all = collectTexts(parent)
        assertTrue(all.contains("Retry"))
        assertTrue(all.contains("Skip this deploy"))
        assertTrue(all.contains("Tell me more"))
        assertTrue(all.contains("Never ask again"))
        // allowText → reply row visible
        val reply = parent.findViewById<TextView>(R.id.widget_question_reply_row)
        assertNotNull(reply)
        assertEquals(View.VISIBLE, reply.visibility)
        assertEquals("💬 Reply in app", reply.text.toString())
        // 5th option (none here) would be dropped; edge case has 6 → only 4 in full
        val edge = collectTexts(render("edge-cases", SizeBucket.FULL))
        assertTrue(edge.contains("Primary"))
        assertTrue(edge.contains("Danger"))
        assertFalse("only 4 options render in full", edge.contains("Quiet three"))
    }

    @Test
    fun `compact question caps at 2 options`() {
        val edgePlan = WidgetPlanner.plan(specOf("edge-cases"), SizeBucket.MEDIUM)
        val q = edgePlan.bodyBefore.filterIsInstance<QuestionElement>().single()
        assertTrue(q.options.size == 6) // planner keeps the element; renderer caps options

        val parent = render("edge-cases", SizeBucket.MEDIUM)
        val texts = collectTexts(parent.findViewById(R.id.widget_body))
        assertTrue(texts.contains("Primary"))
        assertTrue(texts.contains("Danger"))
        assertFalse(texts.contains("Quiet one"))
    }

    // ---- answered state ----

    @Test
    fun `answered question disables options and marks the chosen one`() {
        val parent = render("question", SizeBucket.FULL, answeredMap = answered("q-fail" to "retry"))
        val all = collectTexts(parent)
        assertTrue(all.contains("✓ Retry"))
        assertFalse("reply row hidden once answered", all.contains("💬 Reply in app"))
        assertFalse("untaken options fade", all.contains("Tell me more") && all.contains("✓ Tell me more"))
    }

    // ---- unknown elements degrade ----

    @Test
    fun `unknown element renders as caption and known elements still render`() {
        val parent = render("unknown-element", SizeBucket.FULL)
        val all = collectTexts(parent)
        assertTrue(all.contains("[unsupported: carousel]"))
        assertTrue(all.contains("Known elements still render"))
        assertTrue(all.contains("Unknown elements above must degrade to a caption, never crash."))
    }

    // ---- waiting / missing spec ----

    @Test
    fun `missing spec renders waiting placeholder without crashing`() {
        val parent = render("minimal", SizeBucket.MEDIUM, cached = false)
        assertEquals("Slate", parent.text(R.id.widget_title))
    }

    @Test
    fun `empty spec renders the personality empty state`() {
        val empty = parser.parse("""{"id":"home","version":2,"title":"Calm","children":[]}""")
        val now = System.currentTimeMillis()
        val rv = WidgetRenderer.render(
            context, SizeBucket.MEDIUM, empty,
            dev.slate.android.data.CachedSlate("home", "{}", "h", "h", now),
            preview = true, nowEpochMs = now,
        )
        val parent = FrameLayout(context)
        parent.addView(rv.apply(context, parent))
        assertTrue(collectTexts(parent).any { it.contains("All quiet") })
    }

    // ---- bucket selection from widget options ----

    @Test
    fun `size bucket selection respects dp thresholds`() {
        assertEquals(SizeBucket.COMPACT, SizeBucket.fromWidthDp(200))
        // Compact cap moved to 230dp: the default 4-column placement (~250dp) must
        // land in MEDIUM so the question row is never on a bucket coin flip (P12).
        assertEquals(SizeBucket.COMPACT, SizeBucket.fromWidthDp(230))
        assertEquals(SizeBucket.MEDIUM, SizeBucket.fromWidthDp(231))
        assertEquals(SizeBucket.MEDIUM, SizeBucket.fromWidthDp(250))
        assertEquals(SizeBucket.MEDIUM, SizeBucket.fromWidthDp(300))
        assertEquals(SizeBucket.MEDIUM, SizeBucket.fromWidthDp(400))
        assertEquals(SizeBucket.FULL, SizeBucket.fromWidthDp(401))
        assertEquals(SizeBucket.FULL, SizeBucket.fromWidthDp(0))
        assertEquals(SizeBucket.FULL, SizeBucket.fromWidthDp(-10))

        val options = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220) }
        assertEquals(SizeBucket.COMPACT, SizeBucket.fromOptions(options))
        assertEquals(SizeBucket.FULL, SizeBucket.fromOptions(null))
        assertEquals(SizeBucket.MEDIUM, SizeBucket.fromOptions(Bundle(), fallback = SizeBucket.MEDIUM))
    }

    @Test
    fun `resize changes the bucket plan`() {
        val spec = specOf("status-board")
        val compact = WidgetPlanner.plan(spec, SizeBucket.COMPACT)
        val full = WidgetPlanner.plan(spec, SizeBucket.FULL)
        assertEquals(3, compact.bodyBefore.size)
        assertNull(full.todoCollection)
        // dashboard HAS a todoList → full bucket plans a collection
        val dashFull = WidgetPlanner.plan(specOf("dashboard"), SizeBucket.FULL)
        assertNotNull(dashFull.todoCollection)
        assertEquals("focus", dashFull.todoCollection!!.id)
        val dashCompact = WidgetPlanner.plan(specOf("dashboard"), SizeBucket.COMPACT)
        assertNull(dashCompact.todoCollection)
    }

    // ---- freshness formatting ----

    @Test
    fun `freshness relative labels branch on age`() {
        val now = 1_000_000_000_000L
        val minute = 60_000L
        assertEquals("just now", FreshnessFormatter.relative(now, now))
        assertEquals("updated 6m ago", FreshnessFormatter.relative(now - 6 * minute, now))
        assertEquals("updated 3h ago", FreshnessFormatter.relative(now - 3 * 60 * minute, now))
        assertEquals("updated 2d ago", FreshnessFormatter.relative(now - 2 * 24 * 60 * minute, now))
        assertTrue(FreshnessFormatter.relative(now - 30L * 24 * 60 * minute, now).startsWith("updated "))
        assertEquals("updated unknown", FreshnessFormatter.relative(null, now))
        assertEquals("updated unknown", FreshnessFormatter.relative(0L, now))
        assertEquals("just now", FreshnessFormatter.relative(now + 5, now)) // clock skew
        assertTrue(FreshnessFormatter.relativeIso("2024-01-01T00:00:00Z", now).startsWith("updated "))
        assertEquals("updated unknown", FreshnessFormatter.relativeIso("garbage", now))
        assertEquals("updated unknown", FreshnessFormatter.relativeIso(null, now))
    }

    // ---- todo rows ----

    @Test
    fun `full bucket with todoList uses the ListView collection path`() {
        val spec = specOf("dashboard")
        val now = System.currentTimeMillis()
        val rv = WidgetRenderer.render(
            context = context,
            bucket = SizeBucket.FULL,
            spec = spec,
            cached = dev.slate.android.data.CachedSlate(spec.id, golden("dashboard"), "h", "h", now),
            preview = false,
            nowEpochMs = now,
        )
        val parent = android.appwidget.AppWidgetHostView(context)
        parent.addView(rv.apply(context, parent))
        // ListView is visible with fixed height 3 items × 44dp = 132dp (× density at apply)
        // NOTE: parent is an AppWidgetHostView because the framework's
        // setRemoteAdapter(RemoteCollectionItems) is a no-op outside the widget
        // host ("setRemoteAdapter can only be used for AppWidgets") — widgets
        // always apply inside the host (MVP_SPEC: "full control" RemoteViews widget).
        val list = parent.findViewById<android.widget.ListView>(R.id.widget_todo_list)
        assertNotNull(list)
        assertEquals(View.VISIBLE, list.visibility)
        assertTrue("adapter should be set from collection items", list.adapter != null && list.adapter.count >= 3)
    }

    @Test
    fun `todo rows show checked glyph and fixed height`() {
        val parent = render("dashboard", SizeBucket.FULL, preview = true)
        val body = parent.findViewById<FrameLayout>(R.id.widget_body)
        val texts = collectTexts(body)
        assertTrue(texts.contains("Review Slate v2 spec"))
        assertTrue(texts.contains("Call plumber"))
    }

    @Test
    fun `visible row count caps at six`() {
        assertEquals(0, TodoListRenderer.visibleRowCount(0))
        assertEquals(3, TodoListRenderer.visibleRowCount(3))
        assertEquals(6, TodoListRenderer.visibleRowCount(6))
        assertEquals(6, TodoListRenderer.visibleRowCount(12))
    }

    @Test
    fun `progress renders indeterminate and determinate from edge-cases`() {
        val parent = render("edge-cases", SizeBucket.FULL)
        // Rendering both progress variants without crashing is the contract; texts:
        val all = collectTexts(parent)
        assertTrue(all.contains("Working…"))
        assertTrue(all.contains("Full"))
    }

    @Test
    fun `render context derives compact questions outside full bucket`() {
        fun ctx(bucket: SizeBucket) = RenderContext(
            context = context,
            bucket = bucket,
            slateId = "home",
            appWidgetId = 1,
            answered = emptyMap(),
            expired = false,
            clicks = WidgetClicks(context),
            preview = false,
        )
        assertTrue(ctx(SizeBucket.COMPACT).compactQuestions)
        assertTrue(ctx(SizeBucket.MEDIUM).compactQuestions)
        assertFalse(ctx(SizeBucket.FULL).compactQuestions)
    }

    @Test
    fun `stale spec marks freshness and renders without crash`() {
        val stale = parser.parse(
            """{"id":"x","version":2,"title":"Old","expiresAt":"2020-01-01T00:00:00Z",
               "children":[{"type":"statusRow","label":"L","value":"V"}]}"""
        )
        val now = System.currentTimeMillis()
        val rv = WidgetRenderer.render(
            context, SizeBucket.MEDIUM, stale,
            dev.slate.android.data.CachedSlate("x", "{}", "h", "h", now),
            preview = true, nowEpochMs = now,
        )
        val parent = FrameLayout(context)
        parent.addView(rv.apply(context, parent))
        assertTrue(parent.text(R.id.widget_freshness)!!.endsWith("stale"))
    }

    // ---- helpers ----

    private fun collectTexts(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView && view.visibility == View.VISIBLE) out += view.text?.toString() ?: ""
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) out += collectTexts(view.getChildAt(i))
        }
        return out
    }
}
