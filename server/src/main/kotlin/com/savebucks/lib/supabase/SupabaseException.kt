package com.savebucks.lib.supabase

/** Thrown when a Supabase REST API call returns an error status or unexpected shape. */
class SupabaseException(
    message: String,
    val statusCode: Int = 500,
    cause: Throwable? = null
) : Exception(message, cause)
