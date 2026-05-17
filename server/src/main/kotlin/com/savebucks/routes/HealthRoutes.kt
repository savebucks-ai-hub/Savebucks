package com.savebucks.routes

import com.savebucks.lib.ai.AiOrchestrator
import com.savebucks.lib.redis.RedisCache
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.models.successResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

/** Health-check endpoint used by load balancers and deployment platforms (Render, Railway, etc.). */
fun Route.healthRoutes() {
    val supabase: SupabaseAdmin by inject()
    val cache: RedisCache by inject()
    val ai: AiOrchestrator by inject()

    route("/health") {

        /** Fast liveness probe — just confirms the server process is alive. */
        get("/ping") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        /**
         * Deep readiness probe — checks all downstream dependencies.
         * Returns 503 when any critical dependency (Supabase) is unavailable.
         */
        get {
            val supabaseOk = runCatching {
                supabase.from("deals").select("id").limit(1).execute()
                true
            }.getOrDefault(false)

            val aiOk = runCatching { ai.healthCheck() }.getOrDefault(false)

            val status = HealthStatus(
                status = if (supabaseOk) "ok" else "degraded",
                supabase = if (supabaseOk) "ok" else "error",
                ai = if (aiOk) "ok" else "disabled",
                version = "1.0.0"
            )

            val httpStatus = if (supabaseOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(httpStatus, successResponse(status))
        }
    }
}

@Serializable
private data class HealthStatus(
    val status: String,
    val supabase: String,
    val ai: String,
    val version: String
)
