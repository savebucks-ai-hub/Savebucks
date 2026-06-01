package com.savebucks.worker.lib

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URI

private val log = LoggerFactory.getLogger(Deduper::class.java)

/**
 * Multi-strategy deduplication engine — mirrors the JS deduper.js.
 *
 * Strategies in order of confidence:
 *   1. Normalized URL match (100%)
 *   2. External ID match   (99%)
 *   3. Jaccard title similarity against recent deals (configurable threshold)
 */
class Deduper(private val supabase: SupabaseWorkerClient) {

    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
        "fbclid", "gclid", "ref", "tag", "affiliate", "aff_id", "clickid"
    )

    private val STOPWORDS = setOf(
        "deal", "sale", "free", "shipping", "off", "new", "best", "cheap",
        "price", "buy", "get", "save", "extra", "more", "up", "to", "at",
        "on", "the", "a", "an", "and", "or", "with", "for", "in", "of", "from"
    )

    data class DedupResult(
        val isDuplicate: Boolean,
        val existingId: String? = null,
        val method: String? = null,
        val confidence: Double = 0.0
    )

    suspend fun deduplicateDeal(
        url: String,
        title: String,
        externalId: String? = null,
        price: Double? = null
    ): DedupResult {
        // Strategy 1: Normalized URL
        val normalizedUrl = normalizeUrl(url)
        val byUrl = supabase.select("deals", mapOf("url" to "eq.$normalizedUrl", "select" to "id", "limit" to "1"))
        if (byUrl.isNotEmpty()) {
            return DedupResult(true, byUrl[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull, "url", 1.0)
        }
        // Also check original URL in case stored un-normalized
        if (normalizedUrl != url) {
            val byOrigUrl = supabase.select("deals", mapOf("url" to "eq.$url", "select" to "id", "limit" to "1"))
            if (byOrigUrl.isNotEmpty()) {
                return DedupResult(true, byOrigUrl[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull, "url_original", 1.0)
            }
        }

        // Strategy 2: External ID
        if (!externalId.isNullOrBlank()) {
            val byExtId = supabase.select("deals", mapOf("external_id" to "eq.$externalId", "select" to "id", "limit" to "1"))
            if (byExtId.isNotEmpty()) {
                return DedupResult(true, byExtId[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull, "external_id", 0.99)
            }
        }

        // Strategy 3: Title similarity against recent deals
        val lookbackDate = System.currentTimeMillis() - IngestionConfig.Dedup.LOOKBACK_DAYS * 86_400_000L
        val lookbackIso = java.time.Instant.ofEpochMilli(lookbackDate).toString()
        val recent = supabase.select("deals", mapOf(
            "created_at" to "gt.$lookbackIso",
            "select" to "id,title,price",
            "limit" to "${IngestionConfig.Dedup.MAX_CANDIDATES}"
        ))

        val titleWords = tokenize(normalizeTitle(title))
        for (row in recent) {
            val obj = row.jsonObject
            val existingTitle = obj["title"]?.jsonPrimitive?.contentOrNull ?: continue
            val sim = jaccardSimilarity(titleWords, tokenize(normalizeTitle(existingTitle)))
            if (sim < IngestionConfig.Dedup.TITLE_SIMILARITY_THRESHOLD) continue

            // Price variance check — if prices differ significantly it's a different deal
            val existingPrice = obj["price"]?.jsonPrimitive?.doubleOrNull
            if (price != null && existingPrice != null && price > 0 && existingPrice > 0) {
                val variance = kotlin.math.abs(price - existingPrice) / maxOf(price, existingPrice)
                if (variance > IngestionConfig.Dedup.PRICE_VARIANCE_TOLERANCE) continue
            }

            return DedupResult(
                isDuplicate = true,
                existingId = obj["id"]?.jsonPrimitive?.contentOrNull,
                method = "title_similarity",
                confidence = sim
            )
        }

        return DedupResult(isDuplicate = false)
    }

    /** Selectively update an existing deal with better data from the new record. */
    suspend fun updateExistingDeal(existingId: String, newData: JsonObject) {
        val updates = buildJsonObject {
            newData["description"]?.jsonPrimitive?.contentOrNull?.let { desc ->
                if (desc.length > 50) put("description", desc)
            }
            newData["image_url"]?.jsonPrimitive?.contentOrNull?.let { put("image_url", it) }
            newData["expires_at"]?.jsonPrimitive?.contentOrNull?.let { put("expires_at", it) }
            newData["original_price"]?.let { put("original_price", it) }
        }
        if (updates.isNotEmpty()) {
            supabase.update("deals", updates, mapOf("id" to "eq.$existingId"))
            log.debug("Updated existing deal $existingId with ${updates.keys.size} field(s)")
        }
    }

    // --- helpers ---

    private fun normalizeUrl(url: String): String = runCatching {
        val uri = URI(url)
        val filteredQuery = uri.query?.split("&")
            ?.filter { param -> TRACKING_PARAMS.none { param.startsWith("$it=") } }
            ?.joinToString("&")
        URI(uri.scheme, uri.host, uri.path, filteredQuery?.ifBlank { null }, null)
            .toString().lowercase()
    }.getOrDefault(url.lowercase())

    private fun normalizeTitle(title: String) =
        title.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun tokenize(text: String): Set<String> =
        text.split(" ").filter { it.length > 1 && it !in STOPWORDS }.toSet()

    private fun jaccardSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val union = (a union b).size
        return if (union == 0) 0.0 else (a intersect b).size.toDouble() / union.toDouble()
    }
}
