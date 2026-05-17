package com.savebucks.lib

/**
 * Base class for all application-level exceptions.
 * Each subclass maps to a specific HTTP status code via the StatusPages plugin.
 */
sealed class AppException(message: String, val statusCode: Int) : Exception(message)

/** 400 — malformed request body or invalid query parameter. */
class BadRequestException(message: String) : AppException(message, 400)

/** 401 — missing or invalid Bearer token. */
class UnauthorizedException(message: String = "Authentication required") : AppException(message, 401)

/** 403 — authenticated but lacks permission (e.g., non-admin hitting an admin route). */
class ForbiddenException(message: String = "Access denied") : AppException(message, 403)

/** 404 — resource not found in database. */
class NotFoundException(message: String) : AppException(message, 404)

/** 409 — unique constraint violation (duplicate handle, email already in use, etc.). */
class ConflictException(message: String) : AppException(message, 409)

/** 429 — rate limit exceeded; includes retry-after information in message. */
class TooManyRequestsException(message: String = "Rate limit exceeded") : AppException(message, 429)

/** 500 — unexpected server error; always logged with full stack trace. */
class InternalException(message: String = "Internal server error") : AppException(message, 500)
