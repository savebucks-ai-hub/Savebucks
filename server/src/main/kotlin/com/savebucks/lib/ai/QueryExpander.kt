package com.savebucks.lib.ai

import com.savebucks.lib.redis.RedisCache
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(QueryExpander::class.java)

/**
 * Expands a generic search term (e.g. "haircut") into the actual merchant/brand
 * names that offer it (e.g. ["Great Clips", "Supercuts", "Sport Clips", ...]).
 *
 * This bridges the semantic gap between what users type and how merchants are stored.
 * Without expansion, `company_name ILIKE '%haircut%'` returns 0 rows even though
 * Great Clips and Supercuts are both in the database.
 *
 * Strategy:
 *   1. Known brands short-circuit immediately — no LLM call.
 *   2. Redis cache (24 h TTL) — same term is only sent to Groq once per day.
 *   3. Groq llama-3.1-8b-instant (fast free model) — factual brand lookup.
 *   4. Graceful fallback — if Groq fails, the original term is returned unchanged
 *      so the search still runs (just with lower recall).
 */
class QueryExpander(
    private val router: AiProviderRouter,
    private val cache: RedisCache
) {
    companion object {
        private const val CACHE_TTL = 86_400L   // 24 hours
        private const val MAX_MERCHANTS = 8

        // Brands the expander recognises immediately — no LLM round-trip needed.
        // Keep this list small; the LLM handles everything else.
        private val KNOWN_BRANDS = setOf(
            "amazon", "walmart", "target", "best buy", "costco", "home depot",
            "ebay", "nike", "apple", "starbucks", "mcdonald's", "mcdonalds",
            "burger king", "subway", "chipotle", "taco bell", "kfc", "wendy's",
            "wendys", "domino's", "dominoes", "pizza hut", "papa john's",
            "great clips", "supercuts", "sport clips", "ulta", "sephora",
            "cvs", "walgreens", "rite aid", "dollar tree", "dollar general",
            "tjmaxx", "tj maxx", "marshalls", "ross", "old navy", "gap", "h&m"
        )

        private val EXPAND_PROMPT = """
            Given a shopping, service, or product category search term, list up to 8
            major US merchants, chains, or service businesses that offer it.
            If the input is already a specific brand or chain name, return just that name.

            Respond ONLY with a valid JSON array of strings. No other text, no markdown.

            Examples:
            haircut -> ["Great Clips","Supercuts","Sport Clips","Fantastic Sams","Hair Cuttery","Cost Cutters","Regis Salons"]
            pizza -> ["Domino's","Pizza Hut","Papa John's","Little Caesars","Papa Murphy's","Marco's Pizza"]
            oil change -> ["Jiffy Lube","Valvoline Instant Oil Change","Firestone Complete Auto Care","Midas","Pep Boys","NTB"]
            coffee -> ["Starbucks","Dunkin'","Peet's Coffee","Dutch Bros","The Coffee Bean & Tea Leaf","Caribou Coffee"]
            gym -> ["Planet Fitness","LA Fitness","Gold's Gym","24 Hour Fitness","Anytime Fitness","Crunch Fitness","YMCA"]
            pharmacy -> ["CVS","Walgreens","Rite Aid","Walmart Pharmacy","Costco Pharmacy","Sam's Club Pharmacy"]
            burger -> ["McDonald's","Burger King","Wendy's","Five Guys","Shake Shack","Smashburger","Whataburger"]
            car wash -> ["Mister Car Wash","Zips Car Wash","Splash Car Wash","Quick Quack Car Wash","Magnolia Car Wash"]
            nails -> ["Nail Salon","Regal Nails","Pro Nails","VIP Nails","Happy Nails","Luxury Nails"]
            sandwich -> ["Subway","Jimmy John's","Jersey Mike's","Firehouse Subs","Potbelly","Which Wich"]
            amazon -> ["Amazon"]
            Great Clips -> ["Great Clips"]
        """.trimIndent()
    }

    /**
     * Expands [term] to a list of merchant/brand names.
     *
     * Call this before every `company_name ILIKE` query.
     * The result is cached so "haircut" → brands only hits Groq once per day across
     * all users on all server instances.
     *
     * @return A non-empty list — always contains at least the original [term] as fallback.
     */
    suspend fun expand(term: String): List<String> {
        val normalized = term.lowercase().trim()
        if (normalized.isBlank()) return emptyList()

        // Fast path: already a known brand
        if (KNOWN_BRANDS.contains(normalized)) return listOf(term)

        val cacheKey = "qe:${normalized.hashCode()}"
        cache.get(cacheKey)?.let { cached ->
            return runCatching {
                Json.parseToJsonElement(cached).jsonArray
                    .map { it.jsonPrimitive.content }
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(term) }
            }.getOrDefault(listOf(term))
        }

        return try {
            val merchants = callLlm(term)
            val json = buildJsonArray { merchants.forEach { add(it) } }.toString()
            cache.set(cacheKey, json, CACHE_TTL)
            log.debug("QueryExpander: '{}' → {}", term, merchants)
            merchants
        } catch (e: Exception) {
            log.warn("QueryExpander: LLM call failed for '{}': {}", term, e.message)
            listOf(term)  // fallback — original term still searched, just lower recall
        }
    }

    private suspend fun callLlm(term: String): List<String> {
        val request = ChatRequest(
            model = AiConfig.MODEL_DEFAULT,   // → llama-3.1-8b-instant on Groq (fast + free)
            messages = listOf(
                ChatMessage("system", EXPAND_PROMPT),
                ChatMessage("user", term.take(60))
            ),
            maxTokens = 150,
            temperature = 0.1   // low temperature — factual, consistent answers
        )
        val content = router.chat(request).response
            .choices.firstOrNull()?.message?.content?.trim()
            ?: return listOf(term)

        return runCatching {
            // Strip markdown code fences if the model wraps output in ```json … ```
            val raw = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            Json.parseToJsonElement(raw).jsonArray
                .map { it.jsonPrimitive.content }
                .filter { it.isNotBlank() }
                .take(MAX_MERCHANTS)
                .ifEmpty { listOf(term) }
        }.getOrDefault(listOf(term))
    }
}
