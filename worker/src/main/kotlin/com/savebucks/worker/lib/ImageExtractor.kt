package com.savebucks.worker.lib

import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ImageExtractor::class.java)

/**
 * Extracts product images from deal pages — mirrors imageExtractor.js.
 *
 * Priority order:
 *   1. Open Graph og:image
 *   2. Twitter Card twitter:image
 *   3. JSON-LD schema "image" field
 *   4. Content-area <img> tags (with Slickdeals-specific targeting)
 */
class ImageExtractor(private val scraper: WebScraper) {

    private val EXCLUDED = listOf(
        "logo", "icon", "avatar", "tracking", "pixel", "badge", "spinner",
        "ad.", "ads.", "banner", "placeholder", "favicon"
    )

    suspend fun extractPrimary(url: String): String? = runCatching {
        val doc = scraper.fetchDocument(url) ?: return null
        fromDoc(doc)
    }.getOrNull()

    suspend fun extractAll(url: String, max: Int = 5): List<String> = runCatching {
        val doc = scraper.fetchDocument(url) ?: return emptyList()
        allFromDoc(doc, url, max)
    }.getOrDefault(emptyList())

    /** Extract a single image from an already-parsed document (avoids extra HTTP round-trip). */
    fun fromDoc(doc: Document): String? {
        // 1. Open Graph
        doc.select("meta[property=og:image]").attr("content")
            .takeIf { it.isNotBlank() }?.let { return it }

        // 2. Twitter Card
        doc.select("meta[name=twitter:image]").attr("content")
            .takeIf { it.isNotBlank() }?.let { return it }

        // 3. JSON-LD
        doc.select("script[type=application/ld+json]").forEach { script ->
            Regex(""""image"\s*:\s*"(https?://[^"]+)"""")
                .find(script.data())?.groupValues?.get(1)?.let { return it }
        }

        // 4. First qualifying content image
        return doc.select("article img, .content img, main img, .product img, [class*=deal] img")
            .firstOrNull { img ->
                val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
                src.startsWith("http") && EXCLUDED.none { src.contains(it, ignoreCase = true) }
            }?.let { img ->
                img.absUrl("src").ifBlank { img.absUrl("data-src") }.ifBlank { null }
            }
    }

    private fun allFromDoc(doc: Document, baseUrl: String, max: Int): List<String> {
        val images = mutableListOf<String>()

        fromDoc(doc)?.let { images.add(it) }

        val isSlickdeals = "slickdeals.net" in baseUrl
        val selector = if (isSlickdeals) ".dealDetail img, .dealDetailContainer img, .main-content img"
                       else "article img, main img, .content img, [class*=product] img"

        doc.select(selector)
            .mapNotNull { img ->
                val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
                src.takeIf {
                    it.startsWith("http") &&
                    EXCLUDED.none { excl -> it.contains(excl, ignoreCase = true) } &&
                    it !in images
                }
            }
            .take(max - images.size)
            .forEach { images.add(it) }

        return images.take(max)
    }
}
