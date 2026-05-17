package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.middleware.optionalAuth
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
 * Analytics tracking route mounted at /api/analytics.
 * Accepts arbitrary event tracking from the React client.
 */
fun Route.analyticsRoutes() {
    val supabase: SupabaseAdmin by inject()

    route("/analytics") {

        authenticate("auth-optional") {

            /**
             * POST /api/analytics/track — record an analytics event.
             * Accepts optional user_id; falls back to authenticated user or anonymous.
             */
            post("/track") {
                val user = call.optionalAuth()
                val body = call.receive<TrackEventRequest>()

                val eventName = body.event ?: body.eventName
                    ?: throw BadRequestException("event or event_name is required")

                val userId = body.userId ?: user?.userId

                val data = buildJsonObject {
                    userId?.let { put("user_id", it) }
                    put("event_name", eventName)
                    body.properties?.let { put("properties", it) }
                }

                // Gracefully handle missing analytics table — it's optional infrastructure
                val result = runCatching { supabase.insert("analytics_events", data) }
                if (result.isFailure) {
                    call.respond(HttpStatusCode.NotImplemented,
                        successResponse("Analytics table not provisioned"))
                    return@post
                }

                call.respond(HttpStatusCode.Created, successResponse(mapOf("ok" to true)))
            }
        }
    }
}

@Serializable
private data class TrackEventRequest(
    val event: String? = null,
    val eventName: String? = null,
    val properties: JsonObject? = null,
    val userId: String? = null
)
