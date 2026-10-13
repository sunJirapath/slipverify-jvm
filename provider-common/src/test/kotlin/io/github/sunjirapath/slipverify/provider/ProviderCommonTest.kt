package io.github.sunjirapath.slipverify.provider

import io.github.sunjirapath.slipverify.ReasonCode
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCommonTest {
    private val doc = Json.parseObject("""{"a":{"b":{"s":"text","n":12.50,"t":"1,234.5","bool":true,"nul":null,"blank":" "}}}""")

    @Test
    fun `json readers are null safe`() {
        assertEquals("text", Json.string(doc, "a", "b", "s"))
        assertEquals(BigDecimal("12.50"), Json.decimal(doc, "a", "b", "n"))
        assertEquals(BigDecimal("1234.5"), Json.decimal(doc, "a", "b", "t"))
        assertEquals(true, Json.bool(doc, "a", "b", "bool"))
        assertNull(Json.string(doc, "a", "b", "nul"))
        assertNull(Json.string(doc, "a", "b", "blank"))
        assertNull(Json.string(doc, "a", "missing", "s"))
        assertNull(Json.string(doc, "a", "b", "s", "deeper"))
        assertNull(Json.decimal(doc, "a", "b", "s"))
        assertNull(Json.bool(doc, "a", "b", "s"))
        assertNull(Json.obj(doc, "a", "b", "s"))
        assertTrue(Json.obj(doc, "a", "b") != null)
        assertNull(Json.string(null, "a"))
    }

    @Test
    fun `parseObject rejects non objects and garbage`() {
        for (bad in listOf(null, "", "  ", "[]", "42", "{", "nonsense")) assertNull(Json.parseObject(bad), bad)
    }

    @Test
    fun `thai time formats`() {
        val expected = Instant.parse("2026-10-05T03:15:00Z")
        for (t in listOf("2026-10-05T03:15:00Z", "2026-10-05T10:15:00+07:00", "2026-10-05T10:15:00", "2026-10-05 10:15:00")) {
            assertEquals(expected, ThaiTime.parse(t), t)
        }
        for (bad in listOf(null, "", "yesterday", "05/10/2026")) assertNull(ThaiTime.parse(bad), bad)
    }

    @Test
    fun `send maps connection failures without leaking the url`() {
        val http = ProviderHttp()
        val r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:1/secret-path?key=abc")).timeout(Duration.ofSeconds(2)).build())
        assertEquals(ReasonCode.PROVIDER_ERROR, r.transportFailure)
        assertFalse(r.toString().contains("secret"))
        assertFalse(r.isServerError)
    }

    private fun withServer(
        handler: (com.sun.net.httpserver.HttpExchange) -> Unit,
        block: (String) -> Unit,
    ) {
        val server =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { handler(it) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/x")
        } finally {
            server.stop(0)
        }
    }

    private fun get(
        url: String,
        timeout: Duration = Duration.ofSeconds(2),
    ) = ProviderHttp().send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).build())

    @Test
    fun `send returns status and body, flags 5xx`() {
        withServer({ ex ->
            val bytes = "hello".toByteArray()
            ex.sendResponseHeaders(503, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }) { url ->
            val r = get(url)
            assertEquals(503, r.status)
            assertEquals("hello", r.body)
            assertTrue(r.isServerError)
            assertNull(r.transportFailure)
        }
    }

    @Test
    fun `send maps a slow server to a timeout`() {
        withServer({ ex ->
            Thread.sleep(1500)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }) { url ->
            assertEquals(ReasonCode.PROVIDER_TIMEOUT, get(url, Duration.ofMillis(200)).transportFailure)
        }
    }

    @Test
    fun `send refuses absurdly large bodies`() {
        for (chunked in listOf(false, true)) {
            withServer({ ex ->
                val big = ByteArray(2_100_000) { 'a'.code.toByte() }
                ex.sendResponseHeaders(200, if (chunked) 0 else big.size.toLong())
                try {
                    ex.responseBody.use { it.write(big) }
                } catch (_: java.io.IOException) {
                    // The client intentionally cancels the download before the server finishes sending.
                }
            }) { url ->
                assertEquals(ReasonCode.PROVIDER_ERROR, get(url).transportFailure)
            }
        }
    }

    @Test
    fun `send keeps the interrupt flag`() {
        withServer({ ex ->
            Thread.sleep(800)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }) { url ->
            Thread.currentThread().interrupt()
            val r = get(url, Duration.ofSeconds(5))
            assertEquals(ReasonCode.PROVIDER_ERROR, r.transportFailure)
            assertTrue(Thread.interrupted())
        }
    }
}
