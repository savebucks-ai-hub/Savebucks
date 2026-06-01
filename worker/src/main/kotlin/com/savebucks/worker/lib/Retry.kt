package com.savebucks.worker.lib

import io.ktor.client.plugins.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private val log = LoggerFactory.getLogger("Retry")

/**
 * Returns true if the exception represents a transient error that is safe to retry.
 * Mirrors the retryableErrors + retryableStatusCodes lists in ingestion.config.js.
 */
fun isRetryable(e: Throwable): Boolean {
    if (e is CircuitOpenException) return false

    // HTTP status-code-based retry (Ktor ResponseException)
    if (e is ResponseException) {
        return e.response.status.value in IngestionConfig.Retry.RETRYABLE_STATUS_CODES
    }

    // Network / socket errors matched by message substring
    val msg = (e.message ?: e.cause?.message ?: "").lowercase()
    return IngestionConfig.Retry.RETRYABLE_ERRORS.any { pattern ->
        msg.contains(pattern.lowercase())
    }
}

/**
 * Retries [block] up to [maxAttempts] times using exponential backoff with ±25% jitter.
 * Only retries when [retryIf] returns true (defaults to [isRetryable]).
 */
suspend fun <T> withRetry(
    name: String = "operation",
    maxAttempts: Int = IngestionConfig.Retry.MAX_ATTEMPTS,
    baseDelayMs: Long = IngestionConfig.Retry.BASE_DELAY_MS,
    maxDelayMs: Long = IngestionConfig.Retry.MAX_DELAY_MS,
    jitterFactor: Double = IngestionConfig.Retry.JITTER_FACTOR,
    retryIf: (Throwable) -> Boolean = ::isRetryable,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        attempt++
        try {
            return block()
        } catch (e: Exception) {
            if (attempt >= maxAttempts || !retryIf(e)) {
                if (attempt > 1) log.warn("[$name] Giving up after $attempt attempt(s): ${e.message}")
                throw e
            }
            val exponential = min(
                (baseDelayMs * IngestionConfig.Retry.MULTIPLIER.pow(attempt - 1)).toLong(),
                maxDelayMs
            )
            val jitter = (exponential * jitterFactor * (Random.nextDouble() * 2.0 - 1.0)).toLong()
            val waitMs = maxOf(0L, exponential + jitter)
            log.debug("[$name] Attempt $attempt failed (${e.message}), retrying in ${waitMs}ms")
            delay(waitMs)
        }
    }
}
