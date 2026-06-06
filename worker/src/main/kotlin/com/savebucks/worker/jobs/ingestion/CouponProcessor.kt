package com.savebucks.worker.jobs.ingestion

import com.savebucks.worker.lib.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(CouponProcessor::class.java)

data class RawCoupon(
    val title: String,
    val url: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val merchant: String? = null,
    val couponCode: String? = null,
    val discountValue: Double? = null,
    val discountType: String? = null, // "percent" | "fixed"
    val expiresAt: String? = null,
    val externalId: String? = null,
    val isInstore: Boolean = false
)

/**
 * Coupon-specific processing pipeline — mirrors couponProcessor.js.
 *
 * Validates, normalizes, deduplicates (by URL and coupon code), matches
 * company, then inserts into the coupons table with status = "pending".
 */
class CouponProcessor(
    private val supabase: SupabaseWorkerClient,
    private val companyMatcher: CompanyMatcher
) {
    // Extracts codes like "w/ code SAVE20", "code: SUMMER", "promo DEAL15"
    private val CODE_REGEX = Regex(
        """(?:w/\s*code|code[:\s]|promo(?:\s*code)?[:\s]|coupon(?:\s*code)?[:\s])\s*([A-Z0-9]{3,20})""",
        RegexOption.IGNORE_CASE
    )

    // Signals that a coupon must be redeemed at a physical location
    private val instorePattern = Regex(
        "in[- ]store|in\\s+store\\s+only|show\\s+in\\s+store|present\\s+at\\s+register|" +
        "bring\\s+to\\s+store|valid\\s+in[- ]store|printable\\s+coupon|redeem\\s+in[- ]store|" +
        "in-store\\s+coupon|store\\s+coupon|at\\s+(the\\s+)?register|walk[- ]in|" +
        "ymmv|your\\s+mileage\\s+may\\s+vary",
        RegexOption.IGNORE_CASE
    )

    suspend fun process(raw: RawCoupon, source: String): ProcessResult {
        val title = cleanText(raw.title).take(IngestionConfig.Validation.Coupon.MAX_TITLE_LENGTH)

        if (title.length < IngestionConfig.Validation.Coupon.MIN_TITLE_LENGTH)
            return ProcessResult("skipped", reason = "title_too_short")

        // Expiry check
        if (raw.expiresAt != null) {
            runCatching { java.time.Instant.parse(raw.expiresAt) }
                .onSuccess { if (it.isBefore(java.time.Instant.now()))
                    return ProcessResult("skipped", reason = "already_expired") }
        }

        // Coupon code — from field, or extract from title/description
        val rawCode = raw.couponCode?.uppercase()?.trim()?.ifBlank { null }
            ?: extractCode(raw.title)
            ?: extractCode(raw.description ?: "")
        // Validate code length per config
        val couponCode = rawCode?.takeIf { it.length in
            IngestionConfig.Validation.Coupon.MIN_CODE_LENGTH..IngestionConfig.Validation.Coupon.MAX_CODE_LENGTH
        }

        // Deduplicate by source_url
        val byUrl = supabase.select("coupons", mapOf("source_url" to "eq.${raw.url}", "select" to "id", "limit" to "1"))
        if (byUrl.isNotEmpty())
            return ProcessResult("skipped", reason = "duplicate_url")

        // Deduplicate by coupon code (if present)
        if (couponCode != null) {
            val byCode = supabase.select("coupons", mapOf("coupon_code" to "eq.$couponCode", "select" to "id", "limit" to "1"))
            if (byCode.isNotEmpty())
                return ProcessResult("skipped", reason = "duplicate_coupon_code")
        }

        // Daily cap
        val cap = DailyCapTracker.check(source)
        if (!cap.allowed)
            return ProcessResult("skipped", reason = "daily_cap_reached")

        // Company match
        var companyId: String? = null
        if (!raw.merchant.isNullOrBlank()) {
            runCatching { companyMatcher.match(raw.merchant) }
                .getOrNull()?.get("id")?.jsonPrimitive?.contentOrNull
                ?.let { companyId = it }
        }

        // Detect in-store: check raw flag first, then scan title + description
        val isInstore = raw.isInstore ||
            instorePattern.containsMatchIn(raw.title) ||
            (!raw.description.isNullOrBlank() && instorePattern.containsMatchIn(raw.description))

        // DbMapper translates our readable field names to DB column names
        val insertData = DbMapper.couponRow(
            title         = title,
            url           = raw.url,
            source        = source,
            description   = raw.description?.let { cleanText(it).take(IngestionConfig.Validation.Coupon.MAX_DESCRIPTION_LENGTH) },
            imageUrl      = raw.imageUrl,
            couponCode    = couponCode,
            discountValue = raw.discountValue,
            discountType  = raw.discountType,
            expiresAt     = raw.expiresAt,
            externalId    = raw.externalId,
            companyId     = companyId,
            isInstore     = isInstore
            // merchant: no direct DB column — company link goes through companyId above
        )

        val inserted = supabase.insert("coupons", insertData)
        return if (inserted != null) {
            DailyCapTracker.increment(source)
            log.info("[$source] Created coupon: '${title.take(60)}' code=$couponCode")
            ProcessResult("created", id = inserted["id"]?.jsonPrimitive?.contentOrNull)
        } else {
            log.warn("[$source] Insert failed for coupon '${title.take(50)}'")
            ProcessResult("error", reason = "insert_failed")
        }
    }

    suspend fun processBatch(coupons: List<RawCoupon>, source: String): BatchResult {
        log.info("[$source] Processing batch of ${coupons.size} coupon(s)...")
        val result = BatchResult()
        for (raw in coupons) {
            when (process(raw, source).action) {
                "created" -> result.created++
                "updated" -> result.updated++
                "skipped" -> result.skipped++
                else      -> result.errors++
            }
        }
        log.info("[$source] Batch complete — created=${result.created} skipped=${result.skipped} errors=${result.errors}")
        return result
    }

    private fun extractCode(text: String): String? =
        CODE_REGEX.find(text)?.groupValues?.get(1)?.uppercase()?.ifBlank { null }

    private fun cleanText(text: String) = text
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()
}
