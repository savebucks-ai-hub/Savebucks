package com.savebucks.lib.supabase

import io.ktor.server.auth.*

/**
 * The authenticated-user principal attached to every request that passes
 * the bearer-token middleware.
 *
 * Route handlers retrieve it via:
 *   val user = call.requireAuth()        // throws 401 if absent
 *   val user = call.optionalAuth()       // returns null for guests
 */
data class AuthPrincipal(
    /** Supabase user UUID. */
    val userId: String,
    val email: String?,
    /** "user" | "moderator" | "admin" — sourced from Supabase user metadata. */
    val role: String?,
    /** Display handle from the profiles table — populated after a secondary lookup if needed. */
    val handle: String? = null
) : Principal
