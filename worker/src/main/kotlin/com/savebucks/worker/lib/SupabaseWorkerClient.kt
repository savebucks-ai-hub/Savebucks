package com.savebucks.worker.lib

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(SupabaseWorkerClient::class.java)

/**
 * Minimal Supabase client for the worker process.
 *
 * Purposely simpler than the server's SupabaseAdmin — the worker only
 * needs a handful of operations (read pending items, insert deals, update statuses).
 */
class SupabaseWorkerClient(
    private val baseUrl: String,
    private val serviceRoleKey: String
) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        engine { requestTimeout = 30_000 }
    }

    private fun adminHeaders() = mapOf(
        "apikey" to serviceRoleKey,
        "Authorization" to "Bearer $serviceRoleKey",
        "Content-Type" to "application/json",
        "Prefer" to "return=representation"
    )

    /** Fetches rows from [table] with optional filter and column selection. */
    suspend fun select(table: String, params: Map<String, String> = emptyMap()): JsonArray {
        return try {
            val response = client.get("$baseUrl/rest/v1/$table") {
                adminHeaders().forEach { (k, v) -> headers.append(k, v) }
                params.forEach { (k, v) -> parameter(k, v) }
            }
            if (response.status.isSuccess()) response.body<JsonArray>() else JsonArray(emptyList())
        } catch (e: Exception) {
            log.error("Supabase select failed on $table: ${e.message}", e)
            JsonArray(emptyList())
        }
    }

    /** Inserts a single row into [table]. Returns the created row or null on failure. */
    suspend fun insert(table: String, data: JsonObject): JsonObject? {
        return try {
            val response = client.post("$baseUrl/rest/v1/$table") {
                adminHeaders().forEach { (k, v) -> headers.append(k, v) }
                setBody(data.toString())
                contentType(ContentType.Application.Json)
            }
            if (response.status.isSuccess()) response.body<JsonArray>().firstOrNull()?.jsonObject
            else { log.warn("Insert into $table failed: ${response.bodyAsText()}"); null }
        } catch (e: Exception) {
            log.error("Supabase insert failed on $table: ${e.message}", e)
            null
        }
    }

    /** Updates rows in [table] matching [filter]. */
    suspend fun update(table: String, data: JsonObject, filter: Map<String, String>) {
        try {
            client.patch("$baseUrl/rest/v1/$table") {
                adminHeaders().forEach { (k, v) -> headers.append(k, v) }
                filter.forEach { (k, v) -> parameter(k, v) }
                setBody(data.toString())
                contentType(ContentType.Application.Json)
            }
        } catch (e: Exception) {
            log.error("Supabase update failed on $table: ${e.message}", e)
        }
    }

    /**
     * Deletes rows in [table] matching [filter].
     * Returns the count of deleted rows (requires the Prefer: return=representation header,
     * which is already set in adminHeaders).
     */
    suspend fun delete(table: String, filter: Map<String, String>): Int {
        return try {
            val response = client.delete("$baseUrl/rest/v1/$table") {
                adminHeaders().forEach { (k, v) -> headers.append(k, v) }
                filter.forEach { (k, v) -> parameter(k, v) }
            }
            if (response.status.isSuccess()) {
                response.body<JsonArray>().size
            } else {
                log.warn("Delete from $table failed: ${response.bodyAsText()}")
                0
            }
        } catch (e: Exception) {
            log.error("Supabase delete failed on $table: ${e.message}", e)
            0
        }
    }

    /**
     * Logs an error event to the ingestion_errors table (best-effort — never throws).
     * The table is expected to have columns: source, error_message, error_type, context.
     * If the table doesn't exist this silently does nothing.
     */
    suspend fun logError(source: String, error: Throwable, context: Map<String, String> = emptyMap()) {
        runCatching {
            insert("ingestion_errors", buildJsonObject {
                put("source", source)
                put("error_message", (error.message ?: "Unknown error").take(500))
                put("error_type", error::class.simpleName ?: "Exception")
                if (context.isNotEmpty()) {
                    put("context", buildJsonObject { context.forEach { (k, v) -> put(k, v) } })
                }
            })
        }.onFailure { log.debug("Could not log error to ingestion_errors: ${it.message}") }
    }

    /** Closes the underlying Ktor HttpClient. */
    fun close() = client.close()
}
