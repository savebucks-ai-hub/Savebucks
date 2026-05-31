package com.savebucks.worker.jobs

import com.savebucks.worker.lib.SupabaseWorkerClient
import com.savebucks.worker.lib.WebScraper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DealIngestionJob::class.java)

/**
 * Periodic deal ingestion job — replaces the original Node.js rssFetcher.js.
 *
 * Sources mirror the original config:
 *  - Slickdeals frontpage RSS (same URL the Node.js worker used — rss=1&searchin=first)
 *  - TechBargains via FeedBurner (DealNews replaced — their site is down)
 *
 * Runs every 25 minutes (configured in WorkerApplication) to match original schedule.
 * Price is extracted from the RSS title via regex — avoids hitting each deal page
 * individually (Slickdeals deal pages are behind Cloudflare).
 */
class DealIngestionJob(private val supabase: SupabaseWorkerClient) {

    private val scraper = WebScraper()

    private val sources = listOf(
        "https://slickdeals.net/newsearch.php?mode=frontpage&searcharea=deals&searchin=first&rss=1" to "slickdeals",
        "https://feeds.feedburner.com/techbargains" to "techbargains"
    )

    // Matches trailing price like "$4.99", "$650", "$1,299.00"
    private val priceRegex = Regex("""\$(\d{1,3}(?:,\d{3})*(?:\.\d{2})?)""")

    suspend fun run() = coroutineScope {
        log.info("DealIngestionJob: starting ingestion from ${sources.size} source(s)...")
        var totalInserted = 0
        val results = sources.map { (url, source) ->
            async { fetchSource(url, source) }
        }.map { it.await() }
        results.forEach { totalInserted += it }
        log.info("DealIngestionJob: inserted $totalInserted new deal(s) total.")
    }

    private suspend fun fetchSource(feedUrl: String, source: String): Int {
        val doc = scraper.fetchXmlDocument(feedUrl) ?: return 0

        val items = doc.select("item")
        var inserted = 0

        for (item in items.take(20)) {
            val title = item.select("title").text().ifBlank { continue }
            val link = item.select("link").text().ifBlank {
                item.select("guid").text().ifBlank { continue }
            }

            // Deduplicate by URL
            val existing = supabase.select("deals", mapOf("url" to "eq.$link", "select" to "id"))
            if (existing.isNotEmpty()) continue

            val description = item.select("description").text().take(500)

            // Extract price from the RSS title (e.g. "Sony Headphones $79.99 + Free S&H")
            val price = priceRegex.findAll(title).lastOrNull()?.groupValues?.get(1)
                ?.replace(",", "")?.toDoubleOrNull()

            // Extract thumbnail from <content:encoded> CDATA — Slickdeals embeds <img> there
            val encodedContent = item.select("encoded").text()
                .ifBlank { item.select("content|encoded").text() }
            val imageUrl = if (encodedContent.isNotBlank()) {
                runCatching { Jsoup.parse(encodedContent).select("img").firstOrNull()?.attr("src") }
                    .getOrNull()
            } else null

            // Extract merchant from description (e.g. "Amazon has ..." or "Walmart has ...")
            val merchant = extractMerchant(description)

            supabase.insert("deals", buildJsonObject {
                put("title", title.take(200))
                put("url", link)
                put("source", source)
                put("status", "pending")
                put("description", description)
                merchant?.let { put("merchant", it) }
                price?.let { put("price", it) }
                imageUrl?.let { put("image_url", it) }
            })
            inserted++
        }

        log.info("DealIngestionJob [$source]: inserted $inserted new deal(s).")
        return inserted
    }

    // Pulls merchant name from common RSS description patterns like "Amazon has ..." or "via Amazon"
    private fun extractMerchant(description: String): String? {
        val merchants = listOf("Amazon", "Walmart", "Best Buy", "Target", "Costco",
            "Home Depot", "Lowe's", "eBay", "Newegg", "B&H", "Staples", "Sam's Club")
        return merchants.firstOrNull { description.contains(it, ignoreCase = true) }
    }
}
