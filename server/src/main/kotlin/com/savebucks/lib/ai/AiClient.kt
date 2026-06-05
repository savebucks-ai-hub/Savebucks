package com.savebucks.lib.ai

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
 * Low-level HTTP client for OpenAI-compatible chat completion APIs.
 *
 * Both OpenAI and Groq use the same API shape — the only difference is
 * [baseUrl] and [apiKey]. Business logic lives in [AiProviderRouter].
 */
class AiClient(private val baseUrl: String, private val apiKey: String) {

    private val httpClient = HttpClient(CIO) {
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

    /**
     * Sends a chat completion request with automatic retry on transient errors.
     *
     * @throws AiQuotaException on HTTP 429 so the router can fall back immediately.
     * @throws AiException on all other failures after retries are exhausted.
     */
    suspend fun chat(request: ChatRequest, maxRetries: Int = 3): ChatResponse {
        repeat(maxRetries) { attempt ->
            try {
                val response = httpClient.post("$baseUrl/chat/completions") {
                    headers.append(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                if (response.status == HttpStatusCode.TooManyRequests) {
                    // Surface quota exhaustion as a typed exception so the router can fall back
                    throw AiQuotaException("Rate limited by $baseUrl (HTTP 429)")
                }

                if (!response.status.isSuccess()) {
                    throw AiException("$baseUrl error ${response.status.value}: ${response.status.description}")
                }

                return response.body<ChatResponse>()

            } catch (e: AiQuotaException) {
                throw e  // propagate immediately — no point retrying a quota error
            } catch (e: AiException) {
                throw e
            } catch (e: Exception) {
                if (attempt == maxRetries - 1) throw AiException("Request to $baseUrl failed after $maxRetries attempts: ${e.message}", e)
                val backoffMs = (1L shl attempt) * 1_000 + (Math.random() * 500).toLong()
                log.warn("Request failed (attempt ${attempt + 1}), retrying in ${backoffMs}ms: ${e.message}")
                delay(backoffMs)
            }
        }
        throw AiException("Request to $baseUrl failed after $maxRetries attempts")
    }

    /** @return true if the API endpoint is reachable and the key is valid. */
    suspend fun healthCheck(): Boolean = runCatching {
        httpClient.get("$baseUrl/models") {
            headers.append(HttpHeaders.Authorization, "Bearer $apiKey")
        }.status.isSuccess()
    }.getOrDefault(false)

    fun close() = httpClient.close()
}

/** Thrown when the OpenAI API returns an error or connectivity fails. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)
