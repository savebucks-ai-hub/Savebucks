package com.savebucks.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * In-app notification stored in the `notification_queue` table.
 * Also used to drive web-push payloads when the user has a push subscription.
 */
@Serializable
data class Notification(
    val id: String,
    @SerialName("user_id") val userId: String,
    /** deal_comment | coupon_vote | deal_approved | price_drop | follow | achievement | system */
    val type: String,
    val title: String,
    val body: String,
    @SerialName("is_read") val isRead: Boolean = false,
    /** JSON-encoded extra payload (deal/coupon metadata, deep-link URL, etc.) */
    val data: String? = null,
    @SerialName("deal_id") val dealId: String? = null,
    @SerialName("coupon_id") val couponId: String? = null,
    /** pending | sent | failed */
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String = ""
)

/** Notification preferences stored per-user. */
@Serializable
data class NotificationPreferences(
    @SerialName("user_id") val userId: String,
    @SerialName("deal_comments") val dealComments: Boolean = true,
    @SerialName("deal_votes") val dealVotes: Boolean = true,
    @SerialName("coupon_votes") val couponVotes: Boolean = true,
    @SerialName("deal_approved") val dealApproved: Boolean = true,
    @SerialName("price_drops") val priceDrops: Boolean = true,
    @SerialName("new_followers") val newFollowers: Boolean = true,
    @SerialName("achievements") val achievements: Boolean = true,
    @SerialName("system_announcements") val systemAnnouncements: Boolean = true,
    /** Quiet hours — no push sent between these hours (24h format) */
    @SerialName("quiet_hours_start") val quietHoursStart: Int = 22,
    @SerialName("quiet_hours_end") val quietHoursEnd: Int = 8,
    @SerialName("max_daily_notifications") val maxDailyNotifications: Int = 10
)
