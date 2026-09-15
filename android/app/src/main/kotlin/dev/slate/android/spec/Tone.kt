package dev.slate.android.spec

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Semantic tone/style enums from schema/slate.schema.json. All are LENIENT: an
 * unknown value (e.g. a tone an agent invented in spec v3) decodes to the
 * documented default instead of failing the whole spec.
 */

@Serializable(with = ToneSerializer::class)
enum class Tone {
    @SerialName("ok") OK,
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
    @SerialName("info") INFO,
    @SerialName("neutral") NEUTRAL;

    companion object {
        val DEFAULT = NEUTRAL

        /** Severity used for aggregate header tone: error > warn > info > ok > neutral. */
        val SEVERITY: Map<Tone, Int> = mapOf(ERROR to 4, WARN to 3, INFO to 2, OK to 1, NEUTRAL to 0)

        fun mostSevere(a: Tone, b: Tone): Tone =
            if (SEVERITY.getValue(a) >= SEVERITY.getValue(b)) a else b
    }
}

object ToneSerializer : LenientEnumSerializer<Tone>(enumValues<Tone>(), Tone.DEFAULT) {
    override fun wireName(value: Tone): String = when (value) {
        Tone.OK -> "ok"
        Tone.WARN -> "warn"
        Tone.ERROR -> "error"
        Tone.INFO -> "info"
        Tone.NEUTRAL -> "neutral"
    }
}

@Serializable(with = SpecTextStyleSerializer::class)
enum class SpecTextStyle {
    @SerialName("title") TITLE,
    @SerialName("heading") HEADING,
    @SerialName("body") BODY,
    @SerialName("caption") CAPTION;

    companion object {
        val DEFAULT = BODY
    }
}

object SpecTextStyleSerializer : LenientEnumSerializer<SpecTextStyle>(enumValues<SpecTextStyle>(), SpecTextStyle.DEFAULT) {
    override fun wireName(value: SpecTextStyle): String = when (value) {
        SpecTextStyle.TITLE -> "title"
        SpecTextStyle.HEADING -> "heading"
        SpecTextStyle.BODY -> "body"
        SpecTextStyle.CAPTION -> "caption"
    }
}

@Serializable(with = AlignSerializer::class)
enum class Align {
    @SerialName("start") START,
    @SerialName("center") CENTER,
    @SerialName("end") END;

    companion object {
        val DEFAULT = START
    }
}

object AlignSerializer : LenientEnumSerializer<Align>(enumValues<Align>(), Align.DEFAULT) {
    override fun wireName(value: Align): String = when (value) {
        Align.START -> "start"
        Align.CENTER -> "center"
        Align.END -> "end"
    }
}

@Serializable(with = GravitySerializer::class)
enum class Gravity {
    @SerialName("start") START,
    @SerialName("center") CENTER,
    @SerialName("end") END,
    @SerialName("spaceBetween") SPACE_BETWEEN;

    companion object {
        val DEFAULT = START
    }
}

object GravitySerializer : LenientEnumSerializer<Gravity>(enumValues<Gravity>(), Gravity.DEFAULT) {
    override fun wireName(value: Gravity): String = when (value) {
        Gravity.START -> "start"
        Gravity.CENTER -> "center"
        Gravity.END -> "end"
        Gravity.SPACE_BETWEEN -> "spaceBetween"
    }
}

@Serializable(with = OptionStyleSerializer::class)
enum class OptionStyle {
    @SerialName("primary") PRIMARY,
    @SerialName("danger") DANGER,
    @SerialName("quiet") QUIET;

    companion object {
        val DEFAULT = QUIET
    }
}

object OptionStyleSerializer : LenientEnumSerializer<OptionStyle>(enumValues<OptionStyle>(), OptionStyle.DEFAULT) {
    override fun wireName(value: OptionStyle): String = when (value) {
        OptionStyle.PRIMARY -> "primary"
        OptionStyle.DANGER -> "danger"
        OptionStyle.QUIET -> "quiet"
    }
}

/**
 * Base for lenient enum serializers: unknown strings fall back to [fallback]
 * instead of throwing, so forward-compatible specs never crash the renderer.
 */
abstract class LenientEnumSerializer<E : Enum<E>>(
    private val values: Array<E>,
    private val fallback: E,
) : KSerializer<E> {

    final override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(fallback::class.java.simpleName, PrimitiveKind.STRING)

    /** Map an enum constant to its wire (JSON) name. */
    protected abstract fun wireName(value: E): String

    private fun parseOrNull(raw: String): E? = values.firstOrNull { wireName(it) == raw }

    final override fun deserialize(decoder: Decoder): E =
        parseOrNull(decoder.decodeString()) ?: fallback

    final override fun serialize(encoder: Encoder, value: E) {
        // Unrecognized values (future spec versions) re-serialize as the default.
        encoder.encodeString(if (values.contains(value)) wireName(value) else wireName(fallback))
    }
}
