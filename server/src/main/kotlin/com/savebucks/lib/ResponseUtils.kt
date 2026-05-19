package com.savebucks.models

import kotlinx.serialization.json.*

/**
 * Overload of [successResponse] for untyped Map responses.
 *
 * Routes that call `successResponse(mapOf("key" to value, ...))` land here.
 * Every value is converted to a [JsonElement] so the response is directly
 * serializable by Ktor's content-negotiation without needing a @Serializable
 * wrapper around Map<String, Any>.
 */
fun successResponse(data: Map<String, Any?>): JsonObject = buildJsonObject {
    put("success", true)
    putJsonObject("data") {
        data.forEach { (key, value) -> put(key, toJsonElement(value)) }
    }
}

@Suppress("UNCHECKED_CAST")
private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map { toJsonElement(it) })
    is Map<*, *> -> JsonObject((value as Map<String, Any?>).mapValues { (_, v) -> toJsonElement(v) })
    else -> JsonPrimitive(value.toString())
}
