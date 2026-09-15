package dev.slate.android.api

import dev.slate.android.data.InteractionKind
import dev.slate.android.data.PendingInteraction
import dev.slate.android.spec.SpecParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Device-endpoint contract tests: paths, auth header, ETag, error envelope, batching. */
class SlateApiTest {

    private val seen = mutableListOf<String>()
    private var etag: String? = null
    private var lastStatus: HttpStatusCode = HttpStatusCode.OK
    private var lastBody: String = "{}"

    private fun api(): SlateApi = SlateApi(
        HttpClient(MockEngine { request ->
            seen.add(request.url.toString() + " | " + request.headers["Authorization"] + " | " + request.headers["If-None-Match"])
            val headers = if (etag != null) {
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.ETag to listOf("\"$etag\""),
                )
            } else {
                headersOf(HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()))
            }
            respond(lastBody, lastStatus, headers)
        }) {
            install(ContentNegotiation) { json(SpecParser.defaultJson) }
        }
    )

    private val cfg = ApiConfig("http://server.test", "key-1")

    @Test
    fun `ping hits GET api-ping with bearer auth`() = runTest {
        lastBody = """{"ok":true,"serverTime":"2026-09-14T20:00:00Z","version":"2.0.1"}"""
        val pong = api().ping(cfg)
        assertTrue(pong.ok)
        assertEquals("2.0.1", pong.version)
        assertTrue(seen.single().contains("http://server.test/api/ping"))
        assertTrue(seen.single().contains("Bearer key-1"))
    }

    @Test
    fun `listSlates parses summaries`() = runTest {
        lastBody = """{"slates":[{"slateId":"home","tone":"warn","updatedAt":"2026-01-01T00:00:00Z","contentHash":"h1"}]}"""
        val slates = api().listSlates(cfg)
        assertEquals(1, slates.size)
        assertEquals("home", slates[0].slateId)
        assertEquals(dev.slate.android.spec.Tone.WARN, slates[0].tone)
        assertEquals("h1", slates[0].contentHash)
        assertTrue(seen.single().contains("/api/device/slates"))
    }

    @Test
    fun `fetchSlate returns Fresh with ETag hash and parses spec`() = runTest {
        lastBody = """{"id":"home","version":2,"title":"T","children":[]}"""
        etag = "h9"
        when (val r = api().fetchSlate(cfg, "home", etag = null)) {
            is SlateFetchResult.Fresh -> {
                assertEquals("h9", r.contentHash)
                assertEquals("home", r.slate.id)
                assertTrue(r.rawJson.contains("\"T\""))
            }
            else -> throw AssertionError("expected Fresh")
        }
    }

    @Test
    fun `fetchSlate sends If-None-Match and handles 304`() = runTest {
        lastStatus = HttpStatusCode.NotModified
        lastBody = ""
        when (val r = api().fetchSlate(cfg, "home", etag = "h1")) {
            is SlateFetchResult.NotModified -> {}
            else -> throw AssertionError("expected NotModified")
        }
        assertTrue(seen.single().contains("\"h1\""))
    }

    @Test
    fun `fetchSlate without ETag header falls back to spec hash`() = runTest {
        lastBody = """{"id":"home","version":2,"title":"T","children":[]}"""
        val r = api().fetchSlate(cfg, "home", etag = null) as SlateFetchResult.Fresh
        assertFalse(r.contentHash.isBlank())
    }

    @Test
    fun `error envelope surfaces code message hint and status`() = runTest {
        lastStatus = HttpStatusCode.BadRequest
        lastBody = """{"error":{"code":"validation_error","message":"children[3].tone: invalid value","hint":"use ok|warn|error|info|neutral","status":400}}"""
        try {
            api().listSlates(cfg)
            throw AssertionError("expected SlateApiException")
        } catch (e: SlateApiException) {
            assertEquals(400, e.status)
            assertEquals("validation_error", e.code)
            assertTrue(e.isClientError)
            assertTrue(e.message!!.contains("hint"))
        }
    }

    @Test
    fun `non-json error body still produces a typed exception`() = runTest {
        lastStatus = HttpStatusCode.BadGateway
        lastBody = "gateway exploded"
        try {
            api().ping(cfg)
            throw AssertionError("expected SlateApiException")
        } catch (e: SlateApiException) {
            assertEquals(502, e.status)
            assertEquals("http_502", e.code)
            assertFalse(e.isClientError)
        }
    }

    @Test
    fun `postInteractions serializes the batch and parses ack`() = runTest {
        lastStatus = HttpStatusCode.Accepted
        lastBody = """{"accepted":2,"duplicate":1}"""
        val batch = listOf(
            PendingInteraction(
                seq = "s1", slateId = "home", kind = InteractionKind.answer,
                questionId = "q", optionId = "o", optionLabel = "Opt",
                clientAt = "2026-01-01T00:00:00Z", createdAt = 0,
            ),
            PendingInteraction(
                seq = "s2", slateId = "home", kind = InteractionKind.check,
                elementId = "l", itemId = "i", clientAt = "2026-01-01T00:00:00Z", createdAt = 0,
            ),
        )
        val ack = api().postInteractions(cfg, batch)
        assertEquals(2, ack.accepted)
        assertEquals(1, ack.duplicate)
        assertTrue(seen.single().contains("/api/device/interactions"))
    }

    @Test
    fun `ApiConfig validates completeness`() {
        assertNull(ApiConfig.fromSettings("", "key"))
        assertNull(ApiConfig.fromSettings("http://x", " "))
        assertNull(ApiConfig.fromSettings("nope", "k"))
        val cfg = ApiConfig.fromSettings("http://x/", "k")
        assertEquals("http://x", cfg!!.baseUrl)
        assertTrue(cfg.isComplete)
    }

    @Test
    fun `isNetworkError classifies failures`() {
        assertTrue(isNetworkError(IOException("down")))
        assertFalse(isNetworkError(SlateApiException(400, "code", "bad")))
        assertFalse(isNetworkError(IllegalStateException("no")))
        assertTrue("cause chain is inspected", isNetworkError(RuntimeException(IOException("down"))))
    }
}
