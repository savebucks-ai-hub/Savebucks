package com.savebucks.routes

import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.lib.redis.RedisCache
import com.savebucks.models.successResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("SearchRoutes")

/**
 * Search endpoints mounted at /api/search.
 * Results are cached in Redis (or in-memory) to reduce Supabase load on repeated queries.
 */
fun Route.searchRoutes() {
    val supabase: SupabaseAdmin by inject()
    val cache: RedisCache by inject()

    route("/search") {

        /**
         * GET /api/search?q=query — full-text search across deals and coupons.
         * Cache TTL: 2 minutes for search results.
         */
        get {
            val query = call.parameters["q"]?.trim() ?: ""
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
            val type = call.parameters["type"] ?: "all"  // all | deals | coupons

            if (query.length < 2) {
                call.respond(HttpStatusCode.OK, successResponse(mapOf("deals" to emptyList<Any>(), "coupons" to emptyList<Any>())))
                return@get
            }

            val cacheKey = "search:${query.hashCode()}:$type:$limit"
            val cached = cache.get(cacheKey)
            if (cached != null) {
                call.respond(HttpStatusCode.OK, Json.decodeFromString<JsonObject>(cached))
                return@get
            }

            val deals = if (type != "coupons") {
                supabase.from("deals")
                    .select("id,title,url,price,merchant,discount_percent,image_url,score")
                    .eq("status", "approved")
                    .ilike("title", "%$query%")
                    .order("hot_score", ascending = false)
                    .limit(limit)
                    .execute()
            } else JsonArray(emptyList())

            val coupons = if (type != "deals") {
                supabase.from("coupons")
                    .select("id,title,code,type,company_name,discount_value,success_rate")
                    .eq("status", "approved")
                    .ilike("title", "%$query%")
                    .order("success_rate", ascending = false)
                    .limit(limit)
                    .execute()
            } else JsonArray(emptyList())

            val result = buildJsonObject {
                put("success", true)
                put("data", buildJsonObject {
                    put("deals", deals)
                    put("coupons", coupons)
                    put("query", query)
                })
            }

            cache.set(cacheKey, result.toString(), ttlSeconds = 120)
            call.respond(HttpStatusCode.OK, result)
        }

        /**
         * GET /api/search/suggestions?q=query — lightweight suggestions (min 2 chars).
         * Returns distinct merchants and deal titles for autocomplete.
         */
        get("/suggestions") {
            val query = call.parameters["q"]?.trim() ?: ""
            if (query.length < 2) {
                call.respond(HttpStatusCode.OK, successResponse(emptyList<String>()))
                return@get
            }

            val cacheKey = "suggestions:${query.hashCode()}"
            val cached = cache.get(cacheKey)
            if (cached != null) {
                call.respond(HttpStatusCode.OK, Json.decodeFromString<JsonObject>(cached))
                return@get
            }

            val merchantSuggestions = supabase.from("deals")
                .select("merchant")
                .eq("status", "approved")
                .ilike("merchant", "%$query%")
                .limit(5)
                .execute()
                .mapNotNull { it.jsonObject["merchant"]?.jsonPrimitive?.contentOrNull }
                .distinct()

            val titleSuggestions = supabase.from("deals")
                .select("title")
                .eq("status", "approved")
                .ilike("title", "$query%")
                .order("hot_score", ascending = false)
                .limit(5)
                .execute()
                .mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.contentOrNull }

            val suggestions = (merchantSuggestions + titleSuggestions).distinct().take(8)
            val response = buildJsonObject {
                put("success", true)
                put("data", buildJsonArray { suggestions.forEach { add(it) } })
            }

            cache.set(cacheKey, response.toString(), ttlSeconds = 60)
            call.respond(HttpStatusCode.OK, response)
        }

        /** GET /api/search/trending — trending search terms. */
        get("/trending") {
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 20)

            val cacheKey = "trending-searches:$limit"
            val cached = cache.get(cacheKey)
            if (cached != null) {
                call.respond(HttpStatusCode.OK, Json.decodeFromString<JsonObject>(cached))
                return@get
            }

            // Trending = most-viewed deals in the last 7 days → extract merchant names as trending terms
            val trending = supabase.from("deals")
                .select("merchant")
                .eq("status", "approved")
                .order("hot_score", ascending = false)
                .limit(limit)
                .execute()
                .mapNotNull { it.jsonObject["merchant"]?.jsonPrimitive?.contentOrNull }
                .distinct()

            val response = buildJsonObject {
                put("success", true)
                put("data", buildJsonArray { trending.forEach { add(it) } })
            }

            cache.set(cacheKey, response.toString(), ttlSeconds = 300)
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
