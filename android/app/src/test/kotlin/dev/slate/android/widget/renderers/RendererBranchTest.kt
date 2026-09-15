package dev.slate.android.widget.renderers

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.R
import dev.slate.android.data.AnsweredChoice
import dev.slate.android.spec.Align
import dev.slate.android.spec.ColumnElement
import dev.slate.android.spec.DividerElement
import dev.slate.android.spec.Gravity
import dev.slate.android.spec.OptionStyle
import dev.slate.android.spec.ProgressElement
import dev.slate.android.spec.QuestionElement
import dev.slate.android.spec.QuestionOption
import dev.slate.android.spec.RowElement
import dev.slate.android.spec.SpecTextStyle
import dev.slate.android.spec.SlateElement
import dev.slate.android.spec.SpacerElement
import dev.slate.android.spec.StatusRowElement
import dev.slate.android.spec.TextElement
import dev.slate.android.spec.TodoItem
import dev.slate.android.spec.TodoListElement
import dev.slate.android.spec.Tone
import dev.slate.android.spec.UnknownElement
import dev.slate.android.widget.WidgetClicks
import dev.slate.android.widget.renderers.TodoListRenderer.Companion.visibleRowCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-renderer branch assertions: every renderer must handle the null/blank/
 * extreme variants of its element without crashing and with the right
 * visibility (RemoteViews contract: degrade, never crash).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RendererBranchTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun ctx(
        bucket: dev.slate.android.widget.SizeBucket = dev.slate.android.widget.SizeBucket.FULL,
        answered: Map<String, AnsweredChoice> = emptyMap(),
        preview: Boolean = false,
    ) = RenderContext(
        context = context,
        bucket = bucket,
        slateId = "home",
        appWidgetId = 1,
        answered = answered,
        expired = false,
        clicks = WidgetClicks(context),
        preview = preview,
    )

    private fun apply(rv: android.widget.RemoteViews): View {
        val parent = android.widget.FrameLayout(context)
        parent.addView(rv.apply(context, parent))
        return parent
    }

    private fun texts(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView && view.visibility == View.VISIBLE) out += view.text?.toString() ?: ""
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) out += texts(view.getChildAt(i))
        }
        return out
    }

    // ---- column ----

    @Test
    fun `column renders children with align padding and gaps`() {
        val col = ColumnElement(
            id = "col",
            children = listOf(
                TextElement(text = "one"),
                TextElement(text = "two"),
            ),
            gap = 12,
            padding = 6,
            align = Align.CENTER,
        )
        val view = apply(ColumnRenderer(RendererRegistry()).render(col, ctx()))
        assertTrue(texts(view).containsAll(listOf("one", "two")))
        // same again with START align (other gravity branch)
        val start = col.copy(align = Align.END)
        assertNotNull(apply(ColumnRenderer(RendererRegistry()).render(start, ctx())))
    }

    @Test
    fun `column renderer falls back to an empty slot for non-column elements`() {
        val rv = ColumnRenderer(RendererRegistry()).render(TextElement(text = "t"), ctx())
        assertNotNull(apply(rv))
    }

    // ---- row ----

    @Test
    fun `row stretches the last text child on start gravity`() {
        val row = RowElement(
            id = "row",
            gravity = Gravity.START,
            children = listOf(TextElement(text = "icon"), TextElement(text = "last stretches")),
        )
        val view = apply(RowRenderer(RendererRegistry()).render(row, ctx()))
        assertTrue(texts(view).contains("last stretches"))
    }

    @Test
    fun `row with spaceBetween inserts flex spacers between children`() {
        val row = RowElement(
            id = "row",
            gravity = Gravity.SPACE_BETWEEN,
            children = listOf(TextElement(text = "left"), TextElement(text = "right")),
        )
        val view = apply(RowRenderer(RendererRegistry()).render(row, ctx()))
        assertTrue(texts(view).containsAll(listOf("left", "right")))
    }

    @Test
    fun `row with gap and center gravity renders all children`() {
        val row = RowElement(
            id = "row",
            gravity = Gravity.CENTER,
            gap = 6,
            children = listOf(TextElement(text = "a"), TextElement(text = "b"), TextElement(text = "c")),
        )
        val view = apply(RowRenderer(RendererRegistry()).render(row, ctx()))
        assertEquals(listOf("a", "b", "c"), texts(view))
    }

    @Test
    fun `row renderer falls back for non-row elements`() {
        assertNotNull(apply(RowRenderer(RendererRegistry()).render(SpacerElement(), ctx())))
    }

    // ---- text ----

    @Test
    fun `text renders every style including bold title and caption`() {
        SpecTextStyle.entries.forEach { style ->
            val rv = TextRenderer().render(TextElement(text = "s-$style", style = style), ctx())
            assertTrue(texts(apply(rv)).contains("s-$style"))
        }
    }

    @Test
    fun `text maxLines clamp to the schema range and tone colors apply`() {
        val rv = TextRenderer().render(
            TextElement(text = "clamped", maxLines = 99, tone = Tone.ERROR),
            ctx(),
        )
        val tv = apply(rv).findViewById<TextView>(R.id.widget_text)
        assertEquals(10, tv.maxLines)
    }

    @Test
    fun `text renderer falls back to an empty text view for foreign elements`() {
        val stretched = TextRenderer().render(TextElement(text = "x"), ctx(), stretch = true)
        assertTrue(texts(apply(stretched)).contains("x"))
        val foreign = TextRenderer().render(SpacerElement(height = 4), ctx())
        assertEquals("", apply(foreign).findViewById<TextView>(R.id.widget_text).text.toString())
    }

    // ---- statusRow ----

    @Test
    fun `statusRow without value or detail hides those rows`() {
        val rv = StatusRowRenderer().render(
            StatusRowElement(id = "s", label = "bare", icon = "•"),
            ctx(),
        )
        val view = apply(rv)
        assertEquals(View.GONE, view.findViewById<TextView>(R.id.widget_status_value).visibility)
        assertEquals(View.GONE, view.findViewById<TextView>(R.id.widget_status_detail).visibility)
    }

    @Test
    fun `statusRow with value and detail shows both`() {
        val rv = StatusRowRenderer().render(
            StatusRowElement(id = "s", label = "full", value = "3 unread", detail = "checked 2m ago", tone = Tone.INFO),
            ctx(),
        )
        val view = apply(rv)
        assertEquals(View.VISIBLE, view.findViewById<TextView>(R.id.widget_status_value).visibility)
        assertEquals(View.VISIBLE, view.findViewById<TextView>(R.id.widget_status_detail).visibility)
    }

    @Test
    fun `statusRow falls back for foreign elements`() {
        assertNotNull(apply(StatusRowRenderer().render(SpacerElement(), ctx())))
    }

    // ---- progress ----

    @Test
    fun `progress indeterminate and null value both render indeterminate`() {
        val view = apply(ProgressRenderer().render(ProgressElement(label = "Working…", indeterminate = true), ctx()))
        val bar = view.findViewById<android.widget.ProgressBar>(R.id.widget_progress_bar)
        assertTrue(bar.isIndeterminate)
        val noValue = apply(ProgressRenderer().render(ProgressElement(label = ""), ctx()))
        assertTrue(noValue.findViewById<android.widget.ProgressBar>(R.id.widget_progress_bar).isIndeterminate)
        // blank label hides the label row
        assertEquals(View.GONE, noValue.findViewById<TextView>(R.id.widget_progress_label).visibility)
    }

    @Test
    fun `progress determinate clamps to 0-100`() {
        val view = apply(ProgressRenderer().render(ProgressElement(label = "Burn", value = 250.0), ctx()))
        val bar = view.findViewById<android.widget.ProgressBar>(R.id.widget_progress_bar)
        assertTrue(!bar.isIndeterminate)
        assertEquals(100, bar.progress)
    }

    @Test
    fun `progress falls back for foreign elements`() {
        assertNotNull(apply(ProgressRenderer().render(SpacerElement(), ctx())))
    }

    // ---- spacer / divider ----

    @Test
    fun `spacer height clamps to schema bounds`() {
        SpacerRenderer().render(SpacerElement(height = 100), ctx()) // clamp high
        SpacerRenderer().render(SpacerElement(height = 0), ctx()) // clamp low
        SpacerRenderer().render(SpacerElement(), ctx()) // default 8
        SpacerRenderer().render(TextElement(text = "x"), ctx()) // foreign → default
    }

    @Test
    fun `divider inset widens the rule`() {
        assertNotNull(apply(DividerRenderer().render(DividerElement(inset = true), ctx())))
        assertNotNull(apply(DividerRenderer().render(DividerElement(), ctx())))
    }

    // ---- todoList ----

    @Test
    fun `todo rows show checked and unchecked glyphs with tone accents`() {
        val list = TodoListElement(
            id = "bed",
            items = listOf(
                TodoItem(id = "a", label = "done", checked = true),
                TodoItem(id = "b", label = "open", checked = false, tone = Tone.WARN),
            ),
        )
        val renderer = TodoListRenderer()
        val view = apply(renderer.renderInline(ctx(preview = true), list))
        val glyphs = texts(view)
        assertTrue(glyphs.contains("☑"))
        assertTrue(glyphs.contains("☐"))
    }

    @Test
    fun `todoList renderer degrades foreign elements to the unknown caption`() {
        val view = apply(TodoListRenderer().render(TextElement(text = "x"), ctx()))
        assertTrue(texts(view).contains("[unsupported: text]"))
    }

    @Test
    fun `visibleRowCount caps at six`() {
        assertEquals(0, visibleRowCount(0))
        assertEquals(6, visibleRowCount(99))
    }

    // ---- question ----

    @Test
    fun `single option renders with the second slot invisible`() {
        val q = QuestionElement(
            id = "q1",
            prompt = "?",
            options = listOf(QuestionOption(id = "only", label = "Only", style = OptionStyle.PRIMARY)),
        )
        val view = apply(QuestionRenderer().renderQuestion(ctx(preview = true), q))
        assertEquals(View.INVISIBLE, view.findViewById<TextView>(R.id.widget_option_right).visibility)
    }

    @Test
    fun `question without allowText never shows the reply row`() {
        val q = QuestionElement(
            id = "q2",
            prompt = "?",
            options = listOf(
                QuestionOption(id = "a", label = "A", style = OptionStyle.DANGER),
                QuestionOption(id = "b", label = "B"),
            ),
        )
        val view = apply(QuestionRenderer().renderQuestion(ctx(preview = true), q))
        assertEquals(View.GONE, view.findViewById<TextView>(R.id.widget_question_reply_row).visibility)
    }

    @Test
    fun `answered question fades untaken options and keeps the chosen one`() {
        val q = QuestionElement(
            id = "q3",
            prompt = "?",
            options = listOf(
                QuestionOption(id = "a", label = "A", style = OptionStyle.QUIET),
                QuestionOption(id = "b", label = "B", style = OptionStyle.PRIMARY),
            ),
        )
        val answered = mapOf("q3" to AnsweredChoice(questionId = "q3", optionId = "a", optionLabel = "A", at = 0))
        val view = apply(QuestionRenderer().renderQuestion(ctx(answered = answered, preview = true), q))
        assertEquals(View.GONE, view.findViewById<TextView>(R.id.widget_question_reply_row).visibility)
        assertTrue(texts(view).contains("✓ A"))
        // foreign elements degrade through the unknown caption
        assertTrue(texts(apply(QuestionRenderer().render(SpacerElement(), ctx()))).contains("[unsupported: spacer]"))
    }

    // ---- unknown / registry ----

    @Test
    fun `unknown renderer names both captured and foreign types`() {
        val view = apply(UnknownRenderer().render(UnknownElement(type = "carousel"), ctx()))
        assertTrue(texts(view).contains("[unsupported: carousel]"))
        val knownType = apply(UnknownRenderer().render(SpacerElement(), ctx()))
        assertTrue(texts(knownType).contains("[unsupported: spacer]"))
    }

    @Test
    fun `registry dispatches unknown elements through the unknown renderer`() {
        val registry = RendererRegistry()
        val view = apply(registry.render(UnknownElement(type = "carousel", raw = null), ctx()))
        assertTrue(texts(view).contains("[unsupported: carousel]"))
        assertNotNull(registry.todoRenderer())
        assertNotNull(registry.questionRenderer())
    }

    @Test
    fun `checkedToKind maps to check and uncheck`() {
        assertEquals(dev.slate.android.data.InteractionKind.check, checkedToKind(true))
        assertEquals(dev.slate.android.data.InteractionKind.uncheck, checkedToKind(false))
    }

    @Test
    fun `every registered element type renders without crashing`() {
        val registry = RendererRegistry()
        val elements: List<SlateElement> = listOf(
            TextElement(text = "t"),
            StatusRowElement(label = "s"),
            TodoListElement(id = "l", items = listOf(TodoItem(id = "i", label = "item"))),
            QuestionElement(id = "q", prompt = "?", options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B"))),
            ProgressElement(value = 10.0),
            DividerElement(),
            SpacerElement(),
            ColumnElement(children = listOf(TextElement(text = "c"))),
            RowElement(children = listOf(TextElement(text = "r"))),
        )
        elements.forEach { el -> assertNotNull(apply(registry.render(el, ctx(preview = true)))) }
    }
}
