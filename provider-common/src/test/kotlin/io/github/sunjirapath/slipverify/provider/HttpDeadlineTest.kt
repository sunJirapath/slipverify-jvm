package io.github.sunjirapath.slipverify.provider

import io.github.sunjirapath.slipverify.ReasonCode
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpDeadlineTest {
    private fun stalledBody(block: (String, CountDownLatch, Future<Boolean>) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            val closed =
                pool.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 4000
                        val input = socket.getInputStream()
                        val reader = input.bufferedReader(Charsets.US_ASCII)
                        while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\n{".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        started.countDown()
                        input.read() == -1
                    }
                }
            try {
                block("http://localhost:${server.localPort}/", started, closed)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `deadline covers the body and cancels the transport before the server finishes`() {
        stalledBody { url, started, closed ->
            val before = System.nanoTime()
            val result = ProviderHttp().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(500)).build())
            val elapsedMillis = (System.nanoTime() - before) / 1_000_000
            assertTrue(started.await(1, TimeUnit.SECONDS), "the server must have sent headers and a partial body")
            assertEquals(ReasonCode.PROVIDER_TIMEOUT, result.transportFailure)
            assertFalse(result.notSent, "a body timeout may already have incurred a provider charge")
            assertTrue(elapsedMillis < 2000, "the 500ms budget took ${elapsedMillis}ms")
            assertTrue(closed.get(2, TimeUnit.SECONDS), "deadline must close the in-progress HTTP body on JDK 11 too")
        }
    }

    @Test
    fun `interruption during the body preserves the flag and cancels the transport`() {
        stalledBody { url, started, closed ->
            val result = AtomicReference<HttpResult>()
            val interrupted = AtomicReference<Boolean>()
            val worker =
                Thread {
                    result.set(ProviderHttp().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build()))
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            worker.start()
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS))
                worker.interrupt()
                worker.join(2000)
                assertFalse(worker.isAlive)
                assertEquals(ReasonCode.PROVIDER_ERROR, result.get().transportFailure)
                assertEquals(true, interrupted.get())
                assertTrue(closed.get(2, TimeUnit.SECONDS))
            } finally {
                worker.interrupt()
                worker.join(5000)
            }
        }
    }

    @Test
    fun `client remains usable for a normal request after a body deadline`() {
        val http = ProviderHttp()
        stalledBody { url, _, closed ->
            assertEquals(ReasonCode.PROVIDER_TIMEOUT, http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(500)).build()).transportFailure)
            assertTrue(closed.get(2, TimeUnit.SECONDS))
        }
        val server =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()
        try {
            val result = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/")).build())
            assertEquals(200, result.status)
            assertEquals("{}", result.body)
            assertNull(result.transportFailure)
        } finally {
            server.stop(0)
        }
    }
}
