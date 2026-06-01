package com.savebucks.worker.jobs

import com.savebucks.worker.jobs.ingestion.*
import com.savebucks.worker.lib.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DealIngestionJob::class.java)

/**
 * Fetches RSS feeds for enabled sources and routes items through the full
 * DealProcessor or CouponProcessor pipeline.
 *
 * Each source is wrapped in a CircuitBreaker (one bad feed won't trip others)
 * and a RateLimiter (polite crawling).  The SourceRegistry controls which
 * sources are enabled and at what cadence.
 */
class DealIngestionJob(
    private val supabase: SupabaseWorkerClient,
    private val dealProcessor: DealProcessor,
    private val couponProcessor: CouponProcessor,
    private val circuitBreaker: CircuitBreaker,
    private val rateLimiter: RateLimiter,
    private val scraper: WebScraper
) {
    // Price like "$79.99", "$1,299", "$4"
    private val priceRegex = Regex("""\$(\d{1,3}(?:,\d{3})*(?:\.\d{2})?)""")

    // Coupon codes like "w/ code SAVE20", "code: DEAL"
    private val couponCodeRegex = Regex(
        """(?:w/\s*code|code[:\s]|promo(?:\s*code)?[:\s]|coupon[:\s])\s*([A-Z0-9]{3,20})""",
        RegexOption.IGNORE_CASE
    )

    // Known merchant names for extraction
    private val MERCHANTS = listOf(
        "Amazon", "Walmart", "Best Buy", "Target", "Costco", "Home Depot",
        "Lowe's", "eBay", "Newegg", "B&H", "Staples", "Sam's Club",
        "Dell", "HP", "Samsung", "Apple", "Microsoft", "Lenovo"
    )

    /** Called by the Scheduler once per source on its configured interval. */
    suspend fun runSource(source: IngestionSource) {
        log.info("[${source.key}] Starting ingestion from: ${source.name}")
        try {
            circuitBreaker.withCircuitBreaker(source.key) {
                rateLimiter.withRateLimit(source.key) {
                    when (source.type) {
                        SourceType.RSS_DEALS   -> fetchAndProcessDeals(source)
                        SourceType.RSS_COUPONS -> fetchAndProcessCoupons(source)
                    }
                }
            }
        } catch (e: CircuitOpenException) {
            log.warn("[${source.key}] ${e.message}")
        } catch (e: Exception) {
            log.error("[${source.key}] Ingestion failed: ${e.message}", e)
            supabase.logError(source.key, e)
        }
    }

    // --- deal RSS ---

    private suspend fun fetchAndProcessDeals(source: IngestionSource) {
        val doc = withRetry(name = source.key) { scraper.fetchXmlDocument(source.url) }
            ?: run { log.warn("[${source.key}] Could not fetch RSS feed"); return }

        val rawDeals = parseDealsFromRss(doc, source.key)
        log.info("[${source.key}] Parsed ${rawDeals.size} item(s) from RSS")

        val result = dealProcessor.processBatch(rawDeals.take(20), source.key)
        log.info("[${source.key}] Done — created=${result.created} updated=${result.updated} skipped=${result.skipped} errors=${result.errors}")
    }

    private fun parseDealsFromRss(doc: Document, sourceKey: String): List<RawDeal> {
        return doc.select("item").mapNotNull { item ->
            runCatching {
                val title = item.select("title").text().ifBlank { return@mapNotNull null }
                val link = item.select("link").text().ifBlank {
                    item.select("guid").text().ifBlank { return@mapNotNull null }
                }

                val description = item.select("description").text().take(5000)
                val price = priceRegex.findAll(title).lastOrNull()
                    ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

                // Thumbnail from <content:encoded> CDATA — Slickdeals embeds <img> there
                val encoded = item.select("encoded").text().ifBlank { item.select("content|encoded").text() }
                val imageUrl = if (encoded.isNotBlank()) {
                    runCatching {
                        org.jsoup.Jsoup.parse(encoded).select("img").firstOrNull()?.attr("src")
                            ?.takeIf { it.startsWith("http") }
                    }.getOrNull()
                } else null

                // Merchant from bracket notation "[amazon.com]" or keyword scan
                val merchant = extractMerchantBracket(title)
                    ?: extractMerchantBracket(description)
                    ?: MERCHANTS.firstOrNull { description.contains(it, ignoreCase = true) }

                val couponCode = couponCodeRegex.find(title)?.groupValues?.get(1)?.uppercase()
                    ?: couponCodeRegex.find(description)?.groupValues?.get(1)?.uppercase()

                val guid = item.select("guid").text().ifBlank { null }

                RawDeal(
                    title = title,
                    url = link,
                    description = description.ifBlank { null },
                    imageUrl = imageUrl,
                    price = price,
                    merchant = merchant,
                    couponCode = couponCode,
                    externalId = if (guid != link) guid else null
                )
            }.onFailure { log.debug("[$sourceKey] Skipped malformed item: ${it.message}") }
            .getOrNull()
        }
    }

    // --- coupon RSS ---

    private suspend fun fetchAndProcessCoupons(source: IngestionSource) {
        val doc = withRetry(name = source.key) { scraper.fetchXmlDocument(source.url) }
            ?: run { log.warn("[${source.key}] Could not fetch coupon RSS feed"); return }

        val rawCoupons = parseCouponsFromRss(doc, source.key)
        log.info("[${source.key}] Parsed ${rawCoupons.size} coupon(s) from RSS")

        val result = couponProcessor.processBatch(rawCoupons.take(20), source.key)
        log.info("[${source.key}] Done — created=${result.created} skipped=${result.skipped} errors=${result.errors}")
    }

    private fun parseCouponsFromRss(doc: Document, sourceKey: String): List<RawCoupon> {
        return doc.select("item").mapNotNull { item ->
            runCatching {
                val title = item.select("title").text().ifBlank { return@mapNotNull null }
                val link = item.select("link").text().ifBlank {
                    item.select("guid").text().ifBlank { return@mapNotNull null }
                }

                val description = item.select("description").text().take(5000)
                val merchant = extractMerchantBracket(title)
                    ?: extractMerchantBracket(description)
                    ?: MERCHANTS.firstOrNull { title.contains(it, ignoreCase = true) }

                val couponCode = couponCodeRegex.find(title)?.groupValues?.get(1)?.uppercase()
                    ?: couponCodeRegex.find(description)?.groupValues?.get(1)?.uppercase()

                // Discount percentage like "10% off", "20 percent"
                val discountValue = Regex("""(\d+(?:\.\d+)?)\s*%""").find(title)
                    ?.groupValues?.get(1)?.toDoubleOrNull()

                RawCoupon(
                    title = title,
                    url = link,
                    description = description.ifBlank { null },
                    merchant = merchant,
                    couponCode = couponCode,
                    discountValue = discountValue,
                    discountType = if (discountValue != null) "percent" else null
                )
            }.onFailure { log.debug("[$sourceKey] Skipped malformed coupon item: ${it.message}") }
            .getOrNull()
        }
    }

    // --- helpers ---

    /** Extracts merchant from bracket notation like "[amazon.com]" in Slickdeals titles. */
    private fun extractMerchantBracket(text: String): String? =
        Regex("""\[([a-z0-9 .&']+)\]""", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.trim()?.ifBlank { null }
}
