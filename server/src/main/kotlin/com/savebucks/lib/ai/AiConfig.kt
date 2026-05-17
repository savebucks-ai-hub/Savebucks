package com.savebucks.lib.ai

/**
 * Static configuration for the AI assistant layer.
 * Model names, token budgets, rate limits, and cache TTLs live here
 * so they can be tuned without touching business logic.
 */
object AiConfig {
    // Models — primary is cheap/fast; fallback handles complex reasoning
    const val MODEL_DEFAULT = "gpt-4o-mini"
    const val MODEL_COMPLEX = "gpt-4o"

    // Token budgets per request
    const val MAX_INPUT_CHARS = 2_000
    const val MAX_HISTORY_MESSAGES = 10
    const val MAX_TOKENS_SIMPLE = 1_500
    const val MAX_TOKENS_COMPLEX = 4_000

    // Rate limits
    const val GUEST_REQUESTS_PER_MINUTE = 10
    const val GUEST_REQUESTS_PER_DAY = 20
    const val USER_REQUESTS_PER_MINUTE = 30
    const val USER_REQUESTS_PER_DAY = 200

    // Cache TTLs (seconds)
    const val CACHE_EXACT_TTL = 300L      // exact message match — 5 min
    const val CACHE_TOOL_TTL = 120L       // tool results (deals/coupons) — 2 min

    // Intent types
    val INTENTS = setOf("search", "coupon", "compare", "advice", "trending", "store_info", "help", "general")
}
