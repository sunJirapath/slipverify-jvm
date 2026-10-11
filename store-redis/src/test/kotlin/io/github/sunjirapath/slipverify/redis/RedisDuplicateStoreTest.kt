package io.github.sunjirapath.slipverify.redis

import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisStringCommands
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisDuplicateStoreTest {
    /** Records SET calls and emulates NX in a map, since no Redis is needed to test our use of the API. */
    private class FakeRedis {
        val data = ConcurrentHashMap<String, String>()
        val args = ArrayList<SetArgs>()
        var fail = false

        @Suppress("UNCHECKED_CAST")
        val commands: RedisStringCommands<String, String> =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(RedisStringCommands::class.java),
            ) { _, method, a ->
                check(method.name == "set" && a.size == 3) { "unexpected call ${method.name}" }
                if (fail) throw io.lettuce.core.RedisException("connection refused")
                args += a[2] as SetArgs
                if (data.putIfAbsent(a[0] as String, a[1] as String) == null) "OK" else null
            } as RedisStringCommands<String, String>
    }

    @Test
    fun `first mark wins and later ones are duplicates`() {
        val redis = FakeRedis()
        val store = RedisDuplicateStore(redis.commands)
        assertTrue(store.markIfAbsent("slipverify:abc", Duration.ofDays(1)))
        assertFalse(store.markIfAbsent("slipverify:abc", Duration.ofDays(1)))
        assertTrue(store.markIfAbsent("slipverify:def", Duration.ofDays(1)))
    }

    @Test
    fun `uses SET NX with a millisecond ttl`() {
        val redis = FakeRedis()
        RedisDuplicateStore(redis.commands).markIfAbsent("k", Duration.ofMinutes(2))
        val cmd =
            io.lettuce.core.protocol
                .CommandArgs(io.lettuce.core.codec.StringCodec.UTF8)
        redis.args.single().build(cmd)
        val text = cmd.toCommandString()
        assertTrue(text.contains("NX"), text)
        assertTrue(text.contains("PX") && text.contains("120000"), text)
    }

    @Test
    fun `redis errors propagate so the verifier can fail closed`() {
        val redis = FakeRedis().also { it.fail = true }
        assertFailsWith<io.lettuce.core.RedisException> { RedisDuplicateStore(redis.commands).markIfAbsent("k", Duration.ofMinutes(1)) }
    }

    @Test
    fun `ttl must be positive and close is harmless without a client`() {
        val store = RedisDuplicateStore(FakeRedis().commands)
        assertFailsWith<IllegalArgumentException> { store.markIfAbsent("k", Duration.ZERO) }
        store.close()
    }

    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "REDIS_URL", matches = ".+")
    fun `live redis`() {
        RedisDuplicateStore.connect(System.getenv("REDIS_URL")).use { store ->
            val key = "slipverify:test:" + System.nanoTime()
            assertTrue(store.markIfAbsent(key, Duration.ofSeconds(5)))
            assertFalse(store.markIfAbsent(key, Duration.ofSeconds(5)))
        }
    }
}
