package com.savebucks.worker.jobs

import com.savebucks.worker.lib.SupabaseWorkerClient
import com.savebucks.worker.lib.WebScraper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DealIngestionJob::class.java)

/**
 * Periodic deal ingestion job.
 *
 * Replaces the original Node.js Puppeteer-based scraper. Uses Jsoup (via [WebScraper])
 * for lightweight HTML parsing without a headless browser.
 *
 * For sites that require JavaScript rendering (e.g., Slickdeals), consider
 * adding Playwright-JVM as an optional dependency.
 *
 * Current sources:
 *  - Slickdeals RSS feed (XML → deals)
 *  - DealNews RSS feed
 *
 * Runs every 60 minutes (configured in WorkerApplication).
 */
class DealIngestionJob(private val supabase: SupabaseWorkerClient) {

    private val scraper = WebScraper()

    /** Deal sources — each entry is a RSS/Atom feed URL + source label. */
    private val sources = listOf(
        "https://slickdeals.net/newsearch.php?mode=frontpage&searcharea=deals&q=&rating=4&output=rss" to "slickdeals",
        "https://www.dealnews.com/rss-feeds/" to "dealnews"
    )

    /**
     * Fetches each configured deal source, parses new items, and inserts them
     * as pending deals in Supabase.
     *
     * Items are deduplicated by URL — if a deal with the same URL already exists,
     * the insert is skipped.
     */
    suspend fun run() = coroutineScope {
        log.info("DealIngestionJob: starting ingestion from ${sources.size} source(s)...")
        var totalInserted = 0

        // Fetch all sources concurrently
        val results = sources.map { (url, source) ->
            async { fetchSource(url, source) }
        }.map { it.await() }

        results.forEach { totalInserted += it }
        log.info("DealIngestionJob: inserted $totalInserted new deal(s) total.")
    }

    private suspend fun fetchSource(feedUrl: String, source: String): Int {
        val doc = scraper.fetchDocument(feedUrl) ?: return 0

        // Parse RSS <item> elements
        val items = doc.select("item")
        var inserted = 0

        for (item in items.take(20)) {  // cap at 20 per source per run to avoid flooding
            val title = item.select("title").text().ifBlank { continue }
            val link = item.select("link").text().ifBlank {
                item.select("guid").text().ifBlank { continue }
            }

            // Skip if we already have this URL
            val existing = supabase.select("deals", mapOf(
                "url" to "eq.$link",
                "select" to "id"
            ))
            if (existing.isNotEmpty()) continue

            val description = item.select("description").text().take(500)

            // Fetch the deal page to extract price/image metadata
            val meta = runCatching { scraper.fetchDocument(link)?.let { scraper.extractDealMeta(it) } }
                .getOrNull() ?: emptyMap()

            supabase.insert("deals", buildJsonObject {
                put("title", title.take(200))
                put("url", link)
                put("source", source)
                put("status", "pending")
                put("description", description)
                meta["imageUrl"]?.let { put("image_url", it) }
                meta["price"]?.let { p -> p.toDoubleOrNull()?.let { put("price", it) } }
            })
            inserted++
        }

        log.info("DealIngestionJob [$source]: inserted $inserted new deal(s).")
        return inserted
    }
}
