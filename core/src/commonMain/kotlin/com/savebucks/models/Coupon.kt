package com.savebucks.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a coupon/promo code submitted by a user or ingested automatically.
 * Mirrors the `coupons` table in Supabase.
 */
@Serializable
data class Coupon(
    val id: String,
    val title: String,
    val code: String,
    /** percent | fixed | free_shipping | bogo */
    val type: String = "percent",
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("company_slug") val companySlug: String? = null,
    @SerialName("company_name") val companyName: String? = null,
    @SerialName("discount_value") val discountValue: Double? = null,
    @SerialName("min_order_amount") val minOrderAmount: Double? = null,
    @SerialName("max_discount") val maxDiscount: Double? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_slug") val categorySlug: String? = null,
    val description: String? = null,
    val terms: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    /** pending | approved | rejected | expired */
    val status: String = "pending",
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @SerialName("is_verified") val isVerified: Boolean = false,
    /** Percentage of users who report it worked (0-100) */
    @SerialName("success_rate") val successRate: Int = 0,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val score: Int = 0,
    @SerialName("view_count") val viewCount: Int = 0,
    @SerialName("click_count") val clickCount: Int = 0,
    @SerialName("use_count") val useCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("submitter_id") val submitterId: String? = null,
    @SerialName("submitter_handle") val submitterHandle: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val tags: List<String> = emptyList(),
    val savings: Double? = null,
    /** true for service-business coupons that must be redeemed at a physical location */
    @SerialName("is_instore") val isInstore: Boolean = false,
    /** false only for purely in-store coupons (most promo codes default to online) */
    @SerialName("is_online") val isOnline: Boolean = true
)
