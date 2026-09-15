package dev.slate.android.api

import dev.slate.android.data.PendingInteraction
import dev.slate.android.data.SettingsSanitizer
import dev.slate.android.spec.SpecParser
import dev.slate.android.spec.SlateSpec
import dev.slate.android.spec.Tone
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Connection target, resolved from settings at call time. */
data class ApiConfig(val baseUrl: String, val apiKey: String) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        fun fromSettings(serverUrl: String, apiKey: String): ApiConfig? {
            val url = SettingsSanitizer.normalizeServerUrl(serverUrl) ?: return null
            val key = SettingsSanitizer.normalizeApiKey(apiKey) ?: return null
            return ApiConfig(url, key)
        }
    }
}

/** GET /api/device/slates entry — cheap hash-diff list item. */
@Serializable
data class SlateSummary(
    @SerialName("slateId") val slateId: String,
    val tone: Tone = Tone.DEFAULT,
    val updatedAt: String? = null,
    val contentHash: String = "",
)

@Serializable
data class SlateListResponse(val slates: List<SlateSummary> = emptyList())

@Serializable
data class PingResponse(
    val ok: Boolean = false,
    val serverTime: String? = null,
    val version: String? = null,
)

@Serializable
data class InteractionsBatchBody(val interactions: List<WireInteraction>)

@Serializable
data class FlushAck(
    @SerialName("accepted") val accepted: Int = 0,
    val duplicate: Int = 0,
)

/**
 * Wire shape per schema/interactions.schema.json — EXACTLY the contract fields.
 * The local PendingInteraction carries extra bookkeeping (createdAt) that the
 * server's additionalProperties:false schema would reject with a 400.
 */
@Serializable
data class WireInteraction(
    val seq: String,
    val slateId: String,
    val kind: String,
    val elementId: String? = null,
    val questionId: String? = null,
    val optionId: String? = null,
    val optionLabel: String? = null,
    val itemId: String? = null,
    val value: String? = null,
    val clientAt: String? = null,
)

/** The frozen error envelope: { error: { code, message, hint, status } }. */
@Serializable
data class ApiErrorEnvelope(val error: ApiError? = null)

@Serializable
data class ApiError(
    val code: String = "unknown",
    val message: String = "Unknown error",
    val hint: String? = null,
    val status: Int = 0,
)

/** Typed API failure carrying the parsed error envelope. */
class SlateApiException(
    val status: Int,
    val code: String,
    message: String,
    val hint: String? = null,
) : Exception("[$status/$code] $message${hint?.let { " — hint: $it" } ?: ""}") {
    val isClientError: Boolean get() = status in 400..499

    companion object {
        fun from(status: Int, bodyText: String): SlateApiException {
            val envelope = runCatching {
                SpecParser.defaultJson.decodeFromString(ApiErrorEnvelope.serializer(), bodyText)
            }.getOrNull()
            val err = envelope?.error
            return SlateApiException(
                status = status,
                code = err?.code ?: "http_$status",
                message = err?.message ?: bodyText.take(200).ifBlank { "HTTP $status" },
                hint = err?.hint,
            )
        }
    }
}

/** Result of GET /api/device/slates/{id} with If-None-Match. */
sealed class SlateFetchResult {
    data class Fresh(val slate: SlateSpec, val rawJson: String, val contentHash: String) : SlateFetchResult()
    data object NotModified : SlateFetchResult()
}

/**
 * Thin client over the frozen device endpoints (schema/api.md). All config is
 * explicit per call so tests can use MockEngine easily and the setup screen can
 * ping unsaved credentials. Bodies are (de)serialized with kotlinx.serialization
 * DIRECTLY — no ContentNegotiation dependency — so a bare client works anywhere
 * (MockEngine tests, widget process, ping-before-save).
 */
class SlateApi(private val client: HttpClient) {

    private val json = SpecParser.defaultJson

    suspend fun ping(config: ApiConfig): PingResponse =
        decodeBody(PingResponse.serializer()) {
            client.get("${config.baseUrl}/api/ping") {
                header("Authorization", "Bearer ${config.apiKey}")
            }
        }

    suspend fun listSlates(config: ApiConfig): List<SlateSummary> =
        decodeBody(SlateListResponse.serializer()) {
            client.get("${config.baseUrl}/api/device/slates") {
                header("Authorization", "Bearer ${config.apiKey}")
            }
        }.slates

    /** ETag-aware fetch: 200 → parsed spec + raw + hash; 304 → NotModified. */
    suspend fun fetchSlate(config: ApiConfig, slateId: String, etag: String?): SlateFetchResult {
        val response = client.get("${config.baseUrl}/api/device/slates/$slateId") {
            header("Authorization", "Bearer ${config.apiKey}")
            if (!etag.isNullOrBlank()) header("If-None-Match", "\"$etag\"")
        }
        return when {
            response.status == HttpStatusCode.NotModified -> SlateFetchResult.NotModified
            response.status.isSuccess() -> {
                val raw = response.bodyAsText()
                val spec = SpecParser().parse(raw)
                val hash = response.headers["ETag"]?.trim('"') ?: specHashFallback(spec)
                SlateFetchResult.Fresh(spec, raw, hash)
            }
            else -> throw SlateApiException.from(response.status.value, response.bodyAsText())
        }
    }

    /** POST /api/device/interactions — one batch; server is all-or-nothing per batch. */
    suspend fun postInteractions(config: ApiConfig, batch: List<PendingInteraction>): FlushAck =
        decodeBody(FlushAck.serializer()) {
            client.post("${config.baseUrl}/api/device/interactions") {
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        InteractionsBatchBody.serializer(),
                        InteractionsBatchBody(batch.map { it.toWire() }),
                    ),
                )
            }
        }

    internal fun PendingInteraction.toWire(): WireInteraction = WireInteraction(
        seq = seq,
        slateId = slateId,
        kind = kind.name, // enum names are the contract's lowercase kinds
        elementId = elementId,
        questionId = questionId,
        optionId = optionId,
        optionLabel = optionLabel,
        itemId = itemId,
        value = value,
        clientAt = clientAt,
    )

    /** unwrap status errors, then decode the body with the frozen JSON config. */
    private suspend fun <T> decodeBody(
        serializer: kotlinx.serialization.KSerializer<T>,
        block: suspend () -> HttpResponse,
    ): T {
        val response = block()
        if (!response.status.isSuccess()) {
            throw SlateApiException.from(response.status.value, response.bodyAsText())
        }
        val text = response.bodyAsText()
        return runCatching { json.decodeFromString(serializer, text) }.getOrElse { e ->
            throw SlateApiException(
                status = response.status.value,
                code = "decode_error",
                message = "Server response was not valid Slate JSON: ${e.message ?: "decode failed"}",
            )
        }
    }

    private fun specHashFallback(spec: SlateSpec): String = Integer.toHexString(spec.hashCode())
}

/** True when the failure looks like a network problem (offline, DNS, timeout). */
fun isNetworkError(e: Throwable): Boolean = when (e) {
    is SlateApiException -> false
    is kotlinx.coroutines.CancellationException -> false
    else -> e is java.io.IOException || e.cause?.let { isNetworkError(it) } ?: false
}
