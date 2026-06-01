package com.savebucks.worker.lib

object IngestionConfig {

    // =====================================================
    // RATE LIMITING
    // =====================================================
    object RateLimit {
        const val GLOBAL_RPS = 20
        const val MAX_CONCURRENT_JOBS = 10
        const val DEFAULT_REQUESTS_PER_WINDOW = 120
        const val DEFAULT_WINDOW_MS = 60_000L
    }

    // =====================================================
    // CIRCUIT BREAKER
    // =====================================================
    object CircuitBreaker {
        const val FAILURE_THRESHOLD = 10
        const val RESET_TIMEOUT_MS = 30_000L
        const val SUCCESS_THRESHOLD = 2
        const val MONITORING_WINDOW_MS = 60_000L
    }

    // =====================================================
    // RETRY
    // =====================================================
    object Retry {
        const val MAX_ATTEMPTS = 3
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 30_000L
        const val MULTIPLIER = 2.0
        const val JITTER_FACTOR = 0.25

        // Network error patterns that should trigger a retry
        val RETRYABLE_ERRORS = setOf(
            "ETIMEDOUT", "ECONNRESET", "ECONNREFUSED", "ENOTFOUND",
            "EAI_AGAIN", "EPIPE", "EHOSTUNREACH", "ENETUNREACH",
            "EADDRINUSE", "ERR_SOCKET_TIMEOUT",
            "Connection reset", "Connection refused", "Timed out",
            "timeout", "connect timed out", "Read timed out"
        )

        // HTTP status codes that are safe to retry
        val RETRYABLE_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504, 522, 524)
    }

    // =====================================================
    // VALIDATION
    // =====================================================
    object Validation {
        object Deal {
            const val MIN_TITLE_LENGTH = 10
            const val MAX_TITLE_LENGTH = 500
            const val MIN_DESCRIPTION_LENGTH = 0
            const val MAX_DESCRIPTION_LENGTH = 5000
            const val MIN_DISCOUNT = 1.0
            const val MAX_DISCOUNT = 99.0
            const val MIN_PRICE = 0.0
            const val MAX_PRICE = 1_000_000.0
        }
        object Coupon {
            const val MIN_TITLE_LENGTH = 5
            const val MAX_TITLE_LENGTH = 300
            const val MAX_DESCRIPTION_LENGTH = 5000
            const val MIN_CODE_LENGTH = 2
            const val MAX_CODE_LENGTH = 50
        }
    }

    // =====================================================
    // DEDUPLICATION
    // =====================================================
    object Dedup {
        const val TITLE_SIMILARITY_THRESHOLD = 0.85
        const val PRICE_VARIANCE_TOLERANCE = 0.05 // 5%
        const val LOOKBACK_DAYS = 7L
        const val MAX_CANDIDATES = 100
    }

    // =====================================================
    // EXPIRY
    // =====================================================
    object Expiry {
        const val SCHEDULE = "0 * * * *" // Every hour at minute 0
        const val DEALS_RETENTION_DAYS = 60L
        const val COUPONS_RETENTION_DAYS = 90L
        const val BATCH_SIZE = 500
    }

    // =====================================================
    // AUTO-APPROVAL
    // All deals go to pending state for admin review
    // =====================================================
    object AutoApproval {
        val TRUSTED_SOURCES: List<String> = emptyList()
        const val MIN_QUALITY_SCORE = 0.7
        const val REQUIRE_VERIFIED_COMPANY = false
    }

    // =====================================================
    // DAILY CAPS — safety limits per source
    // =====================================================
    object DailyCaps {
        const val DEFAULT = 500
        val OVERRIDES = mapOf(
            "slickdeals_rss"      to 200,
            "slickdeals_coupons"  to 100,
            "dealnews_rss"        to 200,
            "dealnews_coupons"    to 100,
            "techbargains_rss"    to 150
        )
    }

    // =====================================================
    // HTTP CLIENT
    // =====================================================
    object Http {
        const val TIMEOUT_MS = 30_000L
        const val USER_AGENT = "SavebucksBot/1.0 (+https://savebucks.com)"
        const val ACCEPT = "application/json, text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.8"
        const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"
        val PROXY_HOST: String? = System.getenv("PROXY_HOST")
        val PROXY_PORT: Int = System.getenv("PROXY_PORT")?.toIntOrNull() ?: 8080
    }

    // =====================================================
    // MONITORING
    // =====================================================
    object Monitoring {
        const val HEALTH_CHECK_INTERVAL_MS = 30_000L
        const val METRICS_INTERVAL_MS = 60_000L
        const val ERROR_RATE_THRESHOLD = 0.1       // 10%
        const val QUEUE_DEPTH_THRESHOLD = 1000
        const val PROCESSING_TIME_THRESHOLD_MS = 30_000L
    }

    // =====================================================
    // TELEGRAM
    // =====================================================
    object Telegram {
        const val MIN_TITLE_LENGTH = 12
        const val DEDUP_SIMILARITY_THRESHOLD = 0.6
        const val DEDUP_LOOKBACK_DAYS = 7L
    }
}
