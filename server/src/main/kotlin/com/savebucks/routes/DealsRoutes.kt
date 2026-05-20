package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.NotFoundException
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.middleware.optionalAuth
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
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("DealsRoutes")

/**
 * Deal management routes mounted at /api/deals.
 * Mirrors the original Express deals.js route file.
 */
fun Route.dealsRoutes() {
    val supabase: SupabaseAdmin by inject()

    route("/deals") {

        /**
         * GET /api/deals — paginated, filtered deal listing.
         * Supports tabs (hot, new, trending, popular) and multiple query filters.
         */
        authenticate("auth-optional", optional = true) {
            get {
                val tab = call.parameters["tab"] ?: "hot"
                val category = call.parameters["category"]
                val merchant = call.parameters["merchant"]
                val search = call.parameters["search"]
                val page = call.parameters["page"]?.toIntOrNull() ?: 1
                val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val offset = (page - 1) * limit

                var query = supabase.from("deals")
                    .select("id,title,url,price,original_price,merchant,discount_percentage,image_url,category_id,quality_score,views_count,clicks_count,is_featured,created_at,expires_at,tags,description,store")
                    .eq("status", "approved")

                category?.let { query = query.eq("category_id", it) }
                merchant?.let { query = query.ilike("merchant", "%$it%") }
                search?.let { query = query.ilike("title", "%$it%") }

                query = when (tab) {
                    "new", "new-arrivals" -> query.order("created_at", ascending = false)
                    "trending", "popular" -> query.order("quality_score", ascending = false)
                    else -> query.order("created_at", ascending = false)
                }

                val deals = query.limit(limit).offset(offset).execute()
                val total = deals.size

                call.respond(HttpStatusCode.OK, buildJsonObject {
                    put("success", true)
                    putJsonObject("data") {
                        putJsonArray("deals") { deals.forEach { add(it) } }
                        put("total", total)
                        put("page", page)
                        put("limit", limit)
                        put("hasMore", deals.size >= limit)
                    }
                })
            }

            /**
             * GET /api/deals/:id — full deal detail with comments and vote aggregation.
             */
            get("/{id}") {
                val id = call.parameters["id"] ?: throw BadRequestException("Deal ID is required")
                val user = call.optionalAuth()

                val deal = supabase.from("deals")
                    .select("*,profiles(handle,avatar_url)")
                    .eq("id", id)
                    .single() ?: throw NotFoundException("Deal not found")

                runCatching { supabase.rpc("increment_deal_views", buildJsonObject { put("deal_id", id) }) }

                val comments = supabase.from("comments")
                    .select("*,profiles(handle,avatar_url)")
                    .eq("deal_id", id)
                    .order("created_at", ascending = true)
                    .execute()

                val userVote = user?.let {
                    supabase.from("votes")
                        .select("value")
                        .eq("deal_id", id)
                        .eq("user_id", it.userId)
                        .single()
                }

                call.respond(HttpStatusCode.OK, buildJsonObject {
                    put("success", true)
                    putJsonObject("data") {
                        put("deal", deal)
                        putJsonArray("comments") { comments.forEach { add(it) } }
                        userVote?.let { put("userVote", it) }
                    }
                })
            }
        }

        /**
         * GET /api/deals/related/:id — returns up to 8 related deals.
         * Priority: same category → same merchant → popular.
         */
        get("/related/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Deal ID is required")

            val deal = supabase.from("deals").select("category_slug,merchant").eq("id", id).single()
                ?: throw NotFoundException("Deal not found")

            val categorySlug = deal["category_slug"]?.jsonPrimitive?.contentOrNull
            val merchant = deal["merchant"]?.jsonPrimitive?.contentOrNull

            val related = mutableListOf<JsonElement>()

            // Same category first
            if (categorySlug != null) {
                val catDeals = supabase.from("deals")
                    .select("id,title,url,price,merchant,discount_percent,image_url,score,hot_score")
                    .eq("status", "approved")
                    .eq("category_slug", categorySlug)
                    .neq("id", id)
                    .order("hot_score", ascending = false)
                    .limit(4)
                    .execute()
                related.addAll(catDeals)
            }

            // Fill remaining slots with same-merchant deals
            if (related.size < 8 && merchant != null) {
                val merchantDeals = supabase.from("deals")
                    .select("id,title,url,price,merchant,discount_percent,image_url,score,hot_score")
                    .eq("status", "approved")
                    .ilike("merchant", "%$merchant%")
                    .neq("id", id)
                    .order("hot_score", ascending = false)
                    .limit(8 - related.size)
                    .execute()
                related.addAll(merchantDeals.filter { item ->
                    related.none { r -> r.jsonObject["id"] == item.jsonObject["id"] }
                })
            }

            call.respond(HttpStatusCode.OK, successResponse(related.take(8)))
        }

        authenticate("auth") {

            /** POST /api/deals — submit a new deal (status = pending, requires admin approval). */
            post {
                val user = call.requireAuth()
                val body = call.receive<CreateDealRequest>()

                if (body.title.isBlank()) throw BadRequestException("Title is required")
                if (body.url.isBlank()) throw BadRequestException("URL is required")

                // Calculate karma reward based on how many optional fields were filled in
                val karma = calculateKarmaPoints(body)

                val data = buildJsonObject {
                    put("title", body.title)
                    put("url", body.url)
                    put("merchant", body.merchant ?: "")
                    put("status", "pending")  // all deals require admin approval
                    put("submitter_id", user.userId)
                    put("karma_reward", karma)
                    body.price?.let { put("price", it) }
                    body.originalPrice?.let { put("original_price", it) }
                    body.description?.let { put("description", it) }
                    body.categoryId?.let { put("category_id", it) }
                    body.couponCode?.let { put("coupon_code", it) }
                    body.discountPercent?.let { put("discount_percent", it) }
                    body.expiresAt?.let { put("expires_at", it) }
                    body.freeShipping?.let { put("free_shipping", it) }
                    body.stockStatus?.let { put("stock_status", it) }
                }

                val created = supabase.insert("deals", data)
                log.info("New deal submitted by ${user.userId}: ${body.title}")
                call.respond(HttpStatusCode.Created, successResponse(created))
            }

            /** POST /api/deals/:id/vote — upvote (+1), downvote (-1), or remove (0) a deal. */
            post("/{id}/vote") {
                val user = call.requireAuth()
                val dealId = call.parameters["id"] ?: throw BadRequestException("Deal ID required")
                val body = call.receive<VoteRequest>()

                if (body.value !in listOf(1, -1, 0)) {
                    throw BadRequestException("Vote value must be 1, -1, or 0")
                }

                if (body.value == 0) {
                    // Remove existing vote
                    supabase.delete("votes").eq("deal_id", dealId).eq("user_id", user.userId).execute()
                } else {
                    // Upsert — handles changing vote direction in one operation
                    supabase.upsert("votes",
                        buildJsonObject {
                            put("deal_id", dealId)
                            put("user_id", user.userId)
                            put("value", body.value)
                        },
                        onConflict = "deal_id,user_id"
                    )
                }

                // Return updated vote counts
                val updated = supabase.rpc("get_deal_vote_counts", buildJsonObject { put("deal_id", dealId) })
                call.respond(HttpStatusCode.OK, successResponse(updated))
            }

            /** POST /api/deals/:id/comment — add a comment (supports threaded replies via parent_id). */
            post("/{id}/comment") {
                val user = call.requireAuth()
                val dealId = call.parameters["id"] ?: throw BadRequestException("Deal ID required")
                val body = call.receive<CommentRequest>()

                if (body.content.length < 3) throw BadRequestException("Comment must be at least 3 characters")

                val data = buildJsonObject {
                    put("deal_id", dealId)
                    put("user_id", user.userId)
                    put("content", body.content)
                    body.parentId?.let { put("parent_id", it) }
                }

                val comment = supabase.insert("comments", data)
                call.respond(HttpStatusCode.Created, successResponse(comment))
            }

            /** POST /api/deals/:id/report — report a deal for moderation. */
            post("/{id}/report") {
                val user = call.requireAuth()
                val dealId = call.parameters["id"] ?: throw BadRequestException("Deal ID required")
                val body = call.receive<ReportRequest>()

                if (body.reason.length < 3 || body.reason.length > 500) {
                    throw BadRequestException("Reason must be 3-500 characters")
                }

                val data = buildJsonObject {
                    put("deal_id", dealId)
                    put("reporter_id", user.userId)
                    put("reason", body.reason)
                }

                supabase.insert("reports", data)
                call.respond(HttpStatusCode.OK, successResponse("Report submitted"))
            }

        }

        /** POST /api/deals/:id/click — track affiliate click (no auth required).
         * TODO-Moved ─outside─the─authenticate("auth")─block─ need to check */
        post("/{id}/click") {
            val dealId = call.parameters["id"] ?: throw BadRequestException("Deal ID required")

            runCatching {
                supabase.rpc("increment_deal_clicks", buildJsonObject { put("deal_id", dealId) })
            }.onFailure {
                supabase.update("deals", buildJsonObject { put("click_count", JsonPrimitive(0)) })
                    .eq("id", dealId).execute()
            }

            call.respond(HttpStatusCode.OK, successResponse("Click tracked"))
        }
    }
}

/** Awards 3-10 karma points based on how many optional deal fields were filled in. */
private fun calculateKarmaPoints(deal: CreateDealRequest): Int {
    val optionalFields = listOf(
        deal.description, deal.merchant, deal.originalPrice?.toString(),
        deal.discountPercent?.toString(), deal.expiresAt, deal.categoryId
    )
    val filled = optionalFields.count { !it.isNullOrBlank() }
    val percent = filled.toDouble() / optionalFields.size
    return (3 + (percent * 7)).toInt().coerceIn(3, 10)
}

// ─── Request bodies ───────────────────────────────────────────────────────────

@Serializable
private data class CreateDealRequest(
    val title: String,
    val url: String,
    val merchant: String? = null,
    val price: Double? = null,
    val originalPrice: Double? = null,
    val description: String? = null,
    val categoryId: String? = null,
    val couponCode: String? = null,
    val discountPercent: Int? = null,
    val expiresAt: String? = null,
    val freeShipping: Boolean? = null,
    val stockStatus: String? = null
)

@Serializable private data class CommentRequest(val content: String, val parentId: String? = null)
@Serializable private data class ReportRequest(val reason: String)
