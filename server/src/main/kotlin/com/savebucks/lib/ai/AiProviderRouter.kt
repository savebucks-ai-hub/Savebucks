package com.savebucks.lib.ai

import com.savebucks.config.GroqConfig
import com.savebucks.config.OpenAiConfig
import com.savebucks.lib.redis.RedisCache
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(AiProviderRouter::class.java)

/**
 * Routes LLM calls to Groq (free) first, falls back to OpenAI when the
 * Groq daily quota is exhausted or Groq returns an error.
 *
 * Usage is tracked in Redis so the fallback is shared across all server
 * instances and persists across restarts. Keys expire automatically at 25h
 * (one hour past midnight) to give a clean daily reset.
 *
 * Redis key schema:
 *   ai:groq:req:YYYY-MM-DD    — request count today
 *   ai:groq:tokens:YYYY-MM-DD — token count today
 *   ai:groq:fallback:YYYY-MM-DD — requests that fell back to OpenAI
 */
class AiProviderRouter(
    private val groqConfig: GroqConfig,
    private val openaiConfig: OpenAiConfig,
    private val cache: RedisCache
) {
    private val groqClient = if (groqConfig.isEnabled) {
        AiClient(baseUrl = "https://api.groq.com/openai/v1", apiKey = groqConfig.apiKey)
    } else null

    private val openaiClient = AiClient(baseUrl = "https://api.openai.com/v1", apiKey = openaiConfig.apiKey)

    data class ProviderResult(
        val response: ChatResponse,
        val provider: String,
        val model: String
    )

    /**
     * Sends a chat request via Groq if the daily quota allows, otherwise falls
     * back to OpenAI. The [request.model] is mapped to the appropriate provider
     * model automatically — callers use the OpenAI model names as the common
     * vocabulary.
     */
    suspend fun chat(request: ChatRequest): ProviderResult {
        if (groqClient != null) {
            val today = todayKey()
            val reqKey = "ai:groq:req:$today"
            val currentCount = cache.get(reqKey)?.toLongOrNull() ?: 0L

            if (currentCount < groqConfig.dailyLimit) {
                try {
                    val groqModel = mapToGroqModel(request.model)
                    val result = groqClient.chat(request.copy(model = groqModel))
                    trackGroqUsage(today, result.usage?.totalTokens?.toLong() ?: 0L)
                    return ProviderResult(result, "groq", groqModel)
                } catch (e: AiQuotaException) {
                    log.warn("Groq quota/rate limit hit, falling back to OpenAI")
                    trackFallback(today)
                } catch (e: AiException) {
                    log.warn("Groq request failed (${e.message}), falling back to OpenAI")
                    trackFallback(today)
                }
            } else {
                // use gpt-4o-mini after 15k limit implement this
                log.info("Groq daily limit reached ($currentCount/${groqConfig.dailyLimit}), using OpenAI")
                trackFallback(today)
            }
        }

        // OpenAI fallback — always use the configured fallback model
        val openaiRequest = request.copy(model = openaiConfig.model)
        val result = openaiClient.chat(openaiRequest)
        return ProviderResult(result, "openai", openaiConfig.model)
    }

    /** Maps OpenAI model names to their Groq equivalents. */
    private fun mapToGroqModel(openaiModel: String): String = when (openaiModel) {
        "gpt-4o", "gpt-4", "gpt-4-turbo" -> AiConfig.GROQ_MODEL_QUALITY
        else -> AiConfig.GROQ_MODEL_FAST  // gpt-4o-mini and everything else → fast model
    }

    private suspend fun trackGroqUsage(today: String, tokens: Long) {
        val reqKey = "ai:groq:req:$today"
        val tokenKey = "ai:groq:tokens:$today"
        val newCount = cache.incr(reqKey)
        if (newCount == 1L) cache.expire(reqKey, TTL_25H)
        if (tokens > 0) {
            val newTokens = cache.incrBy(tokenKey, tokens)
            if (newTokens == tokens) cache.expire(tokenKey, TTL_25H)
        }
    }

    private suspend fun trackFallback(today: String) {
        val fallbackKey = "ai:groq:fallback:$today"
        val count = cache.incr(fallbackKey)
        if (count == 1L) cache.expire(fallbackKey, TTL_25H)
    }

    /** Returns today's provider usage statistics for the admin dashboard. */
    suspend fun getStats(): ProviderStats {
        val today = todayKey()
        val groqRequests = cache.get("ai:groq:req:$today")?.toLongOrNull() ?: 0L
        val groqTokens = cache.get("ai:groq:tokens:$today")?.toLongOrNull() ?: 0L
        val fallbacks = cache.get("ai:groq:fallback:$today")?.toLongOrNull() ?: 0L
        val quotaRemaining = (groqConfig.dailyLimit - groqRequests).coerceAtLeast(0L)
        return ProviderStats(
            date = today,
            groqEnabled = groqConfig.isEnabled,
            groqRequestsToday = groqRequests,
            groqDailyLimit = groqConfig.dailyLimit,
            groqQuotaRemaining = quotaRemaining,
            groqTokensToday = groqTokens,
            fallbacksToday = fallbacks,
            activeProvider = if (groqConfig.isEnabled && groqRequests < groqConfig.dailyLimit) "groq" else "openai"
        )
    }

    suspend fun healthCheck(): Map<String, Boolean> {
        val groqOk = groqClient?.healthCheck() ?: false
        val openaiOk = if (openaiConfig.isEnabled) openaiClient.healthCheck() else false
        return mapOf("groq" to groqOk, "openai" to openaiOk)
    }

    fun close() {
        groqClient?.close()
        openaiClient.close()
    }

    private fun todayKey(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        return "%04d-%02d-%02d".format(now.year, now.monthNumber, now.dayOfMonth)
    }

    companion object {
        private const val TTL_25H = 90_000L  // 25 hours in seconds
    }
}

data class ProviderStats(
    val date: String,
    val groqEnabled: Boolean,
    val groqRequestsToday: Long,
    val groqDailyLimit: Long,
    val groqQuotaRemaining: Long,
    val groqTokensToday: Long,
    val fallbacksToday: Long,
    val activeProvider: String
)

/** Thrown when a provider returns HTTP 429 (quota/rate limit). */
class AiQuotaException(message: String) : Exception(message)
