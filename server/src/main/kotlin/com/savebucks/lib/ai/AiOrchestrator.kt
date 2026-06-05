package com.savebucks.lib.ai

import com.savebucks.lib.redis.RedisCache
import com.savebucks.lib.supabase.SupabaseAdmin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(AiOrchestrator::class.java)

/**
 * Coordinates the full AI chat pipeline:
 *   1. Rate-limit check
 *   2. FAQ short-circuit (no LLM cost)
 *   3. Cache lookup
 *   4. Intent classification
 *   5. Tool execution (parallel Supabase queries)
 *   6. Final LLM response via [AiProviderRouter] (Groq → OpenAI fallback)
 *   7. Cache write
 */
class AiOrchestrator(
    private val router: AiProviderRouter,
    private val supabase: SupabaseAdmin,
    private val cache: RedisCache
) {

    private val classifier = AiClassifier(router)
    private val queryExpander = QueryExpander(router, cache)
    private val tools = AiTools(supabase, queryExpander)

    /**
     * Processes a single user message and returns a structured [AiResponse].
     *
     * @param message   The user's text input.
     * @param userId    Authenticated user ID, or an IP string for guests (for rate limiting).
     * @param history   Previous messages in the conversation (oldest first).
     * @param excludeIds Deal IDs already shown in this session — prevents duplicate suggestions.
     */
    suspend fun chat(
        message: String,
        userId: String,
        history: List<ChatMessage> = emptyList(),
        excludeIds: List<String> = emptyList()
    ): AiResponse {
        // Guard: message too long
        if (message.length > AiConfig.MAX_INPUT_CHARS) {
            return AiResponse(message = Json.decodeFromString<JsonObject>(AiPrompts.TOO_LONG)["message"]!!.jsonPrimitive.content)
        }

        // Rate limiting — use stricter guest limits when userId looks like an IP
        val isGuest = userId.contains('.')  // IPs contain dots; UUIDs contain hyphens
        val rateLimitKey = "rl:ai:$userId"
        val maxReqPerMin = if (isGuest) AiConfig.GUEST_REQUESTS_PER_MINUTE else AiConfig.USER_REQUESTS_PER_MINUTE
        if (!cache.rateLimit(rateLimitKey, maxReqPerMin, 60)) {
            return AiResponse(message = Json.decodeFromString<JsonObject>(AiPrompts.RATE_LIMITED)["message"]!!.jsonPrimitive.content)
        }

        // FAQ short-circuit — common greetings/questions don't need the LLM
        AiPrompts.faqMatch(message)?.let { faq ->
            val faqJson = Json.decodeFromString<JsonObject>(faq)
            return AiResponse(message = faqJson["message"]!!.jsonPrimitive.content, cached = true)
        }

        // Cache lookup with SHA-256 key on the message text
        val cacheKey = "ai:chat:${message.hashCode()}"
        cache.get(cacheKey)?.let { cached ->
            val obj = runCatching { Json.decodeFromString<JsonObject>(cached) }.getOrNull()
            if (obj != null) {
                return AiResponse(
                    message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
                    dealIds = obj["dealIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    cached = true
                )
            }
        }

        // Classify intent — may be upgraded to "local" below if this is a zipcode reply
        var intent = classifier.classify(message)

        // ── Local-intent zipcode flow ────────────────────────────────────────
        //
        // Step 1: if the previous assistant turn asked for a zipcode, and the user
        //   (a) provided a 5-digit zip → upgrade intent to "local" and use that zip
        //   (b) replied without a zip   → repeat the geolocation offer (no LLM cost)
        if (lastAssistantAskedForZipcode(history)) {
            val zipFromReply = extractZipcodeFromText(message)
            if (zipFromReply != null) {
                // User supplied their zipcode — upgrade intent and proceed with local search
                intent = ClassifiedIntent("local", 0.95, intent.entities + mapOf("zipcode" to zipFromReply))
            } else {
                // Still no zipcode — offer geolocation again, no LLM call
                return AiResponse(
                    message = AiPrompts.ASK_ZIPCODE_AGAIN_MSG,
                    intent = "local",
                    requiresZipcode = true
                )
            }
        }

        // Step 2: for local intent, resolve zipcode from what the user typed only.
        // No headers or body params — zipcode must come from the chat message itself
        // (inline: "near 28648") or from an earlier message in this conversation.
        var resolvedZipcode: String? = null
        if (intent.intent == "local") {
            resolvedZipcode = intent.entities["zipcode"]    // 1. inline in current message
                ?: extractZipcodeFromHistory(history)       // 2. user typed it earlier this chat

            if (resolvedZipcode == null) {
                // No zipcode anywhere — ask for it (no LLM cost)
                return AiResponse(
                    message = AiPrompts.ASK_ZIPCODE_MSG,
                    intent = "local",
                    requiresZipcode = true
                )
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        val systemPrompt = when (intent.intent) {
            "compare" -> AiPrompts.COMPARE_SYSTEM_PROMPT
            "advice"  -> AiPrompts.ADVICE_SYSTEM_PROMPT
            else      -> AiPrompts.SYSTEM_PROMPT
        }
        // Use the complex model name as a hint — the router maps it to the provider's equivalent
        val modelHint = if (intent.intent in listOf("compare", "advice") || intent.confidence < 0.6) {
            AiConfig.MODEL_COMPLEX
        } else {
            AiConfig.MODEL_DEFAULT
        }

        // Local searches are keyed on (message + zipcode) so "deals near me" in 94102 ≠ 10001
        val resolvedCacheKey = if (intent.intent == "local" && resolvedZipcode != null) {
            "ai:chat:local:${message.hashCode()}:$resolvedZipcode"
        } else {
            cacheKey
        }

        return try {
            // Execute tools for intents that benefit from live data
            val toolResults = if (intent.intent in listOf("search", "coupon", "trending", "compare", "local")) {
                executeTools(message, intent, excludeIds, resolvedZipcode)
            } else emptyList()

            // Build the final message list with history, tool context, and the user's query
            val messages = buildMessages(systemPrompt, message, history, toolResults)

            val request = ChatRequest(
                model = modelHint,
                messages = messages,
                maxTokens = AiConfig.MAX_TOKENS_SIMPLE
            )

            val providerResult = router.chat(request)
            val content = providerResult.response.choices.firstOrNull()?.message?.content ?: AiPrompts.API_ERROR
            val tokens = providerResult.response.usage?.totalTokens ?: 0

            // Parse the JSON response the LLM should always return
            val parsed = runCatching { Json.decodeFromString<JsonObject>(content) }.getOrNull()
            val msg = parsed?.get("message")?.jsonPrimitive?.contentOrNull ?: content
            val dealIds = parsed?.get("dealIds")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            cache.set(resolvedCacheKey, content, AiConfig.CACHE_EXACT_TTL)

            AiResponse(
                message = msg,
                dealIds = dealIds,
                intent = intent.intent,
                model = providerResult.model,
                provider = providerResult.provider,
                tokensUsed = tokens
            )

        } catch (e: Exception) {
            log.error("AI chat error: ${e.message}", e)
            AiResponse(message = "I ran into a problem processing your request. Please try again! 🤖")
        }
    }

    // ── Zipcode helpers ──────────────────────────────────────────────────────

    private val zipcodeRegex = Regex("""\b(\d{5})\b""")

    /** Extracts the first 5-digit US zipcode from [text], or null. */
    private fun extractZipcodeFromText(text: String): String? =
        zipcodeRegex.find(text)?.groupValues?.get(1)

    /**
     * Scans the last 6 user turns in [history] (newest first) for a 5-digit zipcode.
     * This lets subsequent messages reuse a zip the user typed earlier in the same conversation.
     */
    private fun extractZipcodeFromHistory(history: List<ChatMessage>): String? =
        history.filter { it.role == "user" }.takeLast(6).reversed()
            .mapNotNull { extractZipcodeFromText(it.content) }
            .firstOrNull()

    /**
     * Returns true when the most recent assistant message was asking the user for a zipcode.
     * Detected by the [AiPrompts.ASK_ZIPCODE_MARKER] substring — avoids storing extra state.
     */
    private fun lastAssistantAskedForZipcode(history: List<ChatMessage>): Boolean =
        history.lastOrNull { it.role == "assistant" }
            ?.content?.contains(AiPrompts.ASK_ZIPCODE_MARKER, ignoreCase = true) == true

    // ─────────────────────────────────────────────────────────────────────────

    // ── Local query helpers ──────────────────────────────────────────────────

    // Words that signal the user wants a coupon, not just a deal.
    // Intentionally narrow: "promo" alone isn't enough — "promo deal" shouldn't trigger coupon search.
    private val couponSignalPattern = Regex(
        "\\bcoupon|\\bvoucher|\\bpromo code|\\bdiscount code",
        RegexOption.IGNORE_CASE
    )

    // All the noise we strip before extracting the merchant name or deal search term.
    // Must stay in sync with AiClassifier.localPatterns and LocationDetector.localIntentPattern.
    private val localNoisePattern = Regex(
        "near\\s+me|nearby|near\\s*by|close\\s+by|closest|nearest|" +
        "around\\s+me|around\\s+here|close\\s+to\\s+me|near\\s+my\\s+location|" +
        "within\\s+walking|within\\s+\\d+\\s*miles?|" +
        "in\\s+my\\s+(area|city|town|zip|neighborhood)|in\\s+this\\s+(area|city|town)|" +
        "\\blocal(ly)?\\b|" +
        "in[- ]store|in\\s+store\\s+only|in\\s+store|store\\s+pick.?up|pick.?up|stores?\\s+near|" +
        "near\\s+\\d{5}|\\b\\d{5}\\b",
        RegexOption.IGNORE_CASE
    )
    private val couponNoisePattern = Regex(
        "\\b(coupon|coupons|promo|promo code|discount code|voucher|deal|deals|offer|offers|sale|bogo|code|near|at|the|a|an|me|my)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Strips location + coupon noise to extract a clean merchant name.
     * "great clips coupon near me"  → "great clips"
     * "BOGO deals near me"          → null  (only noise words left — no useful merchant)
     * "Target deals near me"        → "target"
     */
    private fun extractMerchantFromLocalQuery(message: String): String? =
        message
            .replace(localNoisePattern, "")
            .replace(couponNoisePattern, "")
            .replace(Regex("\\s+"), " ").trim()
            .takeIf { it.length >= 3 }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes the relevant tool calls in parallel to gather deal/coupon context.
     *
     * For "local" intent, BOTH deals and coupons are always searched in parallel:
     *   - find_local_deals  → in-store deals (is_instore=true), filtered by title/merchant
     *   - get_coupons       → coupons for the extracted merchant/category (runs whenever
     *                         a meaningful term can be pulled from the message, e.g.
     *                         "great clips near me" → coupons for Great Clips even without
     *                         the word "coupon" in the query)
     */
    private suspend fun executeTools(
        message: String,
        intent: ClassifiedIntent,
        excludeIds: List<String>,
        zipcode: String? = null
    ): List<String> = coroutineScope {
        when (intent.intent) {
            "local" -> {
                val dealsJob = async {
                    tools.executeTool("find_local_deals", buildLocalArgs(message, intent.entities, zipcode))
                }
                // Always attempt coupon search — QueryExpander handles the category→brand mapping.
                // Skipped only when no meaningful merchant/category term can be extracted
                // (e.g. "BOGO deals near me" strips to nothing → couponJob = null).
                val merchant = intent.entities["store"] ?: extractMerchantFromLocalQuery(message)
                val couponJob = merchant?.let {
                    async { tools.executeTool("get_coupons", buildArgs("store", it)) }
                }

                listOfNotNull(dealsJob.await(), couponJob?.await())
            }
            "coupon"   -> listOf(async { tools.executeTool("get_coupons",       buildArgs("store", intent.entities["store"] ?: message.take(50))) }.await())
            "trending" -> listOf(async { tools.executeTool("get_trending_deals", "{}") }.await())
            else       -> listOf(async { tools.executeTool("search_deals",       buildSearchArgs(message, intent.entities)) }.await())
        }
    }

    private fun buildArgs(key: String, value: String) = """{"$key": "$value"}"""

    private fun buildLocalArgs(message: String, entities: Map<String, String>, zipcode: String?): String =
        buildJsonObject {
            // Strip BOTH location keywords AND coupon noise so the deal search term is clean.
            // "great clips coupon near me" → query = "great clips" (not "great clips coupon")
            val cleanQuery = message
                .replace(localNoisePattern, "")
                .replace(couponSignalPattern, "")
                .replace(Regex("\\s+"), " ").trim()
            if (cleanQuery.isNotBlank()) put("query", cleanQuery.take(100))
            val resolvedZip = entities["zipcode"] ?: zipcode
            resolvedZip?.let { put("zipcode", it) }
            entities["store"]?.let { put("merchant", it) }
        }.toString()

    private fun buildSearchArgs(message: String, entities: Map<String, String>): String =
        buildJsonObject {
            put("query", message.take(100))
            entities["maxPrice"]?.let { put("max_price", it.toDoubleOrNull() ?: return@let) }
            entities["minDiscount"]?.let { put("min_discount", it.toIntOrNull() ?: return@let) }
            entities["store"]?.let { put("store", it) }
        }.toString()

    /** Assembles the full message list for the LLM including history and tool context. */
    private fun buildMessages(
        systemPrompt: String,
        userMessage: String,
        history: List<ChatMessage>,
        toolResults: List<String>
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt))

        // Trim history to avoid context overflow
        val trimmedHistory = history.takeLast(AiConfig.MAX_HISTORY_MESSAGES)
        messages.addAll(trimmedHistory)

        // Inject tool results as assistant context before the user's message
        if (toolResults.isNotEmpty()) {
            val context = toolResults.joinToString("\n")
            messages.add(ChatMessage("system", "Available deals/coupons from database:\n$context"))
        }

        messages.add(ChatMessage("user", userMessage))
        return messages
    }

    /** @return true if at least one provider is reachable. */
    suspend fun healthCheck(): Boolean = router.healthCheck().values.any { it }

    /** @return per-provider usage stats for today. */
    suspend fun providerStats(): ProviderStats = router.getStats()

    /** @return live reachability check for each provider. */
    suspend fun providerHealth(): Map<String, Boolean> = router.healthCheck()
}
