package com.savebucks.lib.supabase

import com.savebucks.config.SupabaseConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Central HTTP client for all Supabase REST API calls.
 *
 * Uses the service-role key for admin operations (bypasses Row Level Security).
 * Uses the user's JWT for user-scoped operations (RLS enforced by Supabase).
 *
 * This class wraps Supabase's PostgREST, Auth, and Storage APIs directly
 * via Ktor's HTTP client — no extra SDK dependency needed.
 */
class SupabaseAdmin(private val config: SupabaseConfig) {

    private val log = LoggerFactory.getLogger(SupabaseAdmin::class.java)

    /** Shared Ktor HttpClient — single instance for connection pooling. */
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = false
                explicitNulls = false
            })
        }
        install(Logging) {
            logger = object : Logger {
                private val slf4j = LoggerFactory.getLogger("SupabaseHttpClient")
                override fun log(message: String) = slf4j.debug(message)
            }
            level = LogLevel.NONE  // set to BODY during debugging
        }
        engine {
            requestTimeout = 30_000
            endpoint {
                maxConnectionsPerRoute = 50
                keepAliveTime = 60_000
            }
        }
    }

    // ─── Headers ────────────────────────────────────────────────────────────

    /** Headers used for admin (service-role) PostgREST queries. */
    private fun adminHeaders(): Map<String, String> = mapOf(
        "apikey" to config.serviceRoleKey,
        "Authorization" to "Bearer ${config.serviceRoleKey}",
        "Content-Type" to "application/json",
        "Prefer" to "return=representation"
    )

    /** Headers for user-scoped queries — passes the user's JWT through. */
    private fun userHeaders(token: String): Map<String, String> = mapOf(
        "apikey" to config.anonKey,
        "Authorization" to "Bearer $token",
        "Content-Type" to "application/json",
        "Prefer" to "return=representation"
    )

    // ─── Query Builder ──────────────────────────────────────────────────────

    /**
     * Entry point for PostgREST table queries.
     * Returns a [SupabaseQueryBuilder] that fluently accumulates filters
     * before executing the request.
     *
     * Example:
     *   val deals = from("deals").select("*").eq("status", "approved").execute()
     */
    fun from(table: String): SupabaseQueryBuilder =
        SupabaseQueryBuilder(client, config.url, table, adminHeaders())

    /**
     * Same as [from] but uses the provided user JWT — RLS is enforced.
     */
    fun fromAsUser(table: String, token: String): SupabaseQueryBuilder =
        SupabaseQueryBuilder(client, config.url, table, userHeaders(token))

    // ─── RPC ────────────────────────────────────────────────────────────────

    /**
     * Calls a Postgres stored function via PostgREST's `/rest/v1/rpc/{name}` endpoint.
     *
     * @param funcName The Postgres function name.
     * @param params   JSON object passed as the function arguments.
     * @return The function's return value decoded as [JsonElement], or null on error.
     */
    suspend fun rpc(funcName: String, params: JsonObject = JsonObject(emptyMap())): JsonElement? {
        return try {
            val response = client.post("${config.url}/rest/v1/rpc/$funcName") {
                adminHeaders().forEach { (k, v) -> headers.append(k, v) }
                setBody(params.toString())
                contentType(ContentType.Application.Json)
            }
            if (response.status.isSuccess()) response.body<JsonElement>() else null
        } catch (e: Exception) {
            log.warn("RPC call $funcName failed: ${e.message}")
            null
        }
    }

    // ─── Write Operations ───────────────────────────────────────────────────

    /**
     * Inserts a single row and returns the created row (Prefer: return=representation).
     * Throws [SupabaseException] on conflict or constraint violation.
     */
    suspend fun insert(table: String, data: JsonObject): JsonObject? {
        val response = client.post("${config.url}/rest/v1/$table") {
            adminHeaders().forEach { (k, v) -> headers.append(k, v) }
            setBody(data.toString())
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw SupabaseException("Insert into $table failed: ${response.bodyAsText()}", response.status.value)
        }
        return response.body<JsonArray>().firstOrNull()?.jsonObject
    }

    /**
     * Inserts multiple rows in one request.
     */
    suspend fun insertMany(table: String, data: JsonArray): JsonArray {
        val response = client.post("${config.url}/rest/v1/$table") {
            adminHeaders().forEach { (k, v) -> headers.append(k, v) }
            setBody(data.toString())
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw SupabaseException("Bulk insert into $table failed: ${response.bodyAsText()}", response.status.value)
        }
        return response.body<JsonArray>()
    }

    /**
     * Upserts a row (insert or update on conflict).
     *
     * @param onConflict Comma-separated column(s) that identify the unique constraint.
     */
    suspend fun upsert(table: String, data: JsonObject, onConflict: String): JsonObject? {
        val response = client.post("${config.url}/rest/v1/$table") {
            adminHeaders().forEach { (k, v) -> headers.append(k, v) }
            headers.append("Prefer", "resolution=merge-duplicates,return=representation")
            parameter("on_conflict", onConflict)
            setBody(data.toString())
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw SupabaseException("Upsert on $table failed: ${response.bodyAsText()}", response.status.value)
        }
        return response.body<JsonArray>().firstOrNull()?.jsonObject
    }

    /**
     * Returns an [UpdateBuilder] to chain `.eq()` filters before executing.
     *
     * Example:
     *   update("deals", buildJsonObject { put("status", "approved") })
     *       .eq("id", dealId)
     *       .execute()
     */
    fun update(table: String, data: JsonObject): UpdateBuilder =
        UpdateBuilder(client, config.url, table, data, adminHeaders())

    /**
     * Returns a [DeleteBuilder] to chain `.eq()` filters before executing.
     */
    fun delete(table: String): DeleteBuilder =
        DeleteBuilder(client, config.url, table, adminHeaders())

    // ─── Auth API ───────────────────────────────────────────────────────────

    /**
     * Validates a user's JWT by calling Supabase Auth's `/auth/v1/user` endpoint.
     * Returns the user JSON (including id, email, role) or null if the token is invalid.
     *
     * This is how authentication middleware validates every incoming request.
     */
    suspend fun authGetUser(token: String): JsonObject? {
        return try {
            val response = client.get("${config.url}/auth/v1/user") {
                headers.append("apikey", config.anonKey)
                headers.append("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) response.body<JsonObject>() else null
        } catch (e: Exception) {
            log.debug("authGetUser failed (likely invalid token): ${e.message}")
            null
        }
    }

    /** Signs up a new user with email/password. Returns session + user JSON. */
    suspend fun authSignUp(email: String, password: String, data: JsonObject? = null): JsonObject {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            data?.let { put("data", it) }
        }
        val response = client.post("${config.url}/auth/v1/signup") {
            headers.append("apikey", config.anonKey)
            setBody(body.toString())
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            val error = runCatching { response.body<JsonObject>()["msg"]?.jsonPrimitive?.content }.getOrNull()
                ?: response.bodyAsText()
            throw SupabaseException(error, response.status.value)
        }
        return response.body<JsonObject>()
    }

    /** Signs in with email/password. Returns session tokens + user. */
    suspend fun authSignIn(email: String, password: String): JsonObject {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
        }
        val response = client.post("${config.url}/auth/v1/token") {
            parameter("grant_type", "password")
            headers.append("apikey", config.anonKey)
            setBody(body.toString())
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            val error = runCatching { response.body<JsonObject>()["error_description"]?.jsonPrimitive?.content }.getOrNull()
                ?: "Invalid credentials"
            throw SupabaseException(error, response.status.value)
        }
        return response.body<JsonObject>()
    }

    /** Signs out — invalidates the access token on Supabase's side. */
    suspend fun authSignOut(token: String) {
        client.post("${config.url}/auth/v1/logout") {
            headers.append("apikey", config.anonKey)
            headers.append("Authorization", "Bearer $token")
        }
    }

    /** Exchanges a refresh token for new access + refresh tokens. */
    suspend fun authRefresh(refreshToken: String): JsonObject {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        val response = client.post("${config.url}/auth/v1/token") {
            parameter("grant_type", "refresh_token")
            headers.append("apikey", config.anonKey)
            setBody(body.toString())
            contentType(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw SupabaseException("Token refresh failed", response.status.value)
        }
        return response.body<JsonObject>()
    }

    /** Sends a magic link / password-reset email. */
    suspend fun authResetPassword(email: String) {
        val body = buildJsonObject { put("email", email) }
        client.post("${config.url}/auth/v1/recover") {
            headers.append("apikey", config.anonKey)
            setBody(body.toString())
            contentType(ContentType.Application.Json)
        }
    }

    /** Updates the authenticated user's metadata (password, email, etc.). */
    suspend fun authUpdateUser(token: String, data: JsonObject): JsonObject? {
        val response = client.put("${config.url}/auth/v1/user") {
            headers.append("apikey", config.anonKey)
            headers.append("Authorization", "Bearer $token")
            setBody(data.toString())
            contentType(ContentType.Application.Json)
        }
        return if (response.status.isSuccess()) response.body<JsonObject>() else null
    }

    // ─── Storage ─────────────────────────────────────────────────────────────

    /**
     * Uploads a file to Supabase Storage.
     *
     * @param bucket  The storage bucket name.
     * @param path    The path within the bucket (e.g., "avatars/user-123.jpg").
     * @param bytes   The raw file bytes.
     * @param mime    MIME type (e.g., "image/jpeg").
     * @return The public URL of the uploaded file, or null on failure.
     */
    suspend fun storageUpload(bucket: String, path: String, bytes: ByteArray, mime: String): String? {
        return try {
            val response = client.post("${config.url}/storage/v1/object/$bucket/$path") {
                headers.append("apikey", config.serviceRoleKey)
                headers.append("Authorization", "Bearer ${config.serviceRoleKey}")
                contentType(ContentType.parse(mime))
                setBody(bytes)
            }
            if (response.status.isSuccess()) {
                "${config.url}/storage/v1/object/public/$bucket/$path"
            } else {
                log.warn("Storage upload failed: ${response.bodyAsText()}")
                null
            }
        } catch (e: Exception) {
            log.error("Storage upload exception: ${e.message}", e)
            null
        }
    }

    /** Closes the underlying HttpClient — call on application shutdown. */
    fun close() = client.close()
}

// ─── Query Builder ─────────────────────────────────────────────────────────────

/**
 * Fluent builder for PostgREST GET queries.
 * Accumulates filter parameters and executes the request on [execute] / [single].
 */
class SupabaseQueryBuilder(
    private val client: HttpClient,
    private val baseUrl: String,
    private val table: String,
    private val headers: Map<String, String>
) {
    private var selectCols = "*"
    private val filters = mutableListOf<Pair<String, String>>()
    private var orderCol: String? = null
    private var orderAsc = false
    private var limitVal: Int? = null
    private var offsetVal: Int? = null

    fun select(columns: String) = apply { selectCols = columns }
    fun eq(col: String, value: Any?) = apply { filters += col to "eq.${value}" }
    fun neq(col: String, value: Any?) = apply { filters += col to "neq.${value}" }
    fun gt(col: String, value: Any?) = apply { filters += col to "gt.${value}" }
    fun gte(col: String, value: Any?) = apply { filters += col to "gte.${value}" }
    fun lt(col: String, value: Any?) = apply { filters += col to "lt.${value}" }
    fun lte(col: String, value: Any?) = apply { filters += col to "lte.${value}" }
    fun ilike(col: String, pattern: String) = apply { filters += col to "ilike.$pattern" }
    fun `is`(col: String, value: String) = apply { filters += col to "is.$value" }

    /** PostgREST `in` filter — col=in.(v1,v2,v3) */
    fun `in`(col: String, values: List<Any?>) = apply {
        filters += col to "in.(${values.joinToString(",")})"
    }

    /** Text search filter using PostgREST's `fts` operator. */
    fun textSearch(col: String, query: String) = apply {
        filters += col to "fts.${query}"
    }

    fun order(col: String, ascending: Boolean = false) = apply {
        orderCol = col
        orderAsc = ascending
    }

    fun limit(n: Int) = apply { limitVal = n }
    fun offset(n: Int) = apply { offsetVal = n }

    /** Executes the query and returns all matching rows as a [JsonArray]. */
    suspend fun execute(): JsonArray {
        val h = headers
        val response = client.get("$baseUrl/rest/v1/$table") {
            h.forEach { (k, v) -> this.headers.append(k, v) }
            parameter("select", selectCols)
            filters.forEach { (col, filter) -> parameter(col, filter) }
            orderCol?.let { parameter("order", "$it.${if (orderAsc) "asc" else "desc"}") }
            limitVal?.let { parameter("limit", it) }
            offsetVal?.let { parameter("offset", it) }
        }
        return if (response.status.isSuccess()) response.body<JsonArray>() else JsonArray(emptyList())
    }

    /** Returns the first matching row, or null if none found. */
    suspend fun single(): JsonObject? = execute().firstOrNull()?.jsonObject

    /** Returns the count of matching rows using the `Prefer: count=exact` header. */
    suspend fun count(): Int {
        val h = headers
        val response = client.get("$baseUrl/rest/v1/$table") {
            h.forEach { (k, v) -> this.headers.append(k, v) }
            this.headers.append("Prefer", "count=exact")
            this.headers.append("Range-Unit", "items")
            parameter("select", "id")
            filters.forEach { (col, filter) -> parameter(col, filter) }
        }
        // PostgREST returns count in the Content-Range header: "0-9/42"
        val range = response.headers["Content-Range"] ?: return 0
        return range.substringAfter("/").toIntOrNull() ?: 0
    }
}

// ─── Update / Delete Builders ──────────────────────────────────────────────────

class UpdateBuilder(
    private val client: HttpClient,
    private val baseUrl: String,
    private val table: String,
    private val data: JsonObject,
    private val headers: Map<String, String>
) {
    private val filters = mutableListOf<Pair<String, String>>()

    fun eq(col: String, value: Any?) = apply { filters += col to "eq.${value}" }

    suspend fun execute(): JsonArray {
        val h = headers
        val response = client.patch("$baseUrl/rest/v1/$table") {
            h.forEach { (k, v) -> this.headers.append(k, v) }
            filters.forEach { (col, filter) -> parameter(col, filter) }
            setBody(data.toString())
            contentType(ContentType.Application.Json)
        }
        return if (response.status.isSuccess()) response.body<JsonArray>() else JsonArray(emptyList())
    }
}

class DeleteBuilder(
    private val client: HttpClient,
    private val baseUrl: String,
    private val table: String,
    private val headers: Map<String, String>
) {
    private val filters = mutableListOf<Pair<String, String>>()

    fun eq(col: String, value: Any?) = apply { filters += col to "eq.${value}" }

    suspend fun execute() {
        val h = headers
        client.delete("$baseUrl/rest/v1/$table") {
            h.forEach { (k, v) -> this.headers.append(k, v) }
            filters.forEach { (col, filter) -> parameter(col, filter) }
        }
    }
}
