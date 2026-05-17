package com.savebucks

import com.savebucks.config.AppConfig
import com.savebucks.di.appModule
import com.savebucks.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory

/** Entry point — Netty embedded server on PORT (default 4000). */
fun main() {
    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toIntOrNull() ?: 4000,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

/**
 * Ktor application module.
 *
 * Installation order matters:
 *  1. Koin (DI) — must be first so injected singletons are available to all plugins
 *  2. Serialization — required before any JSON route response
 *  3. CORS — must be before routing so pre-flight OPTIONS is intercepted
 *  4. Everything else in any order
 */
fun Application.module() {
    val log = LoggerFactory.getLogger("com.savebucks.Application")
    val config = AppConfig.from(environment.config)

    install(Koin) {
        slf4jLogger()
        modules(appModule(config))
    }

    configureSerialization()
    configureCORS(config.cors)
    configureCompression()
    configureCallLogging()
    configureDefaultHeaders()
    configureStatusPages()
    configureAuthentication(config)
    configureRouting(config)

    log.info("✅  Savebucks API started — listening on port ${System.getenv("PORT") ?: "4000"}")
}
