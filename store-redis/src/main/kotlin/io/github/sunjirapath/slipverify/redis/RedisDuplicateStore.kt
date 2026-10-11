package io.github.sunjirapath.slipverify.redis

import io.github.sunjirapath.slipverify.DuplicateStore
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisStringCommands
import java.io.Closeable
import java.time.Duration

/**
 * [DuplicateStore] on Redis: one `SET key 1 NX PX ttl` per check, so concurrent requests on any number of
 * application instances agree on who saw a slip first. Keys are hashes (see the verifier), never slip references.
 *
 * A Redis failure throws; the verifier then answers `ERROR` with `DUPLICATE_STORE_ERROR` rather than accepting a
 * slip it cannot prove unique. Thread-safe as long as the given commands are (Lettuce's are).
 */
public class RedisDuplicateStore private constructor(
    private val commands: RedisStringCommands<String, String>,
    private val onClose: Runnable?,
) : DuplicateStore,
    Closeable {
    /** Use with a command interface you manage yourself, for example `connection.sync()`. */
    public constructor(commands: RedisStringCommands<String, String>) : this(commands, null)

    override fun markIfAbsent(
        key: String,
        ttl: Duration,
    ): Boolean {
        require(!ttl.isNegative && !ttl.isZero) { "ttl must be positive" }
        val reply = commands.set(key, MARK, SetArgs.Builder.nx().px(ttl.toMillis()))
        // "OK" when the key was created, null when it already existed.
        return reply != null
    }

    /** Closes the client and connection opened by [connect]; does nothing for externally managed commands. */
    override fun close() {
        onClose?.run()
    }

    public companion object {
        private const val MARK = "1"

        /** Opens its own client from a URI such as `redis://:password@host:6379/0`. Close the store when done. */
        @JvmStatic
        public fun connect(redisUri: String): RedisDuplicateStore {
            val client = RedisClient.create(redisUri)
            val connection: StatefulRedisConnection<String, String> = client.connect()
            return RedisDuplicateStore(connection.sync(), {
                connection.close()
                client.shutdown()
            })
        }
    }
}
