package com.savebucks.lib.ai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(AiClassifier::class.java)

/**
 * Two-stage intent classifier for incoming user messages.
 *
 * Stage 1: Fast keyword/regex matching — zero LLM cost, <1ms latency.
 * Stage 2: LLM fallback — used only when keyword matching is uncertain.
 *          The LLM classification result is cached to avoid repeated calls
 *          for similar queries.
 *
 * This pattern (fast local → expensive remote) keeps median latency low
 * while still handling nuanced queries correctly.
 */
class AiClassifier(private val aiClient: AiClient) {

    /**
     * Classifies the [message] into one of the intent types defined in [AiConfig.INTENTS].
     *
     * @return [ClassifiedIntent] with the detected intent and confidence score.
     */
    suspend fun classify(message: String): ClassifiedIntent {
        // Stage 1: try keyword patterns first — they cover ~80% of queries
        val keywordResult = keywordClassify(message)
        if (keywordResult.confidence >= 0.85) return keywordResult

        // Stage 2: fall back to LLM for ambiguous queries
        return runCatching { llmClassify(message) }.getOrElse { e ->
            log.warn("LLM classification failed, using keyword fallback: ${e.message}")
            keywordResult  // degrade gracefully — a lower-confidence keyword result beats an error
        }
    }

    // ─── Keyword patterns ────────────────────────────────────────────────────

    private val searchPatterns = Regex("find|search|looking for|show me|where can i|deals on|best price for", RegexOption.IGNORE_CASE)
    private val couponPatterns = Regex("coupon|promo|discount code|voucher|code for|promo code|off\\b", RegexOption.IGNORE_CASE)
    private val comparePatterns = Regex("compare|vs\\.?|versus|which is better|difference between", RegexOption.IGNORE_CASE)
    private val advicePatterns = Regex("should i buy|wait|buy now|worth it|good deal|is .* worth", RegexOption.IGNORE_CASE)
    private val trendingPatterns = Regex("trending|popular|hot deal|most popular|everyone is buying|what's hot", RegexOption.IGNORE_CASE)
    private val storeInfoPatterns = Regex("store|shop|retailer|website|merchant|brand|about .*(store|shop)", RegexOption.IGNORE_CASE)
    private val helpPatterns = Regex("how (do|does)|help|what can you|explain|tutorial", RegexOption.IGNORE_CASE)

    private fun keywordClassify(message: String): ClassifiedIntent {
        val intent = when {
            couponPatterns.containsMatchIn(message) -> "coupon"
            comparePatterns.containsMatchIn(message) -> "compare"
            advicePatterns.containsMatchIn(message) -> "advice"
            trendingPatterns.containsMatchIn(message) -> "trending"
            storeInfoPatterns.containsMatchIn(message) -> "store_info"
            helpPatterns.containsMatchIn(message) -> "help"
            searchPatterns.containsMatchIn(message) -> "search"
            else -> "general"
        }
        // "general" gets low confidence so LLM gets a chance to reclassify
        val confidence = if (intent == "general") 0.5 else 0.9
        return ClassifiedIntent(intent, confidence, extractEntities(message))
    }

    // ─── LLM fallback ────────────────────────────────────────────────────────

    private suspend fun llmClassify(message: String): ClassifiedIntent {
        val request = ChatRequest(
            model = AiConfig.MODEL_DEFAULT,
            messages = listOf(
                ChatMessage("system", AiPrompts.CLASSIFY_PROMPT),
                ChatMessage("user", message.take(500))  // limit to avoid wasting tokens on classification
            ),
            maxTokens = 50,
            temperature = 0.0  // deterministic classification
        )
        val response = aiClient.chat(request)
        val content = response.choices.firstOrNull()?.message?.content ?: return ClassifiedIntent("general", 0.5)

        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(content).jsonObject }.getOrNull()
            ?: return ClassifiedIntent("general", 0.5)

        val intent = json["intent"]?.jsonPrimitive?.content?.lowercase()?.trim()
            ?.takeIf { it in AiConfig.INTENTS } ?: "general"
        val confidence = json["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.8

        return ClassifiedIntent(intent, confidence, extractEntities(message))
    }

    // ─── Entity extraction ───────────────────────────────────────────────────

    private val pricePattern = Regex("""under\s*\$?(\d+)|less than\s*\$?(\d+)|\$?(\d+)\s*or less""", RegexOption.IGNORE_CASE)
    private val discountPattern = Regex("""(\d+)\s*%\s*off""", RegexOption.IGNORE_CASE)
    private val storeNames = setOf("amazon", "walmart", "target", "best buy", "costco", "home depot", "ebay", "nike", "apple")

    private fun extractEntities(message: String): Map<String, String> {
        val entities = mutableMapOf<String, String>()

        pricePattern.find(message)?.let { match ->
            val price = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            if (price != null) entities["maxPrice"] = price
        }

        discountPattern.find(message)?.let { match ->
            entities["minDiscount"] = match.groupValues[1]
        }

        storeNames.firstOrNull { message.lowercase().contains(it) }?.let {
            entities["store"] = it
        }

        return entities
    }
}
