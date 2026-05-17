package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.middleware.requireAdmin
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
 * Admin-only management routes mounted at /api/admin.
 * All routes here require admin or moderator role (enforced by requireAdmin()).
 */
fun Route.adminRoutes() {
    val supabase: SupabaseAdmin by inject()

    authenticate("auth") {
        route("/admin") {

            // ─── Deal moderation ─────────────────────────────────────────────

            /** GET /api/admin/deals/pending — list deals awaiting approval. */
            get("/deals/pending") {
                call.requireAdmin()
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

                val deals = supabase.from("deals")
                    .select("*,profiles(handle,email)")
                    .eq("status", "pending")
                    .order("created_at", ascending = true)
                    .limit(limit)
                    .offset(offset)
                    .execute()

                call.respond(HttpStatusCode.OK, successResponse(deals))
            }

            /** PUT /api/admin/deals/:id/approve — approve a pending deal. */
            put("/deals/{id}/approve") {
                call.requireAdmin()
                val id = call.parameters["id"] ?: throw BadRequestException("Deal ID required")

                supabase.update("deals",
                    buildJsonObject { put("status", "approved") }
                ).eq("id", id).execute()

                call.respond(HttpStatusCode.OK, successResponse("Deal approved"))
            }

            /** PUT /api/admin/deals/:id/reject — reject a pending deal with a reason. */
            put("/deals/{id}/reject") {
                call.requireAdmin()
                val id = call.parameters["id"] ?: throw BadRequestException("Deal ID required")
                val body = call.receive<RejectRequest>()

                supabase.update("deals",
                    buildJsonObject {
                        put("status", "rejected")
                        body.reason?.let { put("rejection_reason", it) }
                    }
                ).eq("id", id).execute()

                call.respond(HttpStatusCode.OK, successResponse("Deal rejected"))
            }

            // ─── Coupon moderation ───────────────────────────────────────────

            /** GET /api/admin/coupons/pending — list coupons awaiting approval. */
            get("/coupons/pending") {
                call.requireAdmin()
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

                val coupons = supabase.from("coupons")
                    .select("*,profiles(handle)")
                    .eq("status", "pending")
                    .order("created_at", ascending = true)
                    .limit(limit)
                    .execute()

                call.respond(HttpStatusCode.OK, successResponse(coupons))
            }

            /** PUT /api/admin/coupons/:id/approve — approve a coupon. */
            put("/coupons/{id}/approve") {
                call.requireAdmin()
                val id = call.parameters["id"] ?: throw BadRequestException("Coupon ID required")
                supabase.update("coupons", buildJsonObject { put("status", "approved") }).eq("id", id).execute()
                call.respond(HttpStatusCode.OK, successResponse("Coupon approved"))
            }

            // ─── User management ─────────────────────────────────────────────

            /** GET /api/admin/users — paginated user list with search. */
            get("/users") {
                call.requireAdmin()
                val search = call.parameters["search"]
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

                var query = supabase.from("profiles")
                    .select("id,handle,email,role,karma,is_banned,created_at")
                    .order("created_at", ascending = false)
                    .limit(limit)
                    .offset(offset)

                search?.let { query = query.ilike("handle", "%$it%") }

                call.respond(HttpStatusCode.OK, successResponse(query.execute()))
            }

            /** PUT /api/admin/users/:id/ban — ban a user. */
            put("/users/{id}/ban") {
                call.requireAdmin()
                val id = call.parameters["id"] ?: throw BadRequestException("User ID required")

                supabase.update("profiles",
                    buildJsonObject { put("is_banned", true) }
                ).eq("id", id).execute()

                call.respond(HttpStatusCode.OK, successResponse("User banned"))
            }

            /** PUT /api/admin/users/:id/unban — unban a user. */
            put("/users/{id}/unban") {
                call.requireAdmin()
                val id = call.parameters["id"] ?: throw BadRequestException("User ID required")

                supabase.update("profiles",
                    buildJsonObject { put("is_banned", false) }
                ).eq("id", id).execute()

                call.respond(HttpStatusCode.OK, successResponse("User unbanned"))
            }

            // ─── System stats ────────────────────────────────────────────────

            /** GET /api/admin/stats — high-level system statistics. */
            get("/stats") {
                call.requireAdmin()

                val dealCount = supabase.from("deals").select("id").count()
                val pendingDeals = supabase.from("deals").select("id").eq("status", "pending").count()
                val userCount = supabase.from("profiles").select("id").count()
                val couponCount = supabase.from("coupons").select("id").count()

                call.respond(HttpStatusCode.OK, successResponse(mapOf(
                    "totalDeals" to dealCount,
                    "pendingDeals" to pendingDeals,
                    "totalUsers" to userCount,
                    "totalCoupons" to couponCount
                )))
            }
        }
    }
}

@Serializable private data class RejectRequest(val reason: String? = null)
