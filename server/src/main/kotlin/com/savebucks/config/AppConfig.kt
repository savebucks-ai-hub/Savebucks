package com.savebucks.config

import io.ktor.server.config.*

/**
 * Centralised application configuration loaded from application.conf (HOCON).
 *
 * All environment variables are resolved here at startup so mis-configuration
 * causes an immediate crash with a clear message rather than a silent failure
 * on first request.
 */
data class AppConfig(
    val supabase: SupabaseConfig,
    val redis: RedisConfig,
    val openai: OpenAiConfig,
    val groq: GroqConfig,
    val vapid: VapidConfig,
    val cors: CorsConfig,
    val web: WebConfig
) {
    companion object {
        /** Reads and validates all config from the Ktor ApplicationConfig. */
        fun from(config: ApplicationConfig): AppConfig = AppConfig(
            supabase = SupabaseConfig(
                url = conf(config, "savebucks.supabase.url", "SUPABASE_URL")
                    ?: error("SUPABASE_URL is required — set it in server/.env or as an environment variable"),
                anonKey = conf(config, "savebucks.supabase.anonKey", "SUPABASE_ANON_KEY")
                    ?: error("SUPABASE_ANON_KEY is required"),
                serviceRoleKey = conf(config, "savebucks.supabase.serviceRoleKey", "SUPABASE_SERVICE_ROLE")
                    ?: error("SUPABASE_SERVICE_ROLE is required"),
                jwtAudience = conf(config, "savebucks.supabase.jwtAudience", "SUPABASE_JWT_AUDIENCE")
                    ?: "authenticated",
                jwtSecret = conf(config, "savebucks.supabase.jwtSecret", "SUPABASE_JWT_SECRET") ?: ""
            ),
            redis = RedisConfig(
                url = conf(config, "savebucks.redis.url", "REDIS_URL") ?: ""
            ),
            openai = OpenAiConfig(
                apiKey = conf(config, "savebucks.openai.apiKey", "OPENAI_API_KEY") ?: "",
                model = conf(config, "savebucks.openai.model", "OPENAI_MODEL") ?: "gpt-4o-mini"
            ),
            groq = GroqConfig(
                apiKey = conf(config, "savebucks.groq.apiKey", "GROQ_API_KEY") ?: "",
                dailyLimit = conf(config, "savebucks.groq.dailyLimit", "GROQ_DAILY_LIMIT")
                    ?.toLongOrNull() ?: com.savebucks.lib.ai.AiConfig.GROQ_DAILY_LIMIT_DEFAULT
            ),
            vapid = VapidConfig(
                publicKey = conf(config, "savebucks.vapid.publicKey", "VAPID_PUBLIC_KEY") ?: "",
                privateKey = conf(config, "savebucks.vapid.privateKey", "VAPID_PRIVATE_KEY") ?: "",
                subject = conf(config, "savebucks.vapid.subject", "VAPID_SUBJECT")
                    ?: "mailto:admin@savebucks.com"
            ),
            cors = CorsConfig(
                allowedOrigins = (conf(config, "savebucks.cors.allowedOrigins", "CORS_ALLOWED_ORIGINS")
                    ?: "http://localhost:5173,http://localhost:3000")
                    .split(",").map { it.trim() }.filter { it.isNotBlank() }
            ),
            web = WebConfig(
                distPath = conf(config, "savebucks.web.distPath", "WEB_DIST_PATH") ?: "../web/dist"
            )
        )

        /**
         * Reads a config value from three sources in priority order:
         * 1. Ktor ApplicationConfig (from application.conf HOCON — works when env vars are properly set)
         * 2. System.getenv() — real OS/Docker environment variables
         * 3. System.getProperty() — JVM system properties set by loadDotEnv() in Application.kt
         *
         * This triple-fallback means the server starts correctly whether running via Gradle,
         * IntelliJ, a Docker container, or a plain JAR — with no manual export commands needed.
         */
        private fun conf(config: ApplicationConfig, hoconKey: String, envKey: String): String? =
            config.propertyOrNull(hoconKey)?.getString()?.takeIf { it.isNotBlank() }
                ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
                ?: System.getProperty(envKey)?.takeIf { it.isNotBlank() }
    }
}

data class SupabaseConfig(
    val url: String,
    val anonKey: String,
    val serviceRoleKey: String,
    val jwtAudience: String,
    val jwtSecret: String
)

data class RedisConfig(val url: String) {
    /** Redis is optional — when not configured the in-memory LRU cache is used instead. */
    val isEnabled: Boolean get() = url.isNotBlank()
}

data class OpenAiConfig(val apiKey: String, val model: String) {
    /** AI features are disabled gracefully when no API key is present. */
    val isEnabled: Boolean get() = apiKey.isNotBlank()
}

data class GroqConfig(val apiKey: String, val dailyLimit: Long) {
    val isEnabled: Boolean get() = apiKey.isNotBlank()
}

data class VapidConfig(val publicKey: String, val privateKey: String, val subject: String) {
    /** Web push is disabled when VAPID keys are not configured. */
    val isEnabled: Boolean get() = publicKey.isNotBlank() && privateKey.isNotBlank()
}

data class CorsConfig(val allowedOrigins: List<String>)
data class WebConfig(val distPath: String)
