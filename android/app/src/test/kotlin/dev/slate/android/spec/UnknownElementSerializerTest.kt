package dev.slate.android.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct branches of UnknownElementSerializer (forward-compat capture format). */
class UnknownElementSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decoding an object without a type falls back to the unknown type name`() {
        val unknown = json.decodeFromString(UnknownElementSerializer, """{"id":"x","items":[]}""")
        assertEquals("unknown", unknown.typeName)
        assertNotNull(unknown.raw)
        assertEquals("x", unknown.id)
    }

    @Test
    fun `decoding a non-object element yields an empty raw payload`() {
        val unknown = json.decodeFromString(UnknownElementSerializer, """42""")
        assertEquals("unknown", unknown.typeName)
        assertEquals(JsonObject(emptyMap()), unknown.raw)
    }

    @Test
    fun `serializing without raw json emits just the type`() {
        val encoded = json.encodeToString(UnknownElementSerializer, UnknownElement(type = "carousel"))
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("carousel", (obj["type"] as JsonPrimitive).content)
        assertEquals(1, obj.size)
    }

    @Test
    fun `serializing with raw json strips the captured type and re-emits the canonical one`() {
        val raw = JsonObject(
            mapOf(
                "type" to JsonPrimitive("oldName"),
                "keep" to JsonPrimitive(1),
            ),
        )
        val encoded = json.encodeToString(UnknownElementSerializer, UnknownElement(type = "newName", raw = raw))
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("newName", (obj["type"] as JsonPrimitive).content)
        assertEquals(1, (obj["keep"] as JsonPrimitive).content.toInt())
        assertEquals(2, obj.size)
    }

    @Test
    fun `unknown elements round-trip through the spec`() {
        val parser = SpecParser()
        val spec = """{"id":"x","version":2,"children":[{"type":"mystery","depth":3}]}"""
        val first = parser.parse(spec)
        assertTrue(first.children.single() is UnknownElement)
        val second = parser.parse(parser.encode(first))
        assertEquals(first, second)
        assertNull(parser.parseOrNull("<not json>"))
        assertTrue(parser.parseOrNull(spec)!!.children.single().typeName == "mystery")
    }
}
