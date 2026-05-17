package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.NotFoundException
import com.savebucks.lib.supabase.SupabaseAdmin
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
 * User profile routes mounted at /api/users.
 * Covers public profiles, social graph (follow/unfollow), leaderboard, and achievements.
 */
fun Route.usersRoutes() {
    val supabase: SupabaseAdmin by inject()

    route("/users") {

        /** GET /api/users/:identifier/profile — public profile with aggregated stats. */
        get("/{identifier}/profile") {
            val identifier = call.parameters["identifier"] ?: throw BadRequestException("User identifier required")

            // Support both UUID and handle as identifier
            val isUuid = identifier.contains('-') && identifier.length > 30
            val query = if (isUuid) supabase.from("profiles").select("*").eq("id", identifier)
                        else supabase.from("profiles").select("*").eq("handle", identifier)

            val profile = query.single() ?: throw NotFoundException("User not found")
            call.respond(HttpStatusCode.OK, successResponse(profile))
        }

        /** GET /api/users/:handle/deals — paginated deals submitted by a user. */
        get("/{handle}/deals") {
            val handle = call.parameters["handle"] ?: throw BadRequestException("Handle required")
            val page = call.parameters["page"]?.toIntOrNull() ?: 1
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
            val offset = (page - 1) * limit

            val profile = supabase.from("profiles").select("id").eq("handle", handle).single()
                ?: throw NotFoundException("User not found")
            val userId = profile["id"]!!.jsonPrimitive.content

            val deals = supabase.from("deals")
                .select("id,title,url,price,merchant,discount_percent,image_url,score,created_at")
                .eq("submitter_id", userId)
                .eq("status", "approved")
                .order("created_at", ascending = false)
                .limit(limit)
                .offset(offset)
                .execute()

            call.respond(HttpStatusCode.OK, successResponse(mapOf("deals" to deals, "page" to page)))
        }

        /** GET /api/users/leaderboard/:period — karma leaderboard for week/month/year/all. */
        get("/leaderboard/{period}") {
            val period = call.parameters["period"] ?: "all"
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)

            val leaderboard = supabase.from("profiles")
                .select("id,handle,avatar_url,karma,level,xp")
                .order("karma", ascending = false)
                .limit(limit)
                .execute()

            call.respond(HttpStatusCode.OK, successResponse(leaderboard))
        }

        /** GET /api/users/:handle/achievements — user achievements and badges. */
        get("/{handle}/achievements") {
            val handle = call.parameters["handle"] ?: throw BadRequestException("Handle required")

            val profile = supabase.from("profiles").select("id").eq("handle", handle).single()
                ?: throw NotFoundException("User not found")
            val userId = profile["id"]!!.jsonPrimitive.content

            val achievements = supabase.from("user_achievements")
                .select("*,achievements(name,description,badge_url,xp_reward,rarity)")
                .eq("user_id", userId)
                .order("earned_at", ascending = false)
                .execute()

            call.respond(HttpStatusCode.OK, successResponse(achievements))
        }

        authenticate("auth") {

            /** POST /api/users/:handle/follow — follow or unfollow another user. */
            post("/{handle}/follow") {
                val currentUser = call.requireAuth()
                val handle = call.parameters["handle"] ?: throw BadRequestException("Handle required")

                val target = supabase.from("profiles").select("id").eq("handle", handle).single()
                    ?: throw NotFoundException("User not found")
                val targetId = target["id"]!!.jsonPrimitive.content

                if (targetId == currentUser.userId) throw BadRequestException("Cannot follow yourself")

                // Check existing follow
                val existing = supabase.from("follows")
                    .select("id")
                    .eq("follower_id", currentUser.userId)
                    .eq("following_id", targetId)
                    .single()

                if (existing != null) {
                    // Unfollow
                    supabase.delete("follows")
                        .eq("follower_id", currentUser.userId)
                        .eq("following_id", targetId)
                        .execute()
                    call.respond(HttpStatusCode.OK, successResponse(mapOf("following" to false)))
                } else {
                    // Follow
                    supabase.insert("follows",
                        buildJsonObject {
                            put("follower_id", currentUser.userId)
                            put("following_id", targetId)
                        }
                    )
                    call.respond(HttpStatusCode.OK, successResponse(mapOf("following" to true)))
                }
            }

            /** GET /api/users/:identifier/follow-status — check if current user follows the target. */
            get("/{identifier}/follow-status") {
                val currentUser = call.requireAuth()
                val identifier = call.parameters["identifier"] ?: throw BadRequestException("Identifier required")

                val target = if (identifier.contains('-') && identifier.length > 30)
                    supabase.from("profiles").select("id").eq("id", identifier).single()
                else
                    supabase.from("profiles").select("id").eq("handle", identifier).single()

                target ?: throw NotFoundException("User not found")
                val targetId = target["id"]!!.jsonPrimitive.content

                val follows = supabase.from("follows")
                    .select("id")
                    .eq("follower_id", currentUser.userId)
                    .eq("following_id", targetId)
                    .single()

                call.respond(HttpStatusCode.OK, successResponse(mapOf("following" to (follows != null))))
            }

            /** POST /api/users/session/heartbeat — update last_active timestamp. */
            post("/session/heartbeat") {
                val user = call.requireAuth()
                supabase.update("profiles",
                    buildJsonObject { put("last_active_at", "now()") }
                ).eq("id", user.userId).execute()
                call.respond(HttpStatusCode.OK, successResponse("ok"))
            }
        }
    }
}
