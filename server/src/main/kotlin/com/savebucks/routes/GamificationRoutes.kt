package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.NotFoundException
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
 * Gamification routes mounted at /api/gamification.
 * XP system, achievements, badges, and leaderboards.
 */
fun Route.gamificationRoutes() {
    val supabase: SupabaseAdmin by inject()

    route("/gamification") {

        /** GET /api/gamification/users/:handle/xp — XP totals, level, and streak. */
        get("/users/{handle}/xp") {
            val handle = call.parameters["handle"] ?: throw BadRequestException("Handle required")
            val profile = supabase.from("profiles")
                .select("id,xp,level,karma,handle,avatar_url")
                .eq("handle", handle)
                .single() ?: throw NotFoundException("User not found")

            val userId = profile["id"]!!.jsonPrimitive.content
            // Calculate level progression via RPC if it exists, otherwise use raw XP
            val levelData = runCatching {
                supabase.rpc("get_user_level_data", buildJsonObject { put("user_id", userId) })
            }.getOrNull()

            call.respond(HttpStatusCode.OK, successResponse(mapOf(
                "profile" to profile,
                "levelData" to levelData
            )))
        }

        /** GET /api/gamification/achievements — all available achievements. */
        get("/achievements") {
            val category = call.parameters["category"]
            val rarity = call.parameters["rarity"]

            var query = supabase.from("achievements")
                .select("*")
                .eq("is_active", true)
                .order("rarity", ascending = true)

            category?.let { query = query.eq("category", it) }
            rarity?.let { query = query.eq("rarity", it) }

            val achievements = query.execute()
            call.respond(HttpStatusCode.OK, successResponse(achievements))
        }

        /** GET /api/gamification/users/:handle/achievements — user's achievement progress. */
        get("/users/{handle}/achievements") {
            val handle = call.parameters["handle"] ?: throw BadRequestException("Handle required")
            val completedOnly = call.parameters["completed_only"] == "true"

            val profile = supabase.from("profiles").select("id").eq("handle", handle).single()
                ?: throw NotFoundException("User not found")
            val userId = profile["id"]!!.jsonPrimitive.content

            var query = supabase.from("user_achievements")
                .select("*,achievements(name,description,badge_url,xp_reward,rarity,category)")
                .eq("user_id", userId)
                .order("earned_at", ascending = false)

            if (completedOnly) query = query.`is`("earned_at", "not.null")

            call.respond(HttpStatusCode.OK, successResponse(query.execute()))
        }

        /** GET /api/gamification/leaderboard — top users by XP/karma. */
        get("/leaderboard") {
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)

            val leaderboard = runCatching {
                supabase.rpc("get_leaderboard", buildJsonObject { put("limit_count", limit) })
            }.getOrNull() ?: supabase.from("profiles")
                .select("id,handle,avatar_url,karma,xp,level")
                .order("karma", ascending = false)
                .limit(limit)
                .execute()
                .let { JsonArray(it) }

            call.respond(HttpStatusCode.OK, successResponse(leaderboard))
        }

        // ─── Admin ───────────────────────────────────────────────────────────

        authenticate("auth") {

            /** GET /api/gamification/users/:handle/xp-events — XP event history. */
            get("/users/{handle}/xp-events") {
                val user = call.requireAuth()
                val handle = call.parameters["handle"] ?: throw BadRequestException("Handle required")
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

                val profile = supabase.from("profiles").select("id").eq("handle", handle).single()
                    ?: throw NotFoundException("User not found")
                val targetId = profile["id"]!!.jsonPrimitive.content

                // Users can only see their own events; admins see all
                if (targetId != user.userId && user.role !in listOf("admin", "moderator")) {
                    throw com.savebucks.lib.ForbiddenException("Cannot view other users' XP events")
                }

                val events = supabase.from("xp_events")
                    .select("*")
                    .eq("user_id", targetId)
                    .order("created_at", ascending = false)
                    .limit(limit)
                    .offset(offset)
                    .execute()

                call.respond(HttpStatusCode.OK, successResponse(events))
            }

            /** POST /api/gamification/admin/award-xp — manually award XP to a user. */
            post("/admin/award-xp") {
                call.requireAdmin()
                val body = call.receive<AwardXpRequest>()

                val result = supabase.rpc("award_xp",
                    buildJsonObject {
                        put("target_user_id", body.userId)
                        put("event_type", body.eventType)
                        put("description", body.description ?: "Manual XP award")
                        put("multiplier", body.multiplier ?: 1.0)
                    }
                )
                call.respond(HttpStatusCode.OK, successResponse(result))
            }

            /** GET /api/gamification/admin/stats — system-wide gamification statistics. */
            get("/admin/stats") {
                call.requireAdmin()
                val totalXp = supabase.from("xp_events").select("xp_amount").execute()
                    .sumOf { it.jsonObject["xp_amount"]?.jsonPrimitive?.intOrNull ?: 0 }

                val activeUsers = supabase.from("profiles")
                    .select("id")
                    .count()

                call.respond(HttpStatusCode.OK, successResponse(mapOf(
                    "totalXpAwarded" to totalXp,
                    "totalUsers" to activeUsers
                )))
            }
        }
    }
}

@Serializable private data class AwardXpRequest(
    val userId: String,
    val eventType: String,
    val description: String? = null,
    val multiplier: Double? = null
)
