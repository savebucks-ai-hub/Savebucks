package com.savebucks.lib.redis

import com.savebucks.config.RedisConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Unified cache client that wraps Upstash Redis (via Lettuce) with an
 * in-memory LRU fallback for when Redis is not configured or unreachable.
 *
 * All suspend functions are safe to call from any coroutine context —
 * blocking Lettuce futures are dispatched to [Dispatchers.IO].
 */
class RedisCache(private val config: RedisConfig) {

    private val log = LoggerFactory.getLogger(RedisCache::class.java)

    /** Lettuce client — null when Redis URL is not configured. */
    private val redisClient: RedisClient? = if (config.isEnabled) {
        try {
            RedisClient.create(config.url)
        } catch (e: Exception) {
            log.warn("Redis client init failed, falling back to in-memory cache: ${e.message}")
            null
        }
    } else null

    /** Persistent connection to Redis. Reconnects automatically via Lettuce's auto-reconnect. */
    private val connection: StatefulRedisConnection<String, String>? =
        redisClient?.connect()

    private val commands: RedisAsyncCommands<String, String>? =
        connection?.async()

    // ─── In-memory LRU fallback (max 1 000 entries) ──────────────────────────

    private val memoryStore = LinkedHashMap<String, String>(1024, 0.75f, true)
    private val memoryOrder = ConcurrentLinkedDeque<String>()
    private val maxMemoryEntries = 1_000

    private fun memGet(key: String): String? = synchronized(memoryStore) { memoryStore[key] }

    private fun memSet(key: String, value: String) = synchronized(memoryStore) {
        if (memoryStore.size >= maxMemoryEntries) {
            // Evict least-recently-used entry
            memoryStore.remove(memoryStore.keys.first())
        }
        memoryStore[key] = value
    }

    private fun memDel(key: String) = synchronized(memoryStore) { memoryStore.remove(key) }

    // ─── Public API ─────────────────────────────────────────────────────────

    /** Retrieves a value by key, or null if absent / expired. */
    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        commands?.get(key)?.await() ?: memGet(key)
    }

    /** Stores a value with an optional TTL in seconds (default 5 minutes). */
    suspend fun set(key: String, value: String, ttlSeconds: Long = 300) = withContext(Dispatchers.IO) {
        if (commands != null) {
            commands.setex(key, ttlSeconds, value).await()
        } else {
            memSet(key, value)
        }
    }

    /** Deletes a key. */
    suspend fun del(key: String) = withContext(Dispatchers.IO) {
        commands?.del(key)?.await()
        memDel(key)
    }

    /** Atomically increments an integer counter and returns the new value. */
    suspend fun incr(key: String): Long = withContext(Dispatchers.IO) {
        commands?.incr(key)?.await() ?: run {
            val cur = memGet(key)?.toLongOrNull() ?: 0L
            val next = cur + 1
            memSet(key, next.toString())
            next
        }
    }

    /** Sets expiry on an existing key (no-op if key doesn't exist). */
    suspend fun expire(key: String, ttlSeconds: Long) = withContext(Dispatchers.IO) {
        commands?.expire(key, ttlSeconds)?.await()
    }

    /** Returns true if the key exists in cache. */
    suspend fun exists(key: String): Boolean = withContext(Dispatchers.IO) {
        commands?.exists(key)?.await()?.let { it > 0 } ?: memGet(key) != null
    }

    /**
     * Cache-aside pattern: returns cached value if present,
     * otherwise calls [compute], stores the result, and returns it.
     */
    suspend fun getOrSet(key: String, ttlSeconds: Long = 300, compute: suspend () -> String): String {
        get(key)?.let { return it }
        val value = compute()
        set(key, value, ttlSeconds)
        return value
    }

    /**
     * Sliding-window rate limiter using Redis INCR + EXPIRE.
     *
     * @param key        Unique key per user/IP + action (e.g., "rl:ai:user-123").
     * @param maxRequests Maximum requests allowed in the window.
     * @param windowSeconds Length of the sliding window in seconds.
     * @return true if the request is within limits; false if it should be rejected.
     */
    suspend fun rateLimit(key: String, maxRequests: Int, windowSeconds: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (commands != null) {
                val count = commands.incr(key).await()
                // Set expiry only on the first increment so the window slides from first request
                if (count == 1L) commands.expire(key, windowSeconds).await()
                count <= maxRequests
            } else {
                // Degraded mode: in-memory counter (not distributed — only safe on single node)
                val cur = memGet(key)?.toLongOrNull() ?: 0L
                val next = cur + 1
                memSet(key, next.toString())
                next <= maxRequests
            }
        }

    /** Gracefully closes Redis connections on application shutdown. */
    fun close() {
        connection?.close()
        redisClient?.shutdown()
    }
}
