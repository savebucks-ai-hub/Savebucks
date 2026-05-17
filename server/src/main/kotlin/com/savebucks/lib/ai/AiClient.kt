package com.savebucks.lib.ai

import com.savebucks.config.OpenAiConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(AiClient::class.java)

/**
 * Low-level OpenAI API client.
 *
 * Handles authentication, retry with exponential backoff, and error
 * categorisation. Business logic (intent routing, caching, tool execution)
 * lives in [AiOrchestrator] — this class only concerns itself with HTTP.
 */
class AiClient(private val config: OpenAiConfig) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(Logging) {
            logger = object : Logger {
                private val slf4j = LoggerFactory.getLogger("AiHttpClient")
                override fun log(message: String) = slf4j.debug(message)
            }
            level = LogLevel.NONE
        }
        engine { requestTimeout = 60_000 }
    }

    private val baseUrl = "https://api.openai.com/v1"

    /**
     * Sends a chat completion request with automatic retry on transient errors.
     *
     * @param request The full [ChatRequest] including messages and tools.
     * @param maxRetries Number of retry attempts on rate-limit or server errors.
     * @return The parsed [ChatResponse] from OpenAI.
     * @throws AiException when all retries are exhausted or a non-retryable error occurs.
     */
    suspend fun chat(request: ChatRequest, maxRetries: Int = 3): ChatResponse {
        repeat(maxRetries) { attempt ->
            try {
                val response = client.post("$baseUrl/chat/completions") {
                    headers.append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                if (response.status == HttpStatusCode.TooManyRequests) {
                    // Respect Retry-After header if present; otherwise use exponential backoff
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: ((attempt + 1) * 2L)
                    log.warn("OpenAI rate limited — retrying in ${retryAfter}s (attempt ${attempt + 1})")
                    delay(retryAfter * 1_000)
                    return@repeat
                }

                if (!response.status.isSuccess()) {
                    throw AiException("OpenAI error ${response.status.value}: ${response.status.description}")
                }

                return response.body<ChatResponse>()

            } catch (e: AiException) {
                throw e  // don't retry on explicit AI errors
            } catch (e: Exception) {
                if (attempt == maxRetries - 1) throw AiException("OpenAI request failed after $maxRetries attempts: ${e.message}", e)
                val backoffMs = (1L shl attempt) * 1_000 + (Math.random() * 500).toLong()
                log.warn("OpenAI request failed (attempt ${attempt + 1}), retrying in ${backoffMs}ms: ${e.message}")
                delay(backoffMs)
            }
        }
        throw AiException("OpenAI request failed after $maxRetries attempts")
    }

    /** @return true if the OpenAI API is reachable and the key is valid. */
    suspend fun healthCheck(): Boolean = runCatching {
        val resp = client.get("$baseUrl/models") {
            headers.append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
        }
        resp.status.isSuccess()
    }.getOrDefault(false)

    fun close() = client.close()
}

/** Thrown when the OpenAI API returns an error or connectivity fails. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)
