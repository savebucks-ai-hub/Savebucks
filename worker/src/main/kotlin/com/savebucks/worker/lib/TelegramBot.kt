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
 * Lightweight Telegram Bot long-polling client — replaces the JS telegramWorker.js.
 *
 * Monitors whitelisted Telegram channels and queues deal posts as pending records.
 * Near-duplicate detection prevents the same deal being inserted twice within 7 days.
 * Long-polling (30 s timeout) is used instead of webhooks to avoid needing a public endpoint.
 */
class TelegramBot(
    private val supabase: SupabaseWorkerClient,
    private val token: String,
    private val allowedChannels: List<String>,
    private val minTitleLength: Int = IngestionConfig.Telegram.MIN_TITLE_LENGTH
) {
    private val apiBase = "https://api.telegram.org/bot$token"
    private var lastUpdateId = 0L

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        engine { requestTimeout = 35_000 } // slightly over Telegram's 30 s long-poll timeout
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            log.info("Telegram bot polling started (channels: ${allowedChannels.ifEmpty { listOf("all") }.joinToString()})")
            while (isActive) {
                runCatching { poll() }.onFailure { log.warn("Telegram poll error: ${it.message}") }
            }
        }
    }

    private suspend fun poll() {
        val response = client.get("$apiBase/getUpdates") {
            parameter("offset", lastUpdateId + 1)
            parameter("timeout", 30)
            parameter("allowed_updates", "[\"channel_post\"]")
        }

        val updates = response.body<JsonObject>()["result"]?.jsonArray ?: return

        for (update in updates) {
            val obj = update.jsonObject
            lastUpdateId = obj["update_id"]?.jsonPrimitive?.longOrNull ?: continue
            obj["channel_post"]?.jsonObject?.let { post ->
                runCatching { processPost(post) }.onFailure {
                    log.warn("Error processing Telegram post: ${it.message}")
                }
            }
        }
    }

    private suspend fun processPost(post: JsonObject) {
        val chat = post["chat"]?.jsonObject ?: return
        val username = chat["username"]?.jsonPrimitive?.contentOrNull ?: return

        if (allowedChannels.isNotEmpty() && "@$username" !in allowedChannels) return

        val text = post["text"]?.jsonPrimitive?.contentOrNull ?: return

        // Extract title from first non-empty line; fall back to merchant or generic label
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val rawTitle = lines.firstOrNull() ?: return
        val title = rawTitle.let {
            if (it.length >= minTitleLength) it
            else lines.drop(1).firstOrNull()?.takeIf { l -> l.length >= minTitleLength }
                ?: it  // keep even if short — processor will skip if too short
        }

        if (title.length < minTitleLength) {
            log.debug("Telegram post too short (${title.length} < $minTitleLength): '${title.take(40)}'")
            return
        }

        // URL from message entities
        val url = post["entities"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "url" }
            ?.let { ent ->
                val offset = ent.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0
                val length = ent.jsonObject["length"]?.jsonPrimitive?.intOrNull ?: 0
                runCatching { text.substring(offset, minOf(offset + length, text.length)) }.getOrNull()
            } ?: return // no URL — not a deal

        // Near-duplicate check: same URL or similar title in the last 7 days
        if (isDuplicate(url, title)) return

        supabase.insert("deals", buildJsonObject {
            put("title", title.take(200))
            put("url", url)
            put("source", "telegram")
            put("status", "pending")
            put("description", text.take(500))
            put("submitter_note", "@$username")
        })

        log.info("Telegram deal queued from @$username: '${title.take(60)}'")
    }

    private suspend fun isDuplicate(url: String, title: String): Boolean {
        // Exact URL match
        val byUrl = supabase.select("deals", mapOf("url" to "eq.$url", "select" to "id", "limit" to "1"))
        if (byUrl.isNotEmpty()) return true

        // Title similarity against recent Telegram deals
        val lookbackMs = System.currentTimeMillis() - IngestionConfig.Telegram.DEDUP_LOOKBACK_DAYS * 86_400_000L
        val lookbackIso = java.time.Instant.ofEpochMilli(lookbackMs).toString()
        val recent = supabase.select("deals", mapOf(
            "source" to "eq.telegram",
            "created_at" to "gt.$lookbackIso",
            "select" to "title",
            "limit" to "100"
        ))

        val titleWords = title.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        for (row in recent) {
            val existingTitle = row.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: continue
            val existingWords = existingTitle.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
            val union = (titleWords union existingWords).size
            val sim = if (union == 0) 0.0 else (titleWords intersect existingWords).size.toDouble() / union
            if (sim >= IngestionConfig.Telegram.DEDUP_SIMILARITY_THRESHOLD) {
                log.debug("Telegram near-duplicate skipped (sim=${String.format("%.2f", sim)}): '${title.take(50)}'")
                return true
            }
        }

        return false
    }

    fun close() = client.close()
}
