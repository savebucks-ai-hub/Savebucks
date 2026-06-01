package com.savebucks.worker.jobs.ingestion

import com.savebucks.worker.lib.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URI

private val log = LoggerFactory.getLogger(DealProcessor::class.java)

data class RawDeal(
    val title: String,
    val url: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val price: Double? = null,
    val listPrice: Double? = null,
    val merchant: String? = null,
    val category: String? = null,
    val expiresAt: String? = null,
    val externalId: String? = null,
    val couponCode: String? = null
)

data class ProcessResult(
    val action: String, // created | updated | skipped | error
    val id: String? = null,
    val reason: String? = null,
    val errors: List<String> = emptyList()
)

data class BatchResult(var created: Int = 0, var updated: Int = 0, var skipped: Int = 0, var errors: Int = 0)

/**
 * Full deal processing pipeline — mirrors dealProcessor.js.
 *
 * Steps: normalize → validate → daily-cap → deduplicate → company match →
 *        URL resolution → image extraction → insert
 */
class DealProcessor(
    private val supabase: SupabaseWorkerClient,
    private val deduper: Deduper,
    private val companyMatcher: CompanyMatcher,
    private val imageExtractor: ImageExtractor,
    private val urlResolver: UrlResolver
) {

    suspend fun process(raw: RawDeal, source: String): ProcessResult {
        val startMs = System.currentTimeMillis()

        // Step 1: Normalize
        val deal = normalize(raw, source)

        // Step 2: Validate
        val (valid, errors) = validate(deal)
        if (!valid) {
            log.debug("[$source] Skipping '${raw.title.take(50)}' — $errors")
            return ProcessResult("skipped", reason = "validation_failed", errors = errors)
        }

        // Step 3: Daily cap
        val cap = DailyCapTracker.check(source)
        if (!cap.allowed) {
            log.warn("[$source] Daily cap reached (${cap.current}/${cap.cap})")
            return ProcessResult("skipped", reason = "daily_cap_reached")
        }

        // Step 4: Deduplicate
        val dedup = deduper.deduplicateDeal(deal.url, deal.title, deal.externalId, deal.price)
        if (dedup.isDuplicate) {
            log.debug("[$source] Duplicate via ${dedup.method} (${String.format("%.2f", dedup.confidence)}): '${deal.title.take(50)}'")
            dedup.existingId?.let { id ->
                deduper.updateExistingDeal(id, buildJsonObject {
                    deal.description?.let { put("description", it) }
                    deal.imageUrl?.let { put("image_url", it) }
                    deal.expiresAt?.let { put("expires_at", it) }
                    deal.listPrice?.let { put("original_price", it) }
                })
            }
            return ProcessResult("updated", id = dedup.existingId, reason = dedup.method)
        }

        // Step 5: Company match (best-effort — don't fail the deal on error)
        var companyId: String? = null
        if (!deal.merchant.isNullOrBlank()) {
            runCatching { companyMatcher.match(deal.merchant) }
                .getOrNull()?.get("id")?.jsonPrimitive?.contentOrNull
                ?.let { companyId = it }
        }

        // Step 6: URL resolution — get direct merchant link from aggregator page
        var primaryUrl = deal.url
        var sourceUrl: String? = null
        if ("slickdeals.net" in deal.url || "dealnews.com" in deal.url) {
            runCatching { urlResolver.resolve(deal.url) }.getOrNull()?.let { resolved ->
                if (resolved != deal.url) {
                    sourceUrl = deal.url
                    primaryUrl = resolved
                    log.debug("[$source] Resolved URL: ${deal.url.take(40)} → ${resolved.take(40)}")
                }
            }
        }

        // Step 7: Image extraction (only if RSS didn't provide one)
        var imageUrl = deal.imageUrl
        if (imageUrl.isNullOrBlank()) {
            runCatching { imageExtractor.extractPrimary(primaryUrl) }.getOrNull()
                ?.let { imageUrl = it }
        }

        // Step 8: Insert — DbMapper translates our readable field names to DB column names
        val insertData = DbMapper.dealRow(
            title        = deal.title,
            url          = primaryUrl,
            sourceUrl    = sourceUrl,
            source       = source,
            description  = deal.description,
            imageUrl     = imageUrl,
            price        = deal.price,
            listPrice    = deal.listPrice,
            merchant     = deal.merchant,
            expiresAt    = deal.expiresAt,
            externalId   = deal.externalId,
            couponCode   = deal.couponCode,
            companyId    = companyId,
            qualityScore = deal.qualityScore
        )

        val inserted = supabase.insert("deals", insertData)
        return if (inserted != null) {
            DailyCapTracker.increment(source)
            val ms = System.currentTimeMillis() - startMs
            log.info("[$source] Created deal in ${ms}ms: '${deal.title.take(60)}'")
            ProcessResult("created", id = inserted["id"]?.jsonPrimitive?.contentOrNull)
        } else {
            log.warn("[$source] Insert failed for '${deal.title.take(50)}'")
            ProcessResult("error", reason = "insert_failed")
        }
    }

    suspend fun processBatch(deals: List<RawDeal>, source: String): BatchResult {
        log.info("[$source] Processing batch of ${deals.size} deal(s)...")
        val result = BatchResult()
        for (raw in deals) {
            when (process(raw, source).action) {
                "created" -> result.created++
                "updated" -> result.updated++
                "skipped" -> result.skipped++
                else      -> result.errors++
            }
        }
        log.info("[$source] Batch complete — created=${result.created} updated=${result.updated} skipped=${result.skipped} errors=${result.errors}")
        return result
    }

    // --- normalization ---

    private data class NormalizedDeal(
        val title: String,
        val url: String,
        val description: String?,
        val imageUrl: String?,
        val price: Double?,
        val listPrice: Double?,
        val merchant: String?,
        val category: String?,
        val expiresAt: String?,
        val externalId: String?,
        val couponCode: String?,
        val qualityScore: Double
    )

    private fun normalize(raw: RawDeal, source: String): NormalizedDeal {
        val title = cleanText(raw.title).take(IngestionConfig.Validation.Deal.MAX_TITLE_LENGTH)
        val desc = raw.description?.let { cleanText(it).take(IngestionConfig.Validation.Deal.MAX_DESCRIPTION_LENGTH) }?.ifBlank { null }

        var score = 0.5
        if (!raw.imageUrl.isNullOrBlank()) score += 0.1
        if (!desc.isNullOrBlank() && desc.length > 50) score += 0.1
        if (raw.price != null && raw.listPrice != null) score += 0.1
        if (!raw.expiresAt.isNullOrBlank()) score += 0.05
        if (!raw.category.isNullOrBlank()) score += 0.05
        if (IngestionConfig.AutoApproval.TRUSTED_SOURCES.contains(source)) score += 0.1

        return NormalizedDeal(
            title = title,
            url = raw.url.trim(),
            description = desc,
            imageUrl = raw.imageUrl?.trim()?.ifBlank { null },
            price = raw.price,
            listPrice = raw.listPrice,
            merchant = raw.merchant?.trim()?.ifBlank { null },
            category = raw.category?.trim()?.ifBlank { null },
            expiresAt = raw.expiresAt,
            externalId = raw.externalId,
            couponCode = raw.couponCode?.uppercase()?.trim()?.ifBlank { null },
            qualityScore = minOf(1.0, score)
        )
    }

    // --- validation ---

    private fun validate(deal: NormalizedDeal): Pair<Boolean, List<String>> {
        val errors = mutableListOf<String>()
        val cfg = IngestionConfig.Validation.Deal

        if (deal.title.length < cfg.MIN_TITLE_LENGTH)
            errors += "Title too short (${deal.title.length} < ${cfg.MIN_TITLE_LENGTH})"

        if (!isValidUrl(deal.url)) errors += "Invalid URL"

        deal.price?.let { p ->
            if (p < cfg.MIN_PRICE) errors += "Price below minimum"
            if (p > cfg.MAX_PRICE) errors += "Price exceeds maximum"
            deal.listPrice?.let { lp ->
                if (p >= lp) errors += "Sale price must be less than list price"
                else {
                    val disc = (lp - p) / lp * 100
                    if (disc < cfg.MIN_DISCOUNT) errors += "Discount too small (${String.format("%.1f", disc)}%)"
                }
            }
        }

        deal.expiresAt?.let { exp ->
            runCatching { java.time.Instant.parse(exp) }
                .onFailure { errors += "Invalid expiry date" }
                .onSuccess { if (it.isBefore(java.time.Instant.now())) errors += "Deal already expired" }
        }

        return (errors.isEmpty()) to errors
    }

    private fun isValidUrl(url: String) = runCatching { URI(url); true }.getOrDefault(false)

    private fun cleanText(text: String) = text
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()
}
