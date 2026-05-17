package com.savebucks.routes

import com.savebucks.lib.NotFoundException
import com.savebucks.lib.redis.RedisCache
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.models.successResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.koin.ktor.ext.inject

/**
 * Category, collection, banner, and tag routes.
 * These are read-heavy and heavily cached since they rarely change.
 */
fun Route.categoriesRoutes() {
    val supabase: SupabaseAdmin by inject()
    val cache: RedisCache by inject()

    route("/categories") {

        /** GET /api/categories — all categories ordered by ID. */
        get {
            val cached = cache.get("categories:all")
            if (cached != null) {
                call.respond(HttpStatusCode.OK, Json.decodeFromString<JsonObject>(cached))
                return@get
            }

            val categories = supabase.from("categories")
                .select("*")
                .order("id", ascending = true)
                .execute()

            val response = buildJsonObject {
                put("success", true)
                putJsonArray("data") { categories.forEach { add(it) } }
            }
            cache.set("categories:all", response.toString(), ttlSeconds = 3600)
            call.respond(HttpStatusCode.OK, response)
        }

        /** GET /api/categories/:slug — single category with sub-categories. */
        get("/{slug}") {
            val slug = call.parameters["slug"] ?: return@get
            val category = supabase.from("categories")
                .select("*")
                .eq("slug", slug)
                .single() ?: throw NotFoundException("Category not found")

            val children = supabase.from("categories")
                .select("*")
                .eq("parent_id", category["id"]!!.jsonPrimitive.content)
                .execute()

            call.respond(HttpStatusCode.OK, successResponse(mapOf("category" to category, "children" to children)))
        }
    }

    /** GET /api/banners — active promotional banners. */
    get("/banners") {
        val cached = cache.get("banners:active")
        if (cached != null) {
            call.respond(HttpStatusCode.OK, Json.decodeFromString<JsonObject>(cached))
            return@get
        }

        val banners = supabase.from("banners")
            .select("*")
            .eq("is_active", true)
            .order("sort_order", ascending = true)
            .execute()

        val response = buildJsonObject {
            put("success", true)
            putJsonArray("data") { banners.forEach { add(it) } }
        }
        cache.set("banners:active", response.toString(), ttlSeconds = 600)
        call.respond(HttpStatusCode.OK, response)
    }

    /** GET /api/deal-tags — all deal tags for the tag cloud. */
    get("/deal-tags") {
        val tags = supabase.from("tags")
            .select("*")
            .order("name", ascending = true)
            .execute()
        call.respond(HttpStatusCode.OK, successResponse(tags))
    }

    route("/collections") {

        /** GET /api/collections — curated deal collections. */
        get {
            val featuredOnly = call.parameters["featured_only"] == "true"
            var query = supabase.from("collections")
                .select("*,categories(name,slug)")
                .eq("is_active", true)
                .order("sort_order", ascending = true)

            if (featuredOnly) query = query.eq("is_featured", true)

            call.respond(HttpStatusCode.OK, successResponse(query.execute()))
        }

        /** GET /api/collections/:slug — collection with paginated deals. */
        get("/{slug}") {
            val slug = call.parameters["slug"] ?: return@get
            val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
            val offset = call.parameters["offset"]?.toIntOrNull() ?: 0

            val collection = supabase.from("collections")
                .select("*")
                .eq("slug", slug)
                .single() ?: throw NotFoundException("Collection not found")

            val collectionType = collection["type"]?.jsonPrimitive?.contentOrNull ?: "manual"
            val deals = when (collectionType) {
                "auto_category" -> {
                    val catId = collection["category_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    supabase.from("deals").select("id,title,url,price,merchant,image_url,score,hot_score")
                        .eq("status", "approved").eq("category_id", catId)
                        .order("hot_score", ascending = false).limit(limit).offset(offset).execute()
                }
                "auto_discount" -> {
                    val minDiscount = collection["min_discount_percent"]?.jsonPrimitive?.intOrNull ?: 30
                    supabase.from("deals").select("id,title,url,price,merchant,image_url,score,hot_score,discount_percent")
                        .eq("status", "approved").gte("discount_percent", minDiscount)
                        .order("hot_score", ascending = false).limit(limit).offset(offset).execute()
                }
                else -> {
                    // Manual collection — items stored in a junction table
                    supabase.from("collection_items")
                        .select("deals(id,title,url,price,merchant,image_url,score,hot_score)")
                        .eq("collection_id", collection["id"]!!.jsonPrimitive.content)
                        .limit(limit).offset(offset).execute()
                }
            }

            call.respond(HttpStatusCode.OK, successResponse(mapOf("collection" to collection, "deals" to deals)))
        }
    }
}
