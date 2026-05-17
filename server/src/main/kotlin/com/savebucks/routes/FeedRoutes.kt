package com.savebucks.routes

import com.savebucks.lib.redis.RedisCache
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.models.successResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.koin.ktor.ext.inject

/**
 * Unified feed endpoints mounted at /api/feed.
 *
 * Merges deals and coupons into a single chronological stream,
 * similar to a social-media feed. Results are cached aggressively
 * because the feed is the most-visited page.
 */
fun Route.feedRoutes() {
    val supabase: SupabaseAdmin by inject()
    val cache: RedisCache by inject()

    route("/feed") {

        /**
         * GET /api/feed — merged deals + coupons with engagement metadata.
         * Supports filters: under-10, under-25, under-50, trending, hot, ending-soon,
         *                   freebies, flash-sale, free-shipping, new-arrivals, 50-off.
         */
        get {
            val cursor = call.parameters["cursor"]?.toIntOrNull() ?: 0
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 12).coerceIn(1, 50)
            val filter = call.parameters["filter"] ?: "all"
            val category = call.parameters["category"]
            val sort = call.parameters["sort"] ?: "newest"

            val cacheKey = "feed:$filter:$category:$sort:$cursor:$limit"
            cache.get(cacheKey)?.let { cached ->
                call.respond(HttpStatusCode.OK, Json.decodeFromString<JsonObject>(cached))
                return@get
            }

            // Fetch deals and coupons concurrently to minimise latency
            val (rawDeals, rawCoupons) = coroutineScope {
                val deals = async {
                    var q = supabase.from("deals")
                        .select("id,title,url,price,original_price,merchant,discount_percent,image_url,category,score,hot_score,view_count,click_count,comment_count,is_featured,created_at,free_shipping,expires_at,savings")
                        .eq("status", "approved")

                    // Apply filter-specific constraints
                    when (filter) {
                        "under-10" -> q = q.lte("price", 10)
                        "under-25" -> q = q.lte("price", 25)
                        "under-50" -> q = q.lte("price", 50)
                        "freebies" -> q = q.eq("price", 0)
                        "free-shipping" -> q = q.eq("free_shipping", true)
                        "50-off" -> q = q.gte("discount_percent", 50)
                    }
                    category?.let { q = q.eq("category_slug", it) }

                    when (sort) {
                        "trending" -> q = q.order("hot_score", ascending = false)
                        "popular" -> q = q.order("score", ascending = false)
                        else -> q = q.order("created_at", ascending = false)
                    }

                    q.limit(limit * 2).offset(cursor).execute()
                }

                val coupons = async {
                    supabase.from("coupons")
                        .select("id,title,code,type,company_name,company_slug,discount_value,is_verified,score,created_at")
                        .eq("status", "approved")
                        .order("created_at", ascending = false)
                        .limit(limit)
                        .execute()
                }

                Pair(deals.await(), coupons.await())
            }

            // Tag each item with its type so the client can render them differently
            val tagged = (
                rawDeals.map { buildJsonObject { it.jsonObject.entries.forEach { (k, v) -> put(k, v) }; put("itemType", "deal") } } +
                rawCoupons.map { buildJsonObject { it.jsonObject.entries.forEach { (k, v) -> put(k, v) }; put("itemType", "coupon") } }
            ).sortedByDescending { it["created_at"]?.jsonPrimitive?.contentOrNull }
             .drop(cursor)
             .take(limit)

            val response = buildJsonObject {
                put("success", true)
                putJsonObject("data") {
                    putJsonArray("items") { tagged.forEach { add(it) } }
                    put("total", rawDeals.size + rawCoupons.size)
                    put("nextCursor", cursor + limit)
                    put("hasMore", tagged.size >= limit)
                }
            }

            cache.set(cacheKey, response.toString(), ttlSeconds = 60)
            call.respond(HttpStatusCode.OK, response)
        }

        /** GET /api/feed/stats — aggregate counts for the homepage hero section. */
        get("/stats") {
            val cacheKey = "feed:stats"
            cache.get(cacheKey)?.let { cached ->
                call.respond(HttpStatusCode.OK, Json.decodeFromString<JsonObject>(cached))
                return@get
            }

            val (dealCount, userCount) = coroutineScope {
                val deals = async { supabase.from("deals").select("id").eq("status", "approved").count() }
                val users = async { supabase.from("profiles").select("id").count() }
                Pair(deals.await(), users.await())
            }

            val response = buildJsonObject {
                put("success", true)
                putJsonObject("data") {
                    put("activeDeals", dealCount)
                    put("totalUsers", userCount)
                }
            }

            cache.set(cacheKey, response.toString(), ttlSeconds = 300)
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
