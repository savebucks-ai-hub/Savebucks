package com.savebucks.plugins

import com.savebucks.config.AppConfig
import com.savebucks.lib.supabase.AuthPrincipal
import com.savebucks.lib.supabase.SupabaseAdmin
import io.ktor.server.application.*
import io.ktor.server.auth.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Authentication")

/**
 * Installs two bearer-token authentication schemes:
 *
 *  - "auth"          → required; returns 401 if the token is missing or invalid
 *  - "auth-optional" → optional; proceeds as a guest when no token is provided
 *
 * Token validation is delegated to Supabase's `/auth/v1/user` endpoint rather
 * than verifying the JWT locally. This ensures revoked tokens are always rejected
 * without needing to replicate Supabase's key-rotation logic.
 */
fun Application.configureAuthentication(config: AppConfig) {
    install(Authentication) {

        bearer("auth") {
            realm = "Savebucks API"
            authenticate { credential ->
                val supabase: SupabaseAdmin by inject()
                resolveUser(supabase, credential.token)
            }
        }

        bearer("auth-optional") {
            realm = "Savebucks API"
            authenticate { credential ->
                val supabase: SupabaseAdmin by inject()
                // Returns null for guests — routes handle null principal gracefully
                runCatching { resolveUser(supabase, credential.token) }.getOrNull()
            }
        }
    }
}

/**
 * Calls Supabase and maps the user JSON to our [AuthPrincipal].
 * Returns null when the token is invalid so Ktor can return 401.
 */
private suspend fun resolveUser(supabase: SupabaseAdmin, token: String): AuthPrincipal? {
    val user = supabase.authGetUser(token) ?: return null
    val userId = user["id"]?.jsonPrimitive?.contentOrNull ?: return null

    // Supabase stores custom role in app_metadata.role
    val appMeta = user["app_metadata"]?.jsonObject
    val role = appMeta?.get("role")?.jsonPrimitive?.contentOrNull
        ?: user["role"]?.jsonPrimitive?.contentOrNull

    return AuthPrincipal(
        userId = userId,
        email = user["email"]?.jsonPrimitive?.contentOrNull,
        role = role
    )
}
