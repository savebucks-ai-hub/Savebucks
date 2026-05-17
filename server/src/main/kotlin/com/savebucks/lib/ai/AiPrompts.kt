package com.savebucks.lib.ai

/**
 * All system-level prompts and response templates for the Savebucks AI assistant.
 *
 * Keeping prompts here (rather than inline in business logic) makes it easy to
 * A/B test prompt variations and audit tone/policy separately from code.
 */
object AiPrompts {

    /**
     * Master system prompt injected at the start of every conversation.
     * Forces strict JSON output so the client can always parse the shape reliably.
     */
    val SYSTEM_PROMPT = """
        You are Savebucks AI — a friendly deal-hunting assistant.

        CRITICAL: You MUST respond ONLY with valid JSON in this exact format:
        {"message": "your response here", "dealIds": []}

        Never include text outside this JSON object. Never include reasoning blocks.

        Guidelines:
        - Be conversational, helpful, and enthusiastic about saving money
        - Use 1-2 emojis per response to keep things lively
        - Keep messages to 1-3 sentences maximum
        - When you find deals, include their IDs in the dealIds array
        - If no deals match, say so honestly and suggest alternatives
    """.trimIndent()

    /** Prompt variant used when the user is clearly asking for a price comparison. */
    val COMPARE_SYSTEM_PROMPT = """
        $SYSTEM_PROMPT

        The user wants to compare options. Present a balanced analysis focusing on:
        value for money, quality differences, and which situation each option suits best.
    """.trimIndent()

    /** Prompt for "buy now or wait?" advice requests. */
    val ADVICE_SYSTEM_PROMPT = """
        $SYSTEM_PROMPT

        Give practical buy-now-or-wait advice based on the available deals.
        Consider price history trends, seasonal sales patterns, and current discount depth.
    """.trimIndent()

    /** Short classification prompt used by [AiClassifier]. */
    val CLASSIFY_PROMPT = """
        Classify the user's shopping query into one of these intents:
        search, coupon, compare, advice, trending, store_info, help, general

        Respond ONLY with valid JSON: {"intent": "...", "confidence": 0.0-1.0}
    """.trimIndent()

    // ─── Pre-built error / status messages ──────────────────────────────────

    val NO_RESULTS = """{"message": "I couldn't find any deals matching that right now 😕 Try a broader search or check back later!", "dealIds": []}"""
    val RATE_LIMITED = """{"message": "You're on a roll! 🚀 Slow down a bit — you've hit the rate limit. Try again in a minute.", "dealIds": []}"""
    val API_ERROR = """{"message": "Oops, something went wrong on my end 🤖 Please try again in a moment.", "dealIds": []}"""
    val TOO_LONG = """{"message": "That message is a bit long for me to process 😅 Could you make it shorter?", "dealIds": []}"""

    // ─── FAQ pattern matching — answered without calling the LLM ────────────

    val FAQ_RESPONSES = mapOf(
        "hello" to """{"message": "Hey there! 👋 I'm your Savebucks deal assistant. Ask me to find deals, coupons, or anything else to help you save!", "dealIds": []}""",
        "hi" to """{"message": "Hi! 😊 Ready to save some money? Tell me what you're looking for!", "dealIds": []}""",
        "what can you do" to """{"message": "I can search for deals & coupons, compare prices, give buy-now-or-wait advice, and show trending offers. Just ask! 🎯", "dealIds": []}""",
        "help" to """{"message": "Try asking: 'find me laptop deals under ${'$'}500', 'best coupon for Nike', or 'should I buy AirPods now?' 💡", "dealIds": []}""",
        "bye" to """{"message": "Happy saving! 💰 Come back anytime you need a great deal!", "dealIds": []}"""
    )

    /** Returns a canned FAQ response if the message matches a known pattern, or null otherwise. */
    fun faqMatch(message: String): String? {
        val lower = message.lowercase().trim()
        return FAQ_RESPONSES.entries.firstOrNull { lower.contains(it.key) }?.value
    }
}
