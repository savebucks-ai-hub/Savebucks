package com.savebucks.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*

/**
 * Enables GZIP/Deflate compression for API responses.
 *
 * SSE (text/event-stream) is explicitly excluded because streaming responses
 * cannot be compressed — the client reads chunks incrementally.
 */
fun Application.configureCompression() {
    install(Compression) {
        gzip {
            priority = 1.0
            condition {
                // Don't compress Server-Sent Events — they must stream in real time
                request.headers[HttpHeaders.Accept] != ContentType.Text.EventStream.toString()
            }
        }
        deflate {
            priority = 0.9
        }
        minimumSize(1024)  // only compress responses larger than 1 KB
    }
}
