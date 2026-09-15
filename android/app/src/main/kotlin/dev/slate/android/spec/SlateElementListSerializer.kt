package dev.slate.android.spec

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * Serializes List<SlateElement> with per-element fallback: any element that
 * fails to decode (unknown "type", or a known type with bad payload) becomes
 * an [UnknownElement] carrying the raw JSON, so one bad agent element can
 * never take down the whole surface.
 */
object SlateElementListSerializer : KSerializer<List<SlateElement>> {
    private val elementSerializer = SlateElement.serializer()

    override val descriptor: SerialDescriptor = listSerialDescriptor(elementSerializer.descriptor)

    override fun deserialize(decoder: Decoder): List<SlateElement> {
        val json = (decoder as? JsonDecoder)?.json
            ?: error("SlateElement list can only be decoded from JSON")
        val array: JsonArray = jsonDecoderArray(decoder)
        return array.map { rawElement ->
            try {
                json.decodeFromJsonElement(elementSerializer, rawElement)
            } catch (e: SerializationException) {
                toUnknown(rawElement)
            } catch (e: IllegalArgumentException) {
                toUnknown(rawElement)
            }
        }
    }

    private fun jsonDecoderArray(decoder: Decoder): JsonArray =
        (decoder as JsonDecoder).decodeJsonElement().jsonArray

    private fun toUnknown(rawElement: kotlinx.serialization.json.JsonElement): UnknownElement =
        UnknownElement(
            type = (rawElement as? JsonObject)
                ?.get("type")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?: "unknown",
            raw = rawElement as? JsonObject,
        )

    override fun serialize(encoder: Encoder, value: List<SlateElement>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("SlateElement list can only be encoded as JSON")
        val json = jsonEncoder.json
        val array = JsonArray(value.map { json.encodeToJsonElement(elementSerializer, it) })
        jsonEncoder.encodeJsonElement(array)
    }
}
