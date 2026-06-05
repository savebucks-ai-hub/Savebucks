package com.savebucks.plugins

import com.savebucks.config.CorsConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

/**
 * Configures CORS with the origins listed in [CorsConfig.allowedOrigins].
 *
 * Credentials are allowed because the React client sends session cookies
 * and Authorization headers.
 */
fun Application.configureCORS(config: CorsConfig) {
    install(CORS) {
        config.allowedOrigins.forEach { origin ->
            // Ktor's allowHost() expects the host:port string and scheme separately.
            // "http://localhost:5173" → host="localhost:5173", scheme="http"
            val url = origin.trimEnd('/')
            val scheme = if (url.startsWith("https")) "https" else "http"
            val hostWithPort = url.removePrefix("https://").removePrefix("http://")

            allowHost(hostWithPort, schemes = listOf(scheme))
        }

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowHeader("X-Requested-With")
        allowHeader("X-Request-ID")

        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)

        allowCredentials = true
        allowNonSimpleContentTypes = true

        maxAgeInSeconds = 86_400  // pre-flight cache for 24 h
    }
}
