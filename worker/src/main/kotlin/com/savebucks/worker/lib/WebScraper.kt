package com.savebucks.worker.lib

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(WebScraper::class.java)

/**
 * HTML scraper that replaces the original Puppeteer/Cheerio combination.
 *
 * Uses Jsoup (lightweight, pure-JVM) for static HTML pages.
 * For JavaScript-heavy pages that require full browser rendering,
 * consider adding Playwright-JVM or Selenium as an optional dependency.
 */
class WebScraper {

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 15_000
        }
        followRedirects = true
    }

    // Realistic browser user-agent to avoid bot-detection on most sites
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Fetches the HTML at [url] and returns a parsed Jsoup [Document].
     * Returns null on network failure, 4xx, or 5xx responses.
     */
    suspend fun fetchDocument(url: String): Document? = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.get(url) {
                headers.append(HttpHeaders.UserAgent, userAgent)
                headers.append(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                headers.append(HttpHeaders.AcceptLanguage, "en-US,en;q=0.5")
            }

            if (!response.status.isSuccess()) {
                log.warn("Scrape of $url returned ${response.status}")
                return@withContext null
            }

            val html = response.bodyAsText()
            Jsoup.parse(html, url)
        } catch (e: Exception) {
            log.warn("Failed to scrape $url: ${e.message}")
            null
        }
    }

    /**
     * Extracts deal-related metadata from a [Document] using common
     * Open Graph and schema.org selectors.
     *
     * Returns a map of: title, price, originalPrice, imageUrl, description
     */
    fun extractDealMeta(doc: Document): Map<String, String?> {
        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.title() }
            .ifBlank { null }

        val imageUrl = doc.select("meta[property=og:image]").attr("content")
            .ifBlank { null }

        val description = doc.select("meta[property=og:description]").attr("content")
            .ifBlank { doc.select("meta[name=description]").attr("content") }
            .ifBlank { null }

        // Extract price from common schema.org and site-specific selectors
        val priceText = doc.select("[itemprop=price]").attr("content")
            .ifBlank { doc.select(".price, .deal-price, #priceblock_ourprice").firstOrNull()?.text() }
            ?.ifBlank { null }

        val price = priceText?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()?.toString()

        return mapOf(
            "title" to title,
            "imageUrl" to imageUrl,
            "description" to description?.take(500),
            "price" to price
        )
    }

    fun close() = httpClient.close()
}
