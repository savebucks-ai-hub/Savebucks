package com.savebucks.worker.lib

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(TelegramBot::class.java)

/**
 * Lightweight Telegram Bot long-polling client.
 *
 * Replaces the Telegraf (Node.js) dependency. Polls the Telegram Bot API
 * for messages in allowed channels and extracts deal links/text to insert
 * into the Supabase pending deals queue.
 *
 * Long-polling is used instead of webhooks because the worker may not have
 * a public HTTPS endpoint. Switch to webhooks if deploying to a stable host.
 */
class TelegramBot(
    private val supabase: SupabaseWorkerClient,
    private val token: String,
    private val allowedChannels: List<String>
) {
    private val apiBase = "https://api.telegram.org/bot$token"
    private var lastUpdateId = 0L

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        engine { requestTimeout = 35_000 }  // slightly over Telegram's 30s long-poll timeout
    }

    /**
     * Starts the long-polling loop in the provided [scope].
     * The loop runs until the scope is cancelled (e.g., on shutdown).
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info("Telegram bot polling started")
            while (isActive) {
                runCatching { poll() }.onFailure { log.warn("Telegram poll error: ${it.message}") }
            }
        }
    }

    /**
     * Single poll cycle — fetches updates and processes channel posts.
     * Uses long-polling with a 30-second timeout to reduce API call frequency.
     */
    private suspend fun poll() {
        val response = client.get("$apiBase/getUpdates") {
            parameter("offset", lastUpdateId + 1)
            parameter("timeout", 30)
            parameter("allowed_updates", "[\"channel_post\"]")
        }

        val body = response.body<JsonObject>()
        val updates = body["result"]?.jsonArray ?: return

        for (update in updates) {
            val obj = update.jsonObject
            lastUpdateId = obj["update_id"]?.jsonPrimitive?.longOrNull ?: continue

            val post = obj["channel_post"]?.jsonObject ?: continue
            val chat = post["chat"]?.jsonObject ?: continue
            val username = chat["username"]?.jsonPrimitive?.contentOrNull ?: continue

            // Only process messages from whitelisted channels
            if (allowedChannels.isNotEmpty() && "@$username" !in allowedChannels) continue

            processChannelPost(post)
        }
    }

    /**
     * Parses a channel post and queues it as a pending deal if it contains
     * a URL (potential deal link) and sufficient text.
     */
    private suspend fun processChannelPost(post: JsonObject) {
        val text = post["text"]?.jsonPrimitive?.contentOrNull ?: return
        if (text.length < 10) return  // too short to be a deal

        // Extract URL from the message or its entities
        val url = post["entities"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "url" }
            ?.let {
                val offset = it.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0
                val length = it.jsonObject["length"]?.jsonPrimitive?.intOrNull ?: 0
                text.substring(offset, offset + length)
            } ?: return  // no URL found — skip

        log.info("New deal from Telegram: ${text.take(60)}")

        supabase.insert("deals", buildJsonObject {
            put("title", text.lines().first().take(200))
            put("url", url)
            put("source", "telegram")
            put("status", "pending")
            put("description", text.take(500))
        })
    }

    fun close() = client.close()
}
