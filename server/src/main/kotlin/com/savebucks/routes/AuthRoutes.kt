package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.middleware.requireAuth
import com.savebucks.models.successResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("AuthRoutes")

/**
 * Authentication routes mounted at /api/auth.
 *
 * All operations delegate to Supabase Auth — this server acts as a
 * thin proxy that validates inputs and translates HTTP responses.
 */
fun Route.authRoutes() {
    val supabase: SupabaseAdmin by inject()

    route("/auth") {

        /** Sign up a new user with email + password. */
        post("/signup") {
            val body = call.receive<SignupRequest>()

            if (!body.email.contains("@")) throw BadRequestException("Invalid email format")
            if (body.password.length < 6) throw BadRequestException("Password must be at least 6 characters")
            body.handle?.let {
                if (!it.matches(Regex("^[a-z0-9_-]{3,20}$"))) {
                    throw BadRequestException("Handle must be 3-20 characters, lowercase letters/numbers/dashes only")
                }
            }

            val userMeta = buildJsonObject {
                body.handle?.let { put("handle", it) }
            }
            val session = supabase.authSignUp(body.email, body.password, userMeta)
            log.info("New user signed up: ${body.email}")
            call.respond(HttpStatusCode.Created, successResponse(session))
        }

        /** Sign in with email + password. Returns access_token + refresh_token. */
        post("/signin") {
            val body = call.receive<SigninRequest>()
            if (body.email.isBlank() || body.password.isBlank()) {
                throw BadRequestException("Email and password are required")
            }

            val session = supabase.authSignIn(body.email, body.password)
            call.respond(HttpStatusCode.OK, successResponse(session))
        }

        /** Sign out — invalidates the token on Supabase's side. */
        post("/signout") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                ?: throw BadRequestException("Authorization header required")
            supabase.authSignOut(token)
            call.respond(HttpStatusCode.OK, successResponse("Signed out successfully"))
        }

        /** Exchange a refresh token for a new access token. */
        post("/refresh") {
            val body = call.receive<RefreshRequest>()
            if (body.refreshToken.isBlank()) throw BadRequestException("refresh_token is required")
            val session = supabase.authRefresh(body.refreshToken)
            call.respond(HttpStatusCode.OK, successResponse(session))
        }

        /** Initiate password reset — sends an email with a reset link. */
        post("/reset-password") {
            val body = call.receive<ResetPasswordRequest>()
            if (!body.email.contains("@")) throw BadRequestException("Invalid email")
            supabase.authResetPassword(body.email)
            call.respond(HttpStatusCode.OK, successResponse("Password reset email sent"))
        }

        /** Returns the current user's profile (requires authentication). */
        authenticate("auth") {
            get("/me") {
                val user = call.requireAuth()
                // Fetch the profile record from the public profiles table
                val profile = supabase.from("profiles")
                    .select("*")
                    .eq("id", user.userId)
                    .single()
                call.respond(HttpStatusCode.OK, successResponse(profile))
            }

            /** Update handle or avatar. */
            put("/profile") {
                val user = call.requireAuth()
                val body = call.receive<UpdateProfileRequest>()

                val updates = buildJsonObject {
                    body.handle?.let {
                        if (!it.matches(Regex("^[a-z0-9_-]{3,20}$"))) {
                            throw BadRequestException("Invalid handle format")
                        }
                        put("handle", it)
                    }
                    body.avatarUrl?.let { put("avatar_url", it) }
                }

                supabase.update("profiles", updates).eq("id", user.userId).execute()
                call.respond(HttpStatusCode.OK, successResponse("Profile updated"))
            }

            /** Update password — requires the current session token. */
            put("/update-password") {
                val user = call.requireAuth()
                val token = call.request.headers["Authorization"]!!.removePrefix("Bearer ").trim()
                val body = call.receive<UpdatePasswordRequest>()

                if (body.password.length < 6) throw BadRequestException("Password must be at least 6 characters")
                supabase.authUpdateUser(token, buildJsonObject { put("password", body.password) })
                call.respond(HttpStatusCode.OK, successResponse("Password updated"))
            }
        }
    }
}

// ─── Request bodies ──────────────────────────────────────────────────────────

@Serializable private data class SignupRequest(val email: String, val password: String, val handle: String? = null)
@Serializable private data class SigninRequest(val email: String, val password: String)
@Serializable private data class RefreshRequest(val refreshToken: String)
@Serializable private data class ResetPasswordRequest(val email: String)
@Serializable private data class UpdateProfileRequest(val handle: String? = null, val avatarUrl: String? = null)
@Serializable private data class UpdatePasswordRequest(val password: String)
