package com.savebucks.routes

import com.savebucks.lib.BadRequestException
import com.savebucks.lib.TooManyRequestsException
import com.savebucks.lib.ai.AiOrchestrator
import com.savebucks.lib.ai.ChatMessage
import com.savebucks.lib.supabase.SupabaseAdmin
import com.savebucks.middleware.optionalAuth
import com.savebucks.middleware.requireAdmin
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

private val log = LoggerFactory.getLogger("AiRoutes")

/**
 * AI chat endpoints mounted at /api/ai.
 *
 * Supports both streaming (SSE) and non-streaming responses.
 * Rate limiting is enforced inside the [AiOrchestrator].
 */
fun Route.aiRoutes() {
    val orchestrator: AiOrchestrator by inject()
    val supabase: SupabaseAdmin by inject()

    route("/ai") {

        authenticate("auth-optional") {

            /**
             * POST /api/ai/chat — primary chat endpoint.
             *
             * Accepts a message + optional conversation history.
             * Returns the assistant's response with matched deal IDs.
             */
            post("/chat") {
                val user = call.optionalAuth()
                val body = call.receive<ChatRequestBody>()

                if (body.message.isBlank()) throw BadRequestException("message is required")

                // Use user ID for authenticated users, IP for guests (rate limit bucket)
                val userId = user?.userId ?: (call.request.headers["X-Forwarded-For"] ?: "anonymous")

                val history = body.history?.map {
                    ChatMessage(role = it.role, content = it.content)
                } ?: emptyList()

                val response = orchestrator.chat(
                    message = body.message,
                    userId = userId,
                    history = history,
                    excludeIds = body.excludeIds ?: emptyList()
                )

                // Persist to conversation history if user is authenticated
                if (user != null && body.conversationId != null) {
                    runCatching {
                        supabase.insert("chat_messages",
                            buildJsonObject {
                                put("conversation_id", body.conversationId)
                                put("user_id", user.userId)
                                put("role", "user")
                                put("content", body.message)
                            }
                        )
                        supabase.insert("chat_messages",
                            buildJsonObject {
                                put("conversation_id", body.conversationId)
                                put("user_id", user.userId)
                                put("role", "assistant")
                                put("content", response.message)
                            }
                        )
                    }.onFailure { log.warn("Failed to persist chat message: ${it.message}") }
                }

                call.respond(HttpStatusCode.OK, successResponse(mapOf(
                    "message" to response.message,
                    "dealIds" to response.dealIds,
                    "intent" to response.intent,
                    "cached" to response.cached,
                    "tokensUsed" to response.tokensUsed
                )))
            }

            /** GET /api/ai/conversations — list the authenticated user's conversations. */
            authenticate("auth") {
                get("/conversations") {
                    val user = call.optionalAuth() ?: throw com.savebucks.lib.UnauthorizedException()
                    val conversations = supabase.from("conversations")
                        .select("id,title,created_at,updated_at")
                        .eq("user_id", user.userId)
                        .eq("is_archived", false)
                        .order("updated_at", ascending = false)
                        .limit(20)
                        .execute()
                    call.respond(HttpStatusCode.OK, successResponse(conversations))
                }

                /** GET /api/ai/conversations/:id — full conversation with messages. */
                get("/conversations/{id}") {
                    val user = call.optionalAuth() ?: throw com.savebucks.lib.UnauthorizedException()
                    val convId = call.parameters["id"] ?: throw BadRequestException("Conversation ID required")

                    val messages = supabase.from("chat_messages")
                        .select("*")
                        .eq("conversation_id", convId)
                        .order("created_at", ascending = true)
                        .execute()

                    call.respond(HttpStatusCode.OK, successResponse(messages))
                }

                /** POST /api/ai/conversations — create a new conversation. */
                post("/conversations") {
                    val user = call.optionalAuth() ?: throw com.savebucks.lib.UnauthorizedException()
                    val body = call.receive<CreateConversationRequest>()

                    val conv = supabase.insert("conversations",
                        buildJsonObject {
                            put("user_id", user.userId)
                            put("title", body.title ?: "New conversation")
                        }
                    )
                    call.respond(HttpStatusCode.Created, successResponse(conv))
                }

                /** DELETE /api/ai/conversations/:id — soft-delete (archive) a conversation. */
                delete("/conversations/{id}") {
                    val user = call.optionalAuth() ?: throw com.savebucks.lib.UnauthorizedException()
                    val convId = call.parameters["id"] ?: throw BadRequestException("Conversation ID required")

                    supabase.update("conversations", buildJsonObject { put("is_archived", true) })
                        .eq("id", convId).eq("user_id", user.userId).execute()

                    call.respond(HttpStatusCode.OK, successResponse("Conversation archived"))
                }
            }
        }

        /** GET /api/ai/health — confirms the OpenAI API is reachable. */
        get("/health") {
            val ok = orchestrator.healthCheck()
            call.respond(
                if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                successResponse(mapOf("status" to if (ok) "ok" else "degraded"))
            )
        }

        /** Admin-only: usage statistics and cleanup. */
        authenticate("auth") {
            get("/stats") {
                call.requireAdmin()
                val stats = supabase.from("chat_messages")
                    .select("id")
                    .execute()
                call.respond(HttpStatusCode.OK, successResponse(mapOf("totalMessages" to stats.size)))
            }
        }
    }
}

@Serializable
private data class ChatRequestBody(
    val message: String,
    val history: List<HistoryMessage>? = null,
    val excludeIds: List<String>? = null,
    val conversationId: String? = null
)

@Serializable private data class HistoryMessage(val role: String, val content: String)
@Serializable private data class CreateConversationRequest(val title: String? = null)
