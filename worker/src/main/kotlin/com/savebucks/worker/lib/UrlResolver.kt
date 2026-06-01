package com.savebucks.worker.lib

import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(UrlResolver::class.java)

/**
 * Extracts actual merchant URLs from deal aggregator pages — mirrors urlResolver.js.
 *
 * Supports Slickdeals, DealNews, and TechBargains. Results are cached for 1 hour.
 * Falls back to the original URL on any error.
 */
class UrlResolver(private val scraper: WebScraper) {

    private data class CacheEntry(val url: String, val ts: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val CACHE_TTL_MS = 3_600_000L

    private val BLOCKED = setOf(
        "facebook.com", "twitter.com", "instagram.com", "youtube.com",
        "cloudfront.net", "amazonaws.com", "googleapis.com",
        "play.google.com", "apps.apple.com", "cdn."
    )

    private val AGGREGATORS = setOf("slickdeals.net", "dealnews.com", "techbargains.com")

    suspend fun resolve(url: String): String {
        cache[url]?.let { if (System.currentTimeMillis() - it.ts < CACHE_TTL_MS) return it.url }
        val resolved = runCatching { doResolve(url) }.getOrDefault(url)
        cache[url] = CacheEntry(resolved, System.currentTimeMillis())
        return resolved
    }

    private suspend fun doResolve(url: String): String {
        val host = hostOf(url) ?: return url
        return when {
            "slickdeals.net" in host -> resolveSlickdeals(url) ?: url
            "dealnews.com" in host   -> resolveViaOgUrl(url) ?: url
            else                     -> url
        }
    }

    private suspend fun resolveSlickdeals(url: String): String? {
        val doc = scraper.fetchDocument(url) ?: return null

        // data attributes placed on the buy button
        doc.select("[data-merchant-url],[data-target-url],[data-bhwl-url]").firstOrNull()?.let { el ->
            val href = el.attr("data-merchant-url")
                .ifBlank { el.attr("data-target-url") }
                .ifBlank { el.attr("data-bhwl-url") }
            if (isValidMerchant(href)) return cleanUrl(href)
        }

        // Primary CTA button
        doc.select("a.dealDetails-buyButton, a.btn-primary, a[data-deal-url]").firstOrNull()?.let { a ->
            val href = a.attr("href").ifBlank { a.attr("data-deal-url") }
            if (isValidMerchant(href)) return cleanUrl(href)
        }

        // Any external link inside the deal area
        doc.select(".dealDetail a[href], .dealDetailContainer a[href]")
            .map { it.absUrl("href") }
            .firstOrNull { isValidMerchant(it) }
            ?.let { return cleanUrl(it) }

        return null
    }

    private suspend fun resolveViaOgUrl(url: String): String? {
        val doc = scraper.fetchDocument(url) ?: return null
        val ogUrl = doc.select("meta[property=og:url]").attr("content")
        return ogUrl.takeIf { it.isNotBlank() && isValidMerchant(it) }
    }

    private fun isValidMerchant(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        val host = hostOf(url) ?: return false
        if (AGGREGATORS.any { it in host }) return false
        if (BLOCKED.any { it in host }) return false
        return true
    }

    private fun cleanUrl(url: String): String = runCatching {
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        URI(decoded).toString()
    }.getOrDefault(url)

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()
}
