package com.savebucks.worker.jobs.ingestion

import kotlinx.serialization.json.*

/**
 * Single source of truth for translating our readable internal field names
 * to the actual Supabase DB column names.
 *
 * Rule: only this file knows DB column names.
 * Processors, models, and all business logic use our names.
 *
 * ── Deals table translations ──────────────────────────────────────────────────
 *   listPrice    → original_price     (DB calls it original, we call it list)
 *   category     → (skipped)          (DB has category_id FK, not a string column)
 *
 * ── Coupons table translations ────────────────────────────────────────────────
 *   url          → source_url         (our URL is the aggregator source link)
 *   imageUrl     → featured_image     (DB uses featured_image, not image_url)
 *   merchant     → (skipped)          (no text merchant column; link goes via company_id)
 *   discountType → coupon_type        (DB name differs; value "percent" → "percentage")
 */
object DbMapper {

    fun dealRow(
        title: String,
        url: String,
        sourceUrl: String?,
        source: String,
        description: String?,
        imageUrl: String?,
        price: Double?,
        listPrice: Double?,          // → original_price in DB
        merchant: String?,
        expiresAt: String?,
        externalId: String?,
        couponCode: String?,
        companyId: String?,
        qualityScore: Double,
        status: String = "pending",
        dealType: String = "discount"
    ): JsonObject = buildJsonObject {
        put("title", title)
        put("url", url)
        put("source_url", sourceUrl ?: url)
        put("source", source)
        put("status", status)
        put("deal_type", dealType)
        put("quality_score", qualityScore)
        description?.let { put("description", it) }
        imageUrl?.let    { put("image_url", it) }
        price?.let       { put("price", it) }
        listPrice?.let   { put("original_price", it) }  // listPrice → original_price
        merchant?.let    { put("merchant", it) }
        // category (plain string) has no matching DB column — DB uses category_id (FK)
        expiresAt?.let   { put("expires_at", it) }
        externalId?.let  { put("external_id", it) }
        couponCode?.let  { put("coupon_code", it) }
        companyId?.let   { put("company_id", it) }
    }

    fun couponRow(
        title: String,
        url: String,                 // → source_url in DB
        source: String,
        description: String?,
        imageUrl: String?,           // → featured_image in DB
        couponCode: String?,
        discountValue: Double?,
        discountType: String?,       // → coupon_type in DB; "percent" → "percentage"
        expiresAt: String?,
        externalId: String?,
        companyId: String?,
        // merchant has no direct text column — company link goes through companyId
        status: String = "pending"
    ): JsonObject = buildJsonObject {
        put("title", title)
        put("source_url", url)       // url → source_url
        put("source", source)
        put("status", status)
        description?.let { put("description", it) }
        imageUrl?.let    { put("featured_image", it) }  // imageUrl → featured_image
        couponCode?.let  { put("coupon_code", it) }
        discountValue?.let { put("discount_value", it) }
        discountType?.let {
            // discountType → coupon_type; DB enum uses "percentage" not "percent"
            put("coupon_type", if (it == "percent") "percentage" else it)
        }
        expiresAt?.let  { put("expires_at", it) }
        externalId?.let { put("external_id", it) }
        companyId?.let  { put("company_id", it) }
    }
}
