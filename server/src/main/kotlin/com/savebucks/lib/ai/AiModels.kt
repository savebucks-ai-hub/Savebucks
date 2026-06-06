package com.savebucks.lib.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body sent to OpenAI Chat Completions API. */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int = AiConfig.MAX_TOKENS_SIMPLE,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
    val tools: List<AiToolDef>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null
)

@Serializable
data class ChatMessage(
    val role: String,   // system | user | assistant | tool
    val content: String,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val arguments: String  // JSON string
)

/** OpenAI tool/function definition passed in the request. */
@Serializable
data class AiToolDef(
    val type: String = "function",
    val function: AiFunction
)

@Serializable
data class AiFunction(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject
)

/** Top-level response from OpenAI Chat Completions. */
@Serializable
data class ChatResponse(
    val id: String,
    val choices: List<ChatChoice>,
    val usage: TokenUsage? = null
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class TokenUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

/** Intent classification result. */
data class ClassifiedIntent(
    val intent: String,
    val confidence: Double,
    val entities: Map<String, String> = emptyMap()
)

/** Structured response returned by the AI orchestrator to route handlers. */
data class AiResponse(
    val message: String,
    val dealIds: List<String> = emptyList(),
    val intent: String = "general",
    val model: String = AiConfig.MODEL_DEFAULT,
    val provider: String = "openai",   // "groq" or "openai"
    val cached: Boolean = false,
    val tokensUsed: Int = 0,
    /**
     * True when the AI needs a zipcode to complete a local search.
     * The frontend should show a "Use My Location" button or a zip input field.
     * On the next request, supply the zip via the `zipcode` field or X-User-Zipcode header.
     */
    val requiresZipcode: Boolean = false
)
