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
    val vapid: VapidConfig,
    val cors: CorsConfig,
    val web: WebConfig
) {
    companion object {
        /** Reads and validates all config from the Ktor ApplicationConfig. */
        fun from(config: ApplicationConfig): AppConfig = AppConfig(
            supabase = SupabaseConfig(
                url = config.propertyOrNull("savebucks.supabase.url")?.getString()
                    ?: error("SUPABASE_URL is required"),
                anonKey = config.propertyOrNull("savebucks.supabase.anonKey")?.getString()
                    ?: error("SUPABASE_ANON_KEY is required"),
                serviceRoleKey = config.propertyOrNull("savebucks.supabase.serviceRoleKey")?.getString()
                    ?: error("SUPABASE_SERVICE_ROLE is required"),
                jwtAudience = config.propertyOrNull("savebucks.supabase.jwtAudience")?.getString()
                    ?: "authenticated",
                jwtSecret = config.propertyOrNull("savebucks.supabase.jwtSecret")?.getString() ?: ""
            ),
            redis = RedisConfig(
                url = config.propertyOrNull("savebucks.redis.url")?.getString() ?: ""
            ),
            openai = OpenAiConfig(
                apiKey = config.propertyOrNull("savebucks.openai.apiKey")?.getString() ?: "",
                model = config.propertyOrNull("savebucks.openai.model")?.getString() ?: "gpt-4o-mini"
            ),
            vapid = VapidConfig(
                publicKey = config.propertyOrNull("savebucks.vapid.publicKey")?.getString() ?: "",
                privateKey = config.propertyOrNull("savebucks.vapid.privateKey")?.getString() ?: "",
                subject = config.propertyOrNull("savebucks.vapid.subject")?.getString()
                    ?: "mailto:admin@savebucks.com"
            ),
            cors = CorsConfig(
                allowedOrigins = (config.propertyOrNull("savebucks.cors.allowedOrigins")?.getString()
                    ?: "http://localhost:5173,http://localhost:3000")
                    .split(",").map { it.trim() }.filter { it.isNotBlank() }
            ),
            web = WebConfig(
                distPath = config.propertyOrNull("savebucks.web.distPath")?.getString() ?: "../web/dist"
            )
        )
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

data class VapidConfig(val publicKey: String, val privateKey: String, val subject: String) {
    /** Web push is disabled when VAPID keys are not configured. */
    val isEnabled: Boolean get() = publicKey.isNotBlank() && privateKey.isNotBlank()
}

data class CorsConfig(val allowedOrigins: List<String>)
data class WebConfig(val distPath: String)
