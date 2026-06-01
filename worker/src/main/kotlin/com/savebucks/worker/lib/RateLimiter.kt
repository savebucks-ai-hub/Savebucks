package com.savebucks.worker.lib

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(RateLimiter::class.java)

class TokenBucket(val maxTokens: Double, val refillRatePerMs: Double) {
    private var tokens: Double = maxTokens
    private var lastRefillTime: Long = System.currentTimeMillis()

    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        return if (tokens >= 1.0) { tokens -= 1.0; true } else false
    }

    @Synchronized
    fun msUntilAvailable(): Long {
        refill()
        if (tokens >= 1.0) return 0L
        val deficit = 1.0 - tokens
        return (deficit / refillRatePerMs).toLong() + 1
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        tokens = minOf(maxTokens, tokens + (now - lastRefillTime) * refillRatePerMs)
        lastRefillTime = now
    }
}

class RateLimiter(globalRps: Int = IngestionConfig.RateLimit.GLOBAL_RPS) {

    private val global = TokenBucket(
        maxTokens = globalRps.toDouble(),
        refillRatePerMs = globalRps.toDouble() / 1_000.0
    )

    private val perSource = ConcurrentHashMap<String, TokenBucket>()

    private fun sourceBucket(source: String): TokenBucket = perSource.getOrPut(source) {
        val n = IngestionConfig.RateLimit.DEFAULT_REQUESTS_PER_WINDOW.toDouble()
        val w = IngestionConfig.RateLimit.DEFAULT_WINDOW_MS.toDouble()
        TokenBucket(maxTokens = n, refillRatePerMs = n / w)
    }

    suspend fun acquire(source: String) {
        // Global limiter
        var wait = global.msUntilAvailable()
        while (wait > 0) { delay(wait); wait = global.msUntilAvailable() }
        global.tryAcquire()

        // Per-source limiter
        val bucket = sourceBucket(source)
        wait = bucket.msUntilAvailable()
        while (wait > 0) {
            if (wait > 2_000L) log.debug("[$source] rate-limited — waiting ${wait}ms")
            delay(wait)
            wait = bucket.msUntilAvailable()
        }
        bucket.tryAcquire()
    }

    suspend fun <T> withRateLimit(source: String, block: suspend () -> T): T {
        acquire(source)
        return block()
    }
}
