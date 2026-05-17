package com.savebucks.lib.ai

import com.savebucks.config.OpenAiConfig
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
 *   6. Final LLM response generation
 *   7. Cache write
 *
 * The orchestrator is a singleton (wired by Koin). It owns an [AiClient] and
 * [AiClassifier]; both are created here so we don't leak them into Koin's graph.
 */
class AiOrchestrator(
    private val config: OpenAiConfig,
    private val supabase: SupabaseAdmin,
    private val cache: RedisCache
) {

    private val aiClient = AiClient(config)
    private val classifier = AiClassifier(aiClient)
    private val tools = AiTools(supabase)

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

        // Classify intent to pick the right model and system prompt
        val intent = classifier.classify(message)
        val systemPrompt = when (intent.intent) {
            "compare" -> AiPrompts.COMPARE_SYSTEM_PROMPT
            "advice" -> AiPrompts.ADVICE_SYSTEM_PROMPT
            else -> AiPrompts.SYSTEM_PROMPT
        }
        val model = if (intent.intent in listOf("compare", "advice") || intent.confidence < 0.6) {
            AiConfig.MODEL_COMPLEX
        } else {
            config.model
        }

        return try {
            // Execute tools for intents that benefit from live data
            val toolResults = if (intent.intent in listOf("search", "coupon", "trending", "compare")) {
                executeTools(message, intent, excludeIds)
            } else emptyList()

            // Build the final message list with history, tool context, and the user's query
            val messages = buildMessages(systemPrompt, message, history, toolResults)

            val request = ChatRequest(
                model = model,
                messages = messages,
                maxTokens = AiConfig.MAX_TOKENS_SIMPLE
            )

            val response = aiClient.chat(request)
            val content = response.choices.firstOrNull()?.message?.content ?: AiPrompts.API_ERROR
            val tokens = response.usage?.totalTokens ?: 0

            // Parse the JSON response the LLM should always return
            val parsed = runCatching { Json.decodeFromString<JsonObject>(content) }.getOrNull()
            val msg = parsed?.get("message")?.jsonPrimitive?.contentOrNull ?: content
            val dealIds = parsed?.get("dealIds")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            // Cache the response for 5 minutes to avoid duplicate LLM calls for identical queries
            cache.set(cacheKey, content, AiConfig.CACHE_EXACT_TTL)

            AiResponse(message = msg, dealIds = dealIds, intent = intent.intent, model = model, tokensUsed = tokens)

        } catch (e: Exception) {
            log.error("AI chat error: ${e.message}", e)
            AiResponse(message = "I ran into a problem processing your request. Please try again! 🤖")
        }
    }

    /**
     * Executes the relevant tool calls in parallel to gather deal/coupon context.
     * Returns a list of tool result strings to inject into the conversation.
     */
    private suspend fun executeTools(
        message: String,
        intent: ClassifiedIntent,
        excludeIds: List<String>
    ): List<String> = coroutineScope {
        val toolCall = when (intent.intent) {
            "coupon" -> async { tools.executeTool("get_coupons", buildArgs("store", intent.entities["store"] ?: message.take(50))) }
            "trending" -> async { tools.executeTool("get_trending_deals", "{}") }
            else -> async { tools.executeTool("search_deals", buildSearchArgs(message, intent.entities)) }
        }
        listOf(toolCall.await())
    }

    private fun buildArgs(key: String, value: String) = """{"$key": "$value"}"""

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

    /** @return true if the OpenAI API is reachable. */
    suspend fun healthCheck(): Boolean = config.isEnabled && aiClient.healthCheck()
}
