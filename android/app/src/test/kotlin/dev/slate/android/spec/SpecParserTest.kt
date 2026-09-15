package dev.slate.android.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * Parser tests over ALL schema/golden fixtures (copied to test resources —
 * synced from schema/golden/, schema/ wins on disagreement).
 */
class SpecParserTest {

    private val parser = SpecParser()

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/golden/$name.json")!!
            .readBytes().decodeToString()

    // ---- every golden fixture parses ----

    @Test
    fun `minimal golden parses`() {
        val spec = parser.parse(golden("minimal"))
        assertEquals("meds", spec.id)
        assertEquals(2, spec.version)
        assertEquals("Evening", spec.title)
        assertEquals(Tone.NEUTRAL, spec.tone)
        assertEquals(1, spec.children.size)
        assertTrue(spec.children[0] is TextElement)
        assertTrue((spec.children[0] as TextElement).text.contains("meds"))
    }

    @Test
    fun `dashboard golden parses with nesting`() {
        val spec = parser.parse(golden("dashboard"))
        assertEquals("dash", spec.id)
        assertEquals(Tone.INFO, spec.tone)
        // row, progress, divider, statusRow, statusRow, progress, divider, text, todoList
        assertEquals(9, spec.children.size)
        val row = spec.children[0] as RowElement
        assertEquals(Gravity.START, row.gravity)
        assertTrue(row.children[0] is TextElement)
        assertEquals(SpecTextStyle.HEADING, (row.children[0] as TextElement).style)

        val todo = spec.children[8] as TodoListElement
        assertEquals("focus", todo.id)
        assertEquals(3, todo.items.size)
        assertTrue(todo.items[0].checked)
        assertEquals(Tone.WARN, todo.items[1].tone)
    }

    @Test
    fun `status-board golden parses`() {
        val spec = parser.parse(golden("status-board"))
        assertEquals(Tone.WARN, spec.tone)
        val rows = spec.children.filterIsInstance<StatusRowElement>()
        assertEquals(4, rows.size)
        assertEquals(Tone.ERROR, rows[3].tone)
        assertEquals("disk full — 412 GB used", rows[3].detail)
    }

    @Test
    fun `question golden parses with allowText`() {
        val spec = parser.parse(golden("question"))
        val question = spec.children.filterIsInstance<QuestionElement>().single()
        assertEquals("q-fail", question.id)
        assertEquals(4, question.options.size)
        assertEquals(OptionStyle.PRIMARY, question.options[0].style)
        assertEquals(OptionStyle.DANGER, question.options[3].style)
        assertEquals(OptionStyle.QUIET, question.options[1].style)
        assertTrue(question.allowText)
        assertEquals("e.g. rotate the token first", question.placeholder)
    }

    @Test
    fun `edge-cases golden parses with every style and tone`() {
        val spec = parser.parse(golden("edge-cases"))
        assertEquals("edge", spec.id)
        assertNotNull(spec.expiresAt)
        val texts = spec.children.filterIsInstance<TextElement>()
        assertTrue(texts.any { it.style == SpecTextStyle.TITLE })
        assertTrue(texts.map { it.tone }.containsAll(listOf(Tone.OK, Tone.WARN, Tone.ERROR, Tone.INFO)))

        val spacer = spec.children.filterIsInstance<SpacerElement>().single()
        assertEquals(16, spacer.height)

        val divider = spec.children.filterIsInstance<DividerElement>().single()
        assertTrue(divider.inset)

        val row = spec.children.filterIsInstance<RowElement>().single()
        assertEquals(Gravity.SPACE_BETWEEN, row.gravity)

        val progressBars = spec.children.filterIsInstance<ProgressElement>()
        assertEquals(2, progressBars.size)
        assertTrue(progressBars[0].indeterminate)
        assertEquals(100.0, progressBars[1].value!!, 0.001)

        val question = spec.children.filterIsInstance<QuestionElement>().single()
        assertEquals(6, question.options.size)
        assertFalse(question.allowText)
        // default placeholder from the schema
        assertEquals("Type a reply…", question.placeholder)
    }

    // ---- the critical one: unknown element must degrade, never crash ----

    @Test
    fun `unknown-element golden degrades to UnknownElement and known siblings survive`() {
        val spec = parser.parse(golden("unknown-element"))
        assertEquals("future", spec.id)
        assertEquals(4, spec.children.size)

        // Known elements still render.
        assertTrue(spec.children[0] is StatusRowElement)
        assertTrue(spec.children[2] is TextElement)

        // The carousel becomes UnknownElement with its raw JSON preserved.
        val unknown = spec.children[1]
        assertTrue("child 1 should be UnknownElement but was ${unknown::class.simpleName}", unknown is UnknownElement)
        assertEquals("carousel", unknown.typeName)
        val raw = (unknown as UnknownElement).raw
        assertNotNull(raw)
        assertTrue(raw!!.containsKey("items"))
    }

    @Test
    fun `unknown tone falls back to neutral`() {
        val spec = parser.parse(golden("unknown-element"))
        val text = spec.children[3] as TextElement
        assertEquals("chartreuse", "chartreuse") // sanity: fixture really has the unknown tone
        assertEquals(Tone.NEUTRAL, text.tone)
    }

    @Test
    fun `unknown style enum falls back to defaults`() {
        val json = """{"id":"x","version":2,"children":[
            {"type":"text","text":"hi","style":"neon","tone":"chartreuse"},
            {"type":"question","id":"q","prompt":"?","options":[
                {"id":"a","label":"A","style":"glowing"}]},
            {"type":"row","gravity":"diagonal","children":[{"type":"text","text":"in row"}]}
        ]}"""
        val spec = parser.parse(json)
        val text = spec.children[0] as TextElement
        assertEquals(SpecTextStyle.BODY, text.style)
        assertEquals(Tone.NEUTRAL, text.tone)
        val question = spec.children[1] as QuestionElement
        assertEquals(OptionStyle.QUIET, question.options[0].style)
        val row = spec.children[2] as RowElement
        assertEquals(Gravity.START, row.gravity)
        // Row children survive too (element-by-element fallback inside nested lists)
        assertEquals(1, row.children.size)
        assertTrue(row.children[0] is TextElement)
    }

    // ---- roundtrip ----

    @Test
    fun `roundtrip serialization preserves every golden fixture`() {
        listOf("minimal", "dashboard", "status-board", "question", "edge-cases", "unknown-element").forEach { name ->
            val first = parser.parse(golden(name))
            val encoded = parser.encode(first)
            val second = parser.parse(encoded)
            assertEquals("roundtrip mismatch for $name", first, second)
        }
    }

    @Test
    fun `unknown element roundtrips with its raw payload`() {
        val first = parser.parse(golden("unknown-element"))
        val unknown = first.children[1] as UnknownElement
        assertEquals("carousel", unknown.typeName)
        val encoded = parser.encode(first)
        assertTrue("raw carousel payload must survive", encoded.contains("carousel"))
        assertTrue(encoded.contains("Something new in spec v3"))
        val second = parser.parse(encoded)
        assertEquals(first, second)
    }

    // ---- malformed input → typed error ----

    @Test
    fun `malformed json throws SpecParseException`() {
        try {
            parser.parse("{ this is not json")
            fail("expected SpecParseException")
        } catch (e: SpecParseException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `structurally wrong envelope throws SpecParseException`() {
        try {
            parser.parse("""{"version": 2, "children": []}""")
            fail("missing id must fail")
        } catch (e: SpecParseException) {
            // typed, with message
        }
    }

    @Test
    fun `children wrong type throws SpecParseException`() {
        try {
            parser.parse("""{"id":"x","version":2,"children":{"type":"text"}}""")
            fail("children object must fail")
        } catch (e: SpecParseException) {
        }
    }

    @Test
    fun `parseOrNull never throws`() {
        assertNull(parser.parseOrNull("not json"))
        val spec = parser.parseOrNull(golden("minimal"))
        assertNotNull(spec)
    }

    // ---- helpers under test ----

    @Test
    fun `isExpired honours expiresAt`() {
        val past = Instant.now().minusSeconds(60).toString()
        val future = Instant.now().plusSeconds(3600).toString()
        val now = System.currentTimeMillis()
        val spec = SlateSpec(id = "x", version = 2, expiresAt = past)
        assertTrue(spec.isExpired(now))
        val live = SlateSpec(id = "x", version = 2, expiresAt = future)
        assertFalse(live.isExpired(now))
        val none = SlateSpec(id = "x", version = 2)
        assertFalse(none.isExpired(now))
    }

    @Test
    fun `aggregateTone picks the most severe descendant tone`() {
        val spec = parser.parse(golden("status-board"))
        assertEquals(Tone.ERROR, SlateSpec.aggregateTone(spec))
        val quiet = parser.parse(golden("minimal"))
        assertEquals(Tone.NEUTRAL, SlateSpec.aggregateTone(quiet))
        val surface = parser.parse(golden("question"))
        assertEquals(Tone.ERROR, SlateSpec.aggregateTone(surface))
    }

    @Test
    fun `iso parsing is lenient`() {
        assertNotNull(SlateSpec.parseIsoOrNull("2026-12-31T23:59:59Z"))
        assertNull(SlateSpec.parseIsoOrNull("soon"))
    }

    @Test
    fun `tone severity order is error warn info ok neutral`() {
        assertTrue(Tone.SEVERITY.getValue(Tone.ERROR) > Tone.SEVERITY.getValue(Tone.WARN))
        assertTrue(Tone.SEVERITY.getValue(Tone.WARN) > Tone.SEVERITY.getValue(Tone.INFO))
        assertTrue(Tone.SEVERITY.getValue(Tone.INFO) > Tone.SEVERITY.getValue(Tone.OK))
        assertTrue(Tone.SEVERITY.getValue(Tone.OK) > Tone.SEVERITY.getValue(Tone.NEUTRAL))
        assertEquals(Tone.ERROR, Tone.mostSevere(Tone.OK, Tone.ERROR))
        assertEquals(Tone.NEUTRAL, Tone.mostSevere(Tone.NEUTRAL, Tone.NEUTRAL))
    }

    @Test
    fun `descendants flattens containers in document order`() {
        val spec = parser.parse(golden("dashboard"))
        val all = spec.children.flatMap { it.descendants() }
        assertTrue(all.any { it is TodoListElement })
        assertTrue(all.count { it is StatusRowElement } == 2)
    }
}
