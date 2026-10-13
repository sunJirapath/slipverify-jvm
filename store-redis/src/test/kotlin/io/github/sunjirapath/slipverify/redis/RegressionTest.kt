package io.github.sunjirapath.slipverify.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RegressionTest {
    private class Client(
        private val connection: StatefulRedisConnection<String, String>? = null,
        private val connectFailure: RuntimeException? = null,
        private val shutdownFailure: RuntimeException? = null,
    ) : RedisClient() {
        var shutdowns = 0

        override fun connect(): StatefulRedisConnection<String, String> = connectFailure?.let { throw it } ?: connection!!

        override fun shutdown() {
            shutdowns++
            super.shutdown()
            shutdownFailure?.let { throw it }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun connection(
        events: MutableList<String>,
        closeFailure: RuntimeException? = null,
    ): StatefulRedisConnection<String, String> {
        val commands = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RedisCommands::class.java)) { _, _, _ -> "OK" }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StatefulRedisConnection::class.java)) { _, method, _ ->
            when (method.name) {
                "sync" -> {
                    commands
                }

                "close" -> {
                    events += "close"
                    closeFailure?.let { throw it }
                    null
                }

                else -> {
                    error("unexpected connection call")
                }
            }
        } as StatefulRedisConnection<String, String>
    }

    @Test
    fun `connection failure shuts down the client and preserves the original error`() {
        val failure = IllegalStateException("synthetic connect failure")
        val client = Client(connectFailure = failure)
        assertSame(failure, assertFailsWith<IllegalStateException> { RedisDuplicateStore.connect(client) })
        assertEquals(1, client.shutdowns)
    }

    @Test
    fun `shutdown failure is suppressed onto the original connection failure`() {
        val failure = IllegalStateException("synthetic connect failure")
        val cleanup = IllegalArgumentException("synthetic cleanup failure")
        val client = Client(connectFailure = failure, shutdownFailure = cleanup)
        val caught = assertFailsWith<IllegalStateException> { RedisDuplicateStore.connect(client) }
        assertSame(failure, caught)
        assertEquals(listOf(cleanup), caught.suppressed.toList())
        assertEquals(1, client.shutdowns)
    }

    @Test
    fun `close preserves the connection failure and suppresses a shutdown failure`() {
        val failure = IllegalStateException("synthetic close failure")
        val cleanup = IllegalArgumentException("synthetic shutdown failure")
        val events = ArrayList<String>()
        val client = Client(connection(events, failure), shutdownFailure = cleanup)
        val store = RedisDuplicateStore.connect(client)
        val caught = assertFailsWith<IllegalStateException> { store.close() }
        assertSame(failure, caught)
        assertEquals(listOf(cleanup), caught.suppressed.toList())
        assertEquals(listOf("close"), events)
        assertEquals(1, client.shutdowns)
    }

    @Test
    fun `close shuts down the owned client even when the connection cannot close`() {
        for (failure in listOf(null, IllegalStateException("synthetic close failure"))) {
            val events = ArrayList<String>()
            val client = Client(connection(events, failure))
            val store = RedisDuplicateStore.connect(client)
            if (failure == null) {
                store.close()
            } else {
                assertSame(failure, assertFailsWith<IllegalStateException> { store.close() })
            }
            assertEquals(listOf("close"), events)
            assertEquals(1, client.shutdowns)
        }
    }
}
