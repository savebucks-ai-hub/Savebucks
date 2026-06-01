package com.savebucks.worker.lib.redis

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(RedisManager::class.java)

/**
 * Manages a single Lettuce Redis connection shared across all queues.
 *
 * Lettuce auto-reconnects on disconnect — no manual retry loop needed.
 * Supports both plain redis:// and TLS rediss:// (Upstash) URLs.
 */
class RedisManager(private val redisUrl: String) {

    val client: RedisClient = RedisClient.create(RedisURI.create(redisUrl))
    val connection: StatefulRedisConnection<String, String>
    val cmd: RedisAsyncCommands<String, String>

    init {
        var lastErr: Exception? = null
        var conn: StatefulRedisConnection<String, String>? = null
        for (attempt in 1..10) {
            try {
                conn = client.connect()
                break
            } catch (e: Exception) {
                lastErr = e
                // Exponential backoff: 200ms, 400ms, 800ms, 1600ms … capped at 3000ms
                val delay = minOf(200L shl (attempt - 1), 3_000L)
                log.warn("Redis connect attempt $attempt/10 failed (${e.message}), retrying in ${delay}ms")
                Thread.sleep(delay)
            }
        }
        connection = conn ?: throw (lastErr ?: RuntimeException("Redis connection failed after 10 retries"))
        cmd = connection.async()
        log.info("Redis connected: ${maskUrl(redisUrl)}")
    }

    fun isConnected(): Boolean = connection.isOpen

    fun close() {
        runCatching { connection.close() }
        runCatching { client.shutdown() }
        log.info("Redis connection closed")
    }

    private fun maskUrl(url: String) = url.replace(Regex("://[^@]+@"), "://*****@")
}
