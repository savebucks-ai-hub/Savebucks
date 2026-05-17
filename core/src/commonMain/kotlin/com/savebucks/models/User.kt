package com.savebucks.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public user profile as returned by the API. Sensitive fields like
 * email are intentionally omitted from list endpoints; they only appear
 * in /me and admin routes.
 */
@Serializable
data class UserProfile(
    val id: String,
    val handle: String,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    val location: String? = null,
    val karma: Int = 0,
    val xp: Int = 0,
    val level: Int = 1,
    /** user | moderator | admin */
    val role: String = "user",
    @SerialName("is_banned") val isBanned: Boolean = false,
    @SerialName("is_shadow_banned") val isShadowBanned: Boolean = false,
    @SerialName("deal_count") val dealCount: Int = 0,
    @SerialName("coupon_count") val couponCount: Int = 0,
    @SerialName("follower_count") val followerCount: Int = 0,
    @SerialName("following_count") val followingCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = ""
)
