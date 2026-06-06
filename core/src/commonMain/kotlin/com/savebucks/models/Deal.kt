package com.savebucks.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a deal or promotion submitted by a user or ingested from
 * an external source. Mirrors the `deals` table in Supabase.
 */
@Serializable
data class Deal(
    val id: String,
    val title: String,
    val url: String,
    val price: Double? = null,
    @SerialName("original_price") val originalPrice: Double? = null,
    val merchant: String? = null,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val images: List<String> = emptyList(),
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_slug") val categorySlug: String? = null,
    val category: String? = null,
    /** pending | approved | rejected | expired */
    val status: String = "pending",
    @SerialName("is_coupon") val isCoupon: Boolean = false,
    @SerialName("coupon_code") val couponCode: String? = null,
    @SerialName("discount_percent") val discountPercent: Int? = null,
    @SerialName("submitter_id") val submitterId: String? = null,
    @SerialName("submitter_handle") val submitterHandle: String? = null,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val score: Int = 0,
    @SerialName("hot_score") val hotScore: Double = 0.0,
    @SerialName("view_count") val viewCount: Int = 0,
    @SerialName("click_count") val clickCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @SerialName("is_expired") val isExpired: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("free_shipping") val freeShipping: Boolean = false,
    @SerialName("stock_status") val stockStatus: String? = null,
    /** true when deal requires physical store visit or in-store pickup */
    @SerialName("is_instore") val isInstore: Boolean = false,
    /** false only for truly local-only deals (nearly all scraped deals are online) */
    @SerialName("is_online") val isOnline: Boolean = true,
    /** Computed savings = originalPrice - price */
    val savings: Double? = null
)

/**
 * Minimal projection used in feed and list endpoints to reduce payload size.
 */
@Serializable
data class DealSummary(
    val id: String,
    val title: String,
    val url: String,
    val price: Double? = null,
    @SerialName("original_price") val originalPrice: Double? = null,
    val merchant: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("discount_percent") val discountPercent: Int? = null,
    val score: Int = 0,
    @SerialName("hot_score") val hotScore: Double = 0.0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val savings: Double? = null
)
