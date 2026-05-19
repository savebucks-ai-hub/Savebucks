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
 * Coupon CRUD and interaction routes mounted at /api/coupons.
 * Structure mirrors the original Express coupons.js.
 */
fun Route.couponsRoutes() {
    val supabase: SupabaseAdmin by inject()

    route("/coupons") {

        /**
         * GET /api/coupons — paginated coupon listing with filters.
         */
        get {
            val page = call.parameters["page"]?.toIntOrNull() ?: 1
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
            val offset = (page - 1) * limit
            val companySlug = call.parameters["company"]
            val categorySlug = call.parameters["category"]
            val type = call.parameters["type"]
            val search = call.parameters["search"]
            val sort = call.parameters["sort"] ?: "newest"

            var query = supabase.from("coupons")
                .select("id,title,coupon_code,coupon_type,discount_value,success_rate,is_featured,category_id,expires_at,created_at,views_count,clicks_count,verification_count,used_count")
                .eq("status", "approved")

            categorySlug?.let { query = query.eq("category_id", it) }
            type?.let { query = query.eq("coupon_type", it) }
            search?.let { query = query.ilike("title", "%$it%") }

            query = when (sort) {
                "oldest" -> query.order("created_at", ascending = true)
                "expiring" -> query.order("expires_at", ascending = true)
                "popular" -> query.order("clicks_count", ascending = false)
                "success_rate" -> query.order("success_rate", ascending = false)
                else -> query.order("created_at", ascending = false)
            }

            val coupons = query.limit(limit).offset(offset).execute()
            call.respond(HttpStatusCode.OK, buildJsonObject {
                put("success", true)
                putJsonObject("data") {
                    putJsonArray("coupons") { coupons.forEach { add(it) } }
                    put("page", page)
                    put("limit", limit)
                    put("hasMore", coupons.size >= limit)
                }
            })
        }

        /** GET /api/coupons/:id — full coupon with comments and vote aggregation. */
        get("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Coupon ID required")

            val coupon = supabase.from("coupons")
                .select("*,profiles(handle,avatar_url)")
                .eq("id", id)
                .single() ?: throw NotFoundException("Coupon not found")

            // Track view
            runCatching { supabase.rpc("increment_coupon_views", buildJsonObject { put("coupon_id", id) }) }

            val comments = supabase.from("coupon_comments")
                .select("*,profiles(handle,avatar_url)")
                .eq("coupon_id", id)
                .order("created_at", ascending = true)
                .execute()

            call.respond(HttpStatusCode.OK, buildJsonObject {
                put("success", true)
                putJsonObject("data") {
                    put("coupon", coupon)
                    putJsonArray("comments") { comments.forEach { add(it) } }
                }
            })
        }

        authenticate("auth") {

            /** POST /api/coupons — submit a new coupon. */
            post {
                val user = call.requireAuth()
                val body = call.receive<CreateCouponRequest>()

                if (body.title.isBlank()) throw BadRequestException("Title is required")
                if (body.code.isBlank()) throw BadRequestException("Coupon code is required")

                val data = buildJsonObject {
                    put("title", body.title)
                    put("code", body.code.uppercase().trim())
                    put("type", body.type)
                    put("status", "pending")
                    put("submitter_id", user.userId)
                    body.companyId?.let { put("company_id", it) }
                    body.discountValue?.let { put("discount_value", it) }
                    body.minOrderAmount?.let { put("min_order_amount", it) }
                    body.categoryId?.let { put("category_id", it) }
                    body.description?.let { put("description", it) }
                    body.terms?.let { put("terms", it) }
                    body.expiresAt?.let { put("expires_at", it) }
                }

                val created = supabase.insert("coupons", data)
                call.respond(HttpStatusCode.Created, successResponse(created))
            }

            /** POST /api/coupons/:id/vote — upvote or downvote a coupon. */
            post("/{id}/vote") {
                val user = call.requireAuth()
                val couponId = call.parameters["id"] ?: throw BadRequestException("Coupon ID required")
                val body = call.receive<VoteRequest>()

                if (body.value !in listOf(1, -1)) throw BadRequestException("Vote must be 1 or -1")

                supabase.upsert("coupon_votes",
                    buildJsonObject {
                        put("coupon_id", couponId)
                        put("user_id", user.userId)
                        put("value", body.value)
                    },
                    onConflict = "coupon_id,user_id"
                )
                call.respond(HttpStatusCode.OK, successResponse("Vote recorded"))
            }

            /** POST /api/coupons/:id/use — track coupon usage / success. */
            post("/{id}/use") {
                val couponId = call.parameters["id"] ?: throw BadRequestException("Coupon ID required")
                val body = call.receive<UseCouponRequest>()

                supabase.rpc("track_coupon_usage",
                    buildJsonObject {
                        put("coupon_id", couponId)
                        body.orderAmount?.let { put("order_amount", it) }
                        body.success?.let { put("success", it) }
                    }
                )
                call.respond(HttpStatusCode.OK, successResponse("Usage tracked"))
            }

            /** POST /api/coupons/:id/click — analytics click tracking. */
            post("/{id}/click") {
                val couponId = call.parameters["id"] ?: throw BadRequestException("Coupon ID required")
                runCatching { supabase.rpc("increment_coupon_clicks", buildJsonObject { put("coupon_id", couponId) }) }
                call.respond(HttpStatusCode.OK, successResponse("Click tracked"))
            }
        }
    }
}

@Serializable
private data class CreateCouponRequest(
    val title: String,
    val code: String,
    val type: String = "percent",
    val companyId: String? = null,
    val discountValue: Double? = null,
    val minOrderAmount: Double? = null,
    val categoryId: String? = null,
    val description: String? = null,
    val terms: String? = null,
    val expiresAt: String? = null
)

@Serializable public data class VoteRequest(val value: Int)
@Serializable private data class UseCouponRequest(val orderAmount: Double? = null, val success: Boolean? = null)
