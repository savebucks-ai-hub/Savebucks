package com.savebucks.models

import kotlinx.serialization.Serializable

/**
 * Standard envelope for every HTTP response from the Savebucks API.
 *
 * Every endpoint — success or failure — returns this shape so the React
 * client always knows where to look for data vs. error messages.
 *
 * @param T The type of the payload on success.
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val message: String? = null
)

/** Builds a successful response wrapping [data]. */
fun <T> successResponse(data: T, message: String? = null): ApiResponse<T> =
    ApiResponse(success = true, data = data, message = message)

/** Builds an error response. [data] is always null on error paths. */
fun errorResponse(error: String): ApiResponse<Nothing> =
    ApiResponse(success = false, error = error)

/** Paginated list wrapper used by list endpoints. */
@Serializable
data class PagedResponse<T>(
    val items: List<T>,
    val total: Int,
    val page: Int,
    val limit: Int,
    val hasMore: Boolean
)
