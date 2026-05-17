package com.savebucks.plugins

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
 */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        })
    }
}
