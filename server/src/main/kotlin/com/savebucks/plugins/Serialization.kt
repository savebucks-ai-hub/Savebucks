package com.savebucks.plugins

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

/**
 * Installs kotlinx.serialization as the content negotiation provider.
 *
 * - ignoreUnknownKeys: forgiving when Supabase adds new columns we haven't modelled yet
 * - isLenient: accepts unquoted JSON strings (useful for testing with curl)
 * - encodeDefaults = false: keeps response payloads compact
 *
 * The ContentType.Any fallback prevents 406 errors from clients that send
 * non-standard Accept headers (e.g. text/event-stream from streaming clients).
 * This is a pure JSON API — we always respond with JSON regardless of Accept.
 */
fun Application.configureSerialization() {
    val jsonConfig = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    install(ContentNegotiation) {
        json(jsonConfig)
        json(jsonConfig, contentType = ContentType.Any)
    }
}
