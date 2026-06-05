package com.savebucks.lib.ai

import com.savebucks.lib.supabase.SupabaseAdmin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(AiTools::class.java)

/**
 * Tool definitions and executors for the AI assistant's function-calling capability.
 *
 * Each tool maps to a Postgres query against the deals/coupons tables. The LLM
 * decides which tools to call based on the user's intent; we execute them in
 * parallel and format the results back as context for the final response.
 *
 * Tool names match the `name` field in [AiToolDef] — they must stay stable
 * because the LLM generates these names verbatim in its tool_calls.
 */
class AiTools(
    private val supabase: SupabaseAdmin,
    private val queryExpander: QueryExpander
) {

    /** All tool definitions sent to OpenAI in the chat request. */
    val toolDefinitions: List<AiToolDef> = listOf(
        AiToolDef(function = AiFunction(
            name = "search_deals",
            description = "Search for deals and products matching the user's criteria",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "Search query") }
                    putJsonObject("max_price") { put("type", "number"); put("description", "Maximum price filter") }
                    putJsonObject("min_discount") { put("type", "integer"); put("description", "Minimum discount percentage") }
                    putJsonObject("store") { put("type", "string"); put("description", "Specific store/merchant name") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max results to return (default 5)") }
                }
                putJsonArray("required") { add("query") }
            }
        )),
        AiToolDef(function = AiFunction(
            name = "get_coupons",
            description = "Retrieve active coupon codes for a store or category",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("store") { put("type", "string"); put("description", "Store/merchant name") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                putJsonArray("required") { add("store") }
            }
        )),
        AiToolDef(function = AiFunction(
            name = "get_trending_deals",
            description = "Fetch currently trending or popular deals",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("category") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                }
            }
        )),
        AiToolDef(function = AiFunction(
            name = "find_local_deals",
            description = "Find in-store or nearby deals. Use when the user mentions 'near me', 'nearby', 'in-store', 'store pickup', or provides a zip code.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "What the user is looking for (optional)") }
                    putJsonObject("zipcode") { put("type", "string"); put("description", "5-digit US zip code for the user's area") }
                    putJsonObject("merchant") { put("type", "string"); put("description", "Specific store name (e.g. Target, Best Buy)") }
                    putJsonObject("limit") { put("type", "integer") }
                }
            }
        ))
    )

    /**
     * Dispatches a single tool call from the LLM and returns the result as a JSON string.
     *
     * @param toolName The function name the LLM called (must match [toolDefinitions]).
     * @param argsJson JSON string of arguments produced by the LLM.
     * @return Formatted string result to inject back into the conversation as a tool message.
     */
    suspend fun executeTool(toolName: String, argsJson: String): String {
        val args = runCatching { Json.parseToJsonElement(argsJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }

        return when (toolName) {
            "search_deals" -> searchDeals(args)
            "get_coupons" -> getCoupons(args)
            "get_trending_deals" -> getTrendingDeals(args)
            "find_local_deals" -> findLocalDeals(args)
            else -> """{"error": "Unknown tool: $toolName"}"""
        }
    }

    // ─── Tool implementations ─────────────────────────────────────────────────

    private suspend fun searchDeals(args: JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return """{"deals": []}"""
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 5
        val maxPrice = args["max_price"]?.jsonPrimitive?.doubleOrNull
        val minDiscount = args["min_discount"]?.jsonPrimitive?.intOrNull
        val store = args["store"]?.jsonPrimitive?.contentOrNull

        var queryBuilder = supabase.from("deals")
            .select("id,title,url,price,original_price,merchant,discount_percent,image_url,score,hot_score")
            .eq("status", "approved")
            .ilike("title", "%$query%")
            .order("hot_score", ascending = false)
            .limit(limit)

        if (store != null) queryBuilder = queryBuilder.ilike("merchant", "%$store%")

        val results = queryBuilder.execute()

        // Filter by price/discount client-side (PostgREST numeric filters on optional fields are tricky)
        val filtered = results.filter { item ->
            val obj = item.jsonObject
            val price = obj["price"]?.jsonPrimitive?.doubleOrNull
            val discount = obj["discount_percent"]?.jsonPrimitive?.intOrNull ?: 0
            (maxPrice == null || (price != null && price <= maxPrice)) &&
            (minDiscount == null || discount >= minDiscount)
        }

        return buildJsonObject { putJsonArray("deals") { filtered.forEach { add(it) } } }.toString()
    }

    private suspend fun getCoupons(args: JsonObject): String {
        val store = args["store"]?.jsonPrimitive?.contentOrNull ?: return """{"coupons": []}"""
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 5

        // Expand category term to actual merchant names.
        // "haircut" → ["Great Clips", "Supercuts", "Sport Clips", ...]  (cached 24 h)
        // "Great Clips" → ["Great Clips"]  (known brand, no LLM call)
        val merchants = queryExpander.expand(store)

        val results = supabase.from("coupons")
            .select("id,title,code,type,discount_value,company_name,expires_at,success_rate")
            .eq("status", "approved")
            .orIlike("company_name", merchants.map { "%$it%" })
            .order("success_rate", ascending = false)
            .limit(limit)
            .execute()

        return buildJsonObject {
            putJsonArray("coupons") { results.forEach { add(it) } }
            // Surface matched merchant names so the LLM can reference them in its response
            if (merchants.size > 1) putJsonArray("matched_merchants") { merchants.forEach { add(it) } }
        }.toString()
    }

    private suspend fun getTrendingDeals(args: JsonObject): String {
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 5

        val results = supabase.from("deals")
            .select("id,title,url,price,merchant,discount_percent,image_url,score,hot_score")
            .eq("status", "approved")
            .order("hot_score", ascending = false)
            .limit(limit)
            .execute()

        return buildJsonObject { putJsonArray("deals") { results.forEach { add(it) } } }.toString()
    }

    private suspend fun findLocalDeals(args: JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.contentOrNull
        val zipcode = args["zipcode"]?.jsonPrimitive?.contentOrNull
        val merchant = args["merchant"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 5

        // Expand merchant category term → brand names so "haircut" → Great Clips, Supercuts etc.
        // When no merchant filter: title search on `query` handles category terms naturally
        // ("haircut deals near me" → title ILIKE '%haircut%' catches "Great Clips: $8 Haircut")
        val expandedMerchants = merchant?.let { queryExpander.expand(it) }

        var qb = supabase.from("deals")
            .select("id,title,url,price,merchant,discount_percent,image_url,score,hot_score,is_instore")
            .eq("status", "approved")
            .eq("is_instore", true)
            .order("hot_score", ascending = false)
            .limit(limit)

        if (!query.isNullOrBlank()) qb = qb.ilike("title", "%$query%")
        if (!expandedMerchants.isNullOrEmpty()) {
            qb = qb.orIlike("merchant", expandedMerchants.map { "%$it%" })
        }

        val results = qb.execute()

        return buildJsonObject {
            putJsonArray("deals") { results.forEach { add(it) } }
            zipcode?.let { put("searched_zipcode", it) }
            put("note", if (zipcode != null) "Showing in-store deals near $zipcode" else "Showing in-store deals")
            expandedMerchants?.let {
                if (it.size > 1) putJsonArray("matched_merchants") { it.forEach { m -> add(m) } }
            }
        }.toString()
    }
}
