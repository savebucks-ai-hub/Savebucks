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
import java.io.File

/** Entry point — Netty embedded server on PORT (default 4000). */
fun main() {
    // Load .env file as JVM system properties BEFORE embeddedServer() so that
    // application.conf's ${?VAR} substitutions can resolve them via ConfigFactory.load().
    // Real env vars (e.g. in Docker/CI) always take precedence — .env is only used when
    // the variable is not already present in the system environment.
    loadDotEnv()

    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toIntOrNull() ?: 4000,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

private fun loadDotEnv() {
    // Search from where the JVM was launched (Gradle uses the module dir; IntelliJ may use root)
    val candidates = listOf(File(".env"), File("server/.env"))
    val envFile = candidates.firstOrNull { it.exists() } ?: return
    envFile.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || !trimmed.contains("=")) return@forEachLine
        val idx = trimmed.indexOf('=')
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        // Don't overwrite variables already set in the real system environment
        if (System.getenv(key) == null && System.getProperty(key) == null) {
            System.setProperty(key, value)
        }
    }
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
