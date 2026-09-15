package dev.slate.android.spec

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for the hand-written spec serializers: per-element fallback,
 * unknown-element round-trip with "type" stripping, lenient enum wire names,
 * and non-JSON decoder guards.
 */
class SpecSerializerBranchTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val parser = SpecParser()

    private fun decodeList(raw: String): List<SlateElement> =
        json.decodeFromString(SlateElementListSerializer, raw)

    // ---- SlateElementListSerializer ----

    @Test
    fun `element list decodes known and unknown elements side by side`() {
        val raw = """[
            {"type":"text","text":"known"},
            {"type":"carousel","items":[]},
            {"type":"statusRow","label":"ok"}
        ]"""
        val elements = decodeList(raw)
        assertTrue(elements[0] is TextElement)
        assertTrue(elements[1] is UnknownElement)
        assertTrue(elements[2] is StatusRowElement)
    }

    @Test
    fun `a known element type with a bad payload degrades to unknown with its type name`() {
        // statusRow without the required "label" fails to decode but keeps "statusRow"
        val elements = decodeList("""[{"type":"statusRow","value":"no label"}]""")
        val unknown = elements.single() as UnknownElement
        assertEquals("statusRow", unknown.typeName)
        assertEquals(null, unknown.id)
    }

    @Test
    fun `non-object children fall back to the unknown type name`() {
        val elements = decodeList("""["just a string"]""")
        val unknown = elements.single() as UnknownElement
        assertEquals("unknown", unknown.typeName)
        assertNull(unknown.raw)
    }

    @Test
    fun `encoding an element list round-trips through raw json`() {
        val elements: List<SlateElement> = listOf(
            TextElement(text = "a"),
            UnknownElement(type = "carousel"),
        )
        val encoded = json.encodeToString(SlateElementListSerializer, elements)
        val decoded = json.decodeFromString(SlateElementListSerializer, encoded)
        assertEquals(2, decoded.size)
        assertTrue(decoded[1] is UnknownElement)
        assertEquals("carousel", (decoded[1] as UnknownElement).typeName)
    }

    // ---- UnknownElementSerializer ----

    @Test
    fun `unknown element without an id exposes null id`() {
        val unknown = UnknownElement(type = "carousel", raw = null)
        assertNull(unknown.id)
        val withId = UnknownElement(type = "carousel", raw = Json.parseToJsonElement("""{"type":"carousel","id":"c1"}""") as kotlinx.serialization.json.JsonObject)
        assertEquals("c1", withId.id)
    }

    @Test
    fun `unknown element keeps its id from raw json`() {
        val raw = """{"id":"kids","type":"carousel","title":"x"}"""
        val unknown = decodeList("[$raw]").single() as UnknownElement
        assertEquals("kids", unknown.id)
    }

    // ---- lenient enums ----

    @Test
    fun `align falls back to start for unknown values`() {
        val spec = parser.parse(
            """{"id":"x","version":2,"children":[
               {"type":"column","align":"diagonal","children":[]}]}""",
        )
        val col = spec.children.single() as ColumnElement
        assertEquals(Align.START, col.align)
    }

    @Test
    fun `enum serializers emit wire names for every constant`() {
        Tone.entries.forEach { tone ->
            assertEquals(tone.name.lowercase(), json.encodeToString(ToneSerializer, tone).trim('"'))
        }
        SpecTextStyle.entries.forEach { style ->
            assertEquals(style.name.lowercase(), json.encodeToString(SpecTextStyleSerializer, style).trim('"'))
        }
        assertEquals("spaceBetween", json.encodeToString(GravitySerializer, Gravity.SPACE_BETWEEN).trim('"'))
        assertEquals("primary", json.encodeToString(OptionStyleSerializer, OptionStyle.PRIMARY).trim('"'))
        assertEquals("end", json.encodeToString(AlignSerializer, Align.END).trim('"'))
    }
}
