package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.middleware.requireAdmin
import com.savebucks.middleware.requireAuth
import com.savebucks.models.successResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.koin.ktor.ext.inject

/**
 * Notification and web-push routes mounted at /api/notifications.
 * Handles in-app notifications, push subscriptions, and admin broadcast.
 */
fun Route.notificationsRoutes() {
    val supabase: SupabaseAdmin by inject()

    route("/notifications") {

        authenticate("auth") {

            /** GET /api/notifications — paginated notification list for the current user. */
            get {
                val user = call.requireAuth()
                val unreadOnly = call.parameters["unread"] == "true"
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

                var query = supabase.from("notification_queue")
                    .select("*")
                    .eq("user_id", user.userId)
                    .order("created_at", ascending = false)
                    .limit(limit)
                    .offset(offset)

                if (unreadOnly) query = query.eq("is_read", false)

                val notifications = query.execute()
                call.respond(HttpStatusCode.OK, successResponse(notifications))
            }

            /** PUT /api/notifications/:id/read — mark a single notification as read. */
            put("/{id}/read") {
                val user = call.requireAuth()
                val id = call.parameters["id"] ?: throw BadRequestException("Notification ID required")

                supabase.update("notification_queue",
                    buildJsonObject { put("is_read", true) }
                ).eq("id", id).eq("user_id", user.userId).execute()

                call.respond(HttpStatusCode.OK, successResponse("Marked as read"))
            }

            /** PUT /api/notifications/read-all — mark all notifications as read. */
            put("/read-all") {
                val user = call.requireAuth()
                supabase.update("notification_queue",
                    buildJsonObject { put("is_read", true) }
                ).eq("user_id", user.userId).eq("is_read", false).execute()
                call.respond(HttpStatusCode.OK, successResponse("All notifications marked as read"))
            }

            /** GET /api/notifications/preferences — load notification preferences. */
            get("/preferences") {
                val user = call.requireAuth()
                val prefs = supabase.from("user_notification_preferences")
                    .select("*")
                    .eq("user_id", user.userId)
                    .single()

                // Return defaults if the row doesn't exist yet
                call.respond(HttpStatusCode.OK, successResponse(prefs ?: buildJsonObject {
                    put("user_id", user.userId)
                    put("quiet_hours_start", 22)
                    put("quiet_hours_end", 8)
                    put("max_daily_notifications", 10)
                }))
            }

            /** PUT /api/notifications/preferences — update preferences. */
            put("/preferences") {
                val user = call.requireAuth()
                val body = call.receive<NotificationPreferencesRequest>()

                val data = buildJsonObject {
                    put("user_id", user.userId)
                    body.dealComments?.let { put("deal_comments", it) }
                    body.dealVotes?.let { put("deal_votes", it) }
                    body.priceDrops?.let { put("price_drops", it) }
                    body.newFollowers?.let { put("new_followers", it) }
                    body.quietHoursStart?.let { put("quiet_hours_start", it) }
                    body.quietHoursEnd?.let { put("quiet_hours_end", it) }
                }

                supabase.upsert("user_notification_preferences", data, onConflict = "user_id")
                call.respond(HttpStatusCode.OK, successResponse("Preferences updated"))
            }

            // ─── Web Push ────────────────────────────────────────────────────

            /** POST /api/notifications/push/subscribe — store a push subscription. */
            post("/push/subscribe") {
                val user = call.requireAuth()
                val body = call.receive<PushSubscriptionRequest>()

                supabase.upsert("push_subscriptions",
                    buildJsonObject {
                        put("user_id", user.userId)
                        put("endpoint", body.endpoint)
                        put("p256dh", body.p256dh)
                        put("auth", body.auth)
                    },
                    onConflict = "endpoint"
                )
                call.respond(HttpStatusCode.OK, successResponse("Subscription saved"))
            }

            /** DELETE /api/notifications/push/subscribe — remove a push subscription. */
            delete("/push/subscribe") {
                val user = call.requireAuth()
                val body = call.receive<UnsubscribeRequest>()
                supabase.delete("push_subscriptions")
                    .eq("endpoint", body.endpoint)
                    .eq("user_id", user.userId)
                    .execute()
                call.respond(HttpStatusCode.OK, successResponse("Subscription removed"))
            }

            // ─── Admin ───────────────────────────────────────────────────────

            /** POST /api/notifications/admin/broadcast — push to all subscribed users. */
            post("/admin/broadcast") {
                call.requireAdmin()
                val body = call.receive<BroadcastRequest>()

                // Queue broadcast notification — actual sending done by the worker
                val count = supabase.from("profiles").select("id").count()
                supabase.insert("notification_queue", buildJsonObject {
                    put("type", "system_announcement")
                    put("title", body.title)
                    put("body", body.message)
                    put("broadcast", true)
                })

                call.respond(HttpStatusCode.OK, successResponse(mapOf(
                    "message" to "Broadcast queued",
                    "recipientCount" to count
                )))
            }
        }
    }
}

@Serializable private data class NotificationPreferencesRequest(
    val dealComments: Boolean? = null, val dealVotes: Boolean? = null,
    val priceDrops: Boolean? = null, val newFollowers: Boolean? = null,
    val quietHoursStart: Int? = null, val quietHoursEnd: Int? = null
)
@Serializable private data class PushSubscriptionRequest(val endpoint: String, val p256dh: String, val auth: String)
@Serializable private data class UnsubscribeRequest(val endpoint: String)
@Serializable private data class BroadcastRequest(val title: String, val message: String)
