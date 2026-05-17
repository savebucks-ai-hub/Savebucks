package com.savebucks.plugins

import com.savebucks.config.AppConfig
import com.savebucks.routes.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Mounts all API routes under /api/ and configures static file serving for the React SPA.
 *
 * Route registration order:
 *  - /api/health    — liveness/readiness probes
 *  - /api/auth      — authentication
 *  - /api/deals     — deal CRUD + interactions
 *  - /api/coupons   — coupon CRUD + interactions
 *  - /api/search    — search + suggestions
 *  - /api/feed      — unified feed
 *  - /api/users     — profiles + social graph
 *  - /api/ai        — AI chat assistant
 *  - /api/notifications — push + in-app
 *  - /api/gamification  — XP + achievements
 *  - /api/analytics     — event tracking
 *  - /api/categories    — categories + collections + banners
 *  - /api/reviews       — deal reviews
 *  - /api/admin         — moderation tools (admin only)
 *
 * After API routes, a catch-all serves the React SPA's index.html for any non-/api/ path.
 */
fun Application.configureRouting(config: AppConfig) {
    val log = LoggerFactory.getLogger("Routing")
    routing {
        route("/api") {
            healthRoutes()
            authRoutes()
            dealsRoutes()
            couponsRoutes()
            searchRoutes()
            feedRoutes()
            usersRoutes()
            aiRoutes()
            notificationsRoutes()
            gamificationRoutes()
            analyticsRoutes()
            categoriesRoutes()
            reviewsRoutes()
            adminRoutes()
        }

        // Serve the React SPA static files from the dist directory
        val distDir = File(config.web.distPath)
        if (distDir.exists()) {
            staticFiles("/", distDir) {
                default("index.html")   // fallback for client-side routing (React Router)
            }
            log.info("Serving React web app from ${distDir.absolutePath}")
        } else {
            log.warn("Web dist directory not found at ${distDir.absolutePath} — API-only mode")
            // Return a helpful message when no frontend is built yet
            get("/") {
                call.respond(HttpStatusCode.OK, mapOf(
                    "message" to "Savebucks API is running",
                    "docs" to "Hit /api/health to verify all dependencies are up"
                ))
            }
        }
    }
}
