package com.savebucks.plugins

import com.savebucks.lib.*
import com.savebucks.models.errorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

/**
 * Centralised exception → HTTP response mapping.
 *
 * Every AppException subclass maps to its designated HTTP status.
 * Unexpected exceptions are logged at ERROR level and returned as 500s
 * without leaking stack traces to the client.
 */
fun Application.configureStatusPages() {
    val log = LoggerFactory.getLogger("StatusPages")
    install(StatusPages) {
        // AppException hierarchy — each subclass carries its own status code
        exception<AppException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                errorResponse(cause.message ?: "An error occurred")
            )
        }

        // JSON body parsing failures
        exception<SerializationException> { call, cause ->
            log.debug("Serialization error: ${cause.message}")
            call.respond(HttpStatusCode.BadRequest, errorResponse("Invalid request body: ${cause.message}"))
        }

        // Fallback for anything we didn't anticipate — log full stack but don't expose it
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception: ${cause.message}", cause)
            call.respond(HttpStatusCode.InternalServerError, errorResponse("Internal server error"))
        }
    }
}
