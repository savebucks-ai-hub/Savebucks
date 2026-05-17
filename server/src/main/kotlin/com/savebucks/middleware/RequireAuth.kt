package com.savebucks.middleware

import com.savebucks.lib.ForbiddenException
import com.savebucks.lib.UnauthorizedException
import com.savebucks.lib.supabase.AuthPrincipal
import io.ktor.server.application.*
import io.ktor.server.auth.*

/**
 * Returns the authenticated principal or throws [UnauthorizedException].
 * Use inside route handlers that are wrapped with `authenticate("auth")`.
 */
fun ApplicationCall.requireAuth(): AuthPrincipal =
    principal<AuthPrincipal>() ?: throw UnauthorizedException("Authentication required")

/**
 * Returns the optional principal — null for unauthenticated guests.
 * Use inside routes wrapped with `authenticate("auth-optional")`.
 */
fun ApplicationCall.optionalAuth(): AuthPrincipal? = principal<AuthPrincipal>()

/**
 * Returns the principal and verifies the user has admin or moderator role.
 * Throws [ForbiddenException] otherwise.
 */
fun ApplicationCall.requireAdmin(): AuthPrincipal {
    val principal = requireAuth()
    if (principal.role != "admin" && principal.role != "moderator") {
        throw ForbiddenException("Admin access required")
    }
    return principal
}

/** Returns true if the authenticated user owns the resource identified by [ownerId]. */
fun ApplicationCall.isOwner(ownerId: String): Boolean =
    principal<AuthPrincipal>()?.userId == ownerId
