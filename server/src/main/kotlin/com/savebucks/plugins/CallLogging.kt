package com.savebucks.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import org.slf4j.event.Level

/** Logs every inbound request at INFO level, excluding noisy health-check polling. */
fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        // Skip /api/health so load-balancer pings don't flood the log
        filter { call -> !call.request.path().startsWith("/api/health") }
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val duration = call.processingTimeMillis()
            "$method $path → $status (${duration}ms)"
        }
    }
}

/**
 * Adds security-hardening response headers to every response.
 *
 * These do not replace a WAF/CDN layer but provide defence-in-depth
 * for clients that don't go through one.
 */
fun Application.configureDefaultHeaders() {
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("X-XSS-Protection", "1; mode=block")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
    }
}
