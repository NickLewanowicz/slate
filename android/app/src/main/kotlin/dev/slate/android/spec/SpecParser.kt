package dev.slate.android.spec

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Single entry point for decoding spec envelopes (schema/slate.schema.json).
 * Lenient on unknown content (unknown elements/tones/styles degrade),
 * strict on structural breakage (typed [SpecParseException]).
 */
class SpecParser(json: Json = defaultJson) {

    private val json: Json = json

    /** Parse a spec envelope. Throws [SpecParseException] on malformed JSON. */
    fun parse(raw: String): SlateSpec = try {
        json.decodeFromString(SlateSpec.serializer(), raw)
    } catch (e: SerializationException) {
        throw SpecParseException("Spec is not a valid Slate v2 envelope: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw SpecParseException("Spec JSON was structurally invalid: ${e.message}", e)
    }

    /** Parse or return null (never throws) — used by widget paths that must never crash. */
    fun parseOrNull(raw: String): SlateSpec? = runCatching { parse(raw) }.getOrNull()

    /** Re-serialize a parsed spec back to the canonical JSON form. */
    fun encode(spec: SlateSpec): String = json.encodeToString(SlateSpec.serializer(), spec)

    companion object {
        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
            isLenient = false
        }
    }
}

/** Typed parse failure so callers can surface a meaningful error, not a stack trace. */
class SpecParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
