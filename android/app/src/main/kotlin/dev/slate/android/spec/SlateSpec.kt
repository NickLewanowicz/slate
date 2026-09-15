package dev.slate.android.spec

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/**
 * DTOs mirroring schema/slate.schema.json exactly (frozen contract).
 *
 * SlateElement is a sealed hierarchy discriminated by the JSON "type" field.
 * Unknown element types decode to [UnknownElement] (capturing the raw JSON)
 * instead of failing; renderers degrade them to a caption, never crash.
 */

@Serializable
data class SlateSpec(
    val id: String,
    val version: Int = 2,
    val title: String = "",
    val tone: Tone = Tone.DEFAULT,
    val updatedAt: String? = null,
    val expiresAt: String? = null,
    @Serializable(with = SlateElementListSerializer::class)
    val children: List<SlateElement> = emptyList(),
) {
    /** True when expiresAt (ISO-8601) is in the past relative to [nowEpochMs]. */
    fun isExpired(nowEpochMs: Long): Boolean {
        val expires = expiresAt ?: return false
        val epoch = parseIsoOrNull(expires) ?: return false
        return epoch <= nowEpochMs
    }

    companion object {
        /** Highest-severity tone among all descendants; falls back to the surface tone. */
        fun aggregateTone(spec: SlateSpec): Tone =
            spec.children.fold(spec.tone) { acc, el -> Tone.mostSevere(acc, el.aggregatedTone()) }

        /** Lenient ISO-8601 instant parsing; null when unparseable. */
        fun parseIsoOrNull(iso: String): Long? =
            runCatching { Instant.parse(iso).toEpochMilli() }
                .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
                .getOrNull()
    }
}

@Serializable
sealed class SlateElement {
    /** The "type" discriminator string as it appears in JSON. */
    abstract val typeName: String

    abstract val id: String?

    /** Tone used for aggregate header-tone computation. */
    open fun aggregatedTone(): Tone = Tone.DEFAULT

    /** This element plus all container descendants, in document order. */
    open fun descendants(): List<SlateElement> = listOf(this)
}

@Serializable
@SerialName("column")
data class ColumnElement(
    override val id: String? = null,
    @Serializable(with = SlateElementListSerializer::class)
    val children: List<SlateElement> = emptyList(),
    val gap: Int = 8,
    val padding: Int = 0,
    val align: Align = Align.DEFAULT,
) : SlateElement() {
    override val typeName: String = "column"
    override fun descendants(): List<SlateElement> = listOf(this) + children.flatMap { it.descendants() }
}

@Serializable
@SerialName("row")
data class RowElement(
    override val id: String? = null,
    @Serializable(with = SlateElementListSerializer::class)
    val children: List<SlateElement> = emptyList(),
    val gap: Int = 8,
    val gravity: Gravity = Gravity.DEFAULT,
) : SlateElement() {
    override val typeName: String = "row"
    override fun descendants(): List<SlateElement> = listOf(this) + children.flatMap { it.descendants() }
}

@Serializable
@SerialName("text")
data class TextElement(
    override val id: String? = null,
    val text: String,
    val style: SpecTextStyle = SpecTextStyle.DEFAULT,
    val tone: Tone = Tone.DEFAULT,
    val maxLines: Int? = null,
) : SlateElement() {
    override val typeName: String = "text"
    override fun aggregatedTone(): Tone = tone
}

@Serializable
@SerialName("statusRow")
data class StatusRowElement(
    override val id: String? = null,
    val icon: String = "•",
    val label: String,
    val value: String? = null,
    val tone: Tone = Tone.DEFAULT,
    val detail: String? = null,
) : SlateElement() {
    override val typeName: String = "statusRow"
    override fun aggregatedTone(): Tone = tone
}

@Serializable
data class TodoItem(
    val id: String,
    val label: String,
    val checked: Boolean = false,
    val tone: Tone = Tone.DEFAULT,
)

@Serializable
@SerialName("todoList")
data class TodoListElement(
    override val id: String,
    val items: List<TodoItem>,
) : SlateElement() {
    override val typeName: String = "todoList"
    override fun aggregatedTone(): Tone =
        items.fold(Tone.DEFAULT) { acc, item -> Tone.mostSevere(acc, item.tone) }
}

@Serializable
data class QuestionOption(
    val id: String,
    val label: String,
    val style: OptionStyle = OptionStyle.DEFAULT,
)

@Serializable
@SerialName("question")
data class QuestionElement(
    override val id: String,
    val prompt: String,
    val options: List<QuestionOption>,
    val allowText: Boolean = false,
    val placeholder: String = "Type a reply…",
) : SlateElement() {
    override val typeName: String = "question"
}

@Serializable
@SerialName("progress")
data class ProgressElement(
    override val id: String? = null,
    val value: Double? = null,
    val indeterminate: Boolean = false,
    val label: String? = null,
    val tone: Tone = Tone.DEFAULT,
) : SlateElement() {
    override val typeName: String = "progress"
    override fun aggregatedTone(): Tone = tone
}

@Serializable
@SerialName("divider")
data class DividerElement(
    override val id: String? = null,
    val inset: Boolean = false,
) : SlateElement() {
    override val typeName: String = "divider"
}

@Serializable
@SerialName("spacer")
data class SpacerElement(
    override val id: String? = null,
    val height: Int = 8,
) : SlateElement() {
    override val typeName: String = "spacer"
}

/**
 * Captures an element whose "type" we don't know (spec v3+ forward compat),
 * or a known-typed element whose required fields failed to decode.
 * [raw] keeps the original JSON object so it round-trips unchanged.
 */
@Serializable(with = UnknownElementSerializer::class)
data class UnknownElement(
    val type: String,
    val raw: JsonObject? = null,
) : SlateElement() {
    override val typeName: String get() = type
    override val id: String? get() = (raw?.get("id") as? JsonPrimitive)?.contentOrNull
}

object UnknownElementSerializer : KSerializer<UnknownElement> {
    // NOTE: deliberately minimal descriptor — the class writes raw JSON directly.
    // Declaring a "type" element here would collide with the sealed
    // classDiscriminator "type" during polymorphic encoding.
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("UnknownElement")

    override fun deserialize(decoder: Decoder): UnknownElement {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("UnknownElement can only be decoded from JSON")
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject ?: JsonObject(emptyMap())
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
        return UnknownElement(type = type, raw = obj)
    }

    override fun serialize(encoder: Encoder, value: UnknownElement) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("UnknownElement can only be encoded as JSON")
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                value.raw?.forEach { (k, v) -> if (k != "type") put(k, v) }
                put("type", JsonPrimitive(value.type))
            }
        )
    }
}
