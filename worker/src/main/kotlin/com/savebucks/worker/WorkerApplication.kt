package com.savebucks.worker

import com.savebucks.worker.jobs.ExpiryJob
import com.savebucks.worker.jobs.DealIngestionJob
import com.savebucks.worker.lib.Scheduler
import com.savebucks.worker.lib.SupabaseWorkerClient
import com.savebucks.worker.lib.TelegramBot
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("WorkerApplication")

/**
 * Entry point for the background worker process.
 *
 * Runs completely independently of the Ktor server — communicates with
 * Supabase directly via HTTP. Designed to be deployed as a separate
 * service (e.g., a second Render web service or a cron job).
 *
 * Jobs:
 *  - ExpiryJob     — marks deals/coupons as expired when their expiry date passes
 *  - DealIngestionJob — scrapes external deal sources and inserts new deals
 *  - TelegramBot   — optional Telegram channel monitor (enable via TELEGRAM_BOT_TOKEN)
 */
fun main() { runBlocking {
    loadDotEnv()
    log.info("Starting Savebucks worker...")

    val supabaseUrl = env("SUPABASE_URL") ?: error("SUPABASE_URL required — set it in worker/.env or server/.env")
    val serviceRoleKey = env("SUPABASE_SERVICE_ROLE") ?: error("SUPABASE_SERVICE_ROLE required — set it in worker/.env or server/.env")

    val supabase = SupabaseWorkerClient(supabaseUrl, serviceRoleKey)
    val scheduler = Scheduler()

    // Register periodic jobs
    scheduler.scheduleRepeating("ExpiryJob", intervalMinutes = 30) {
        ExpiryJob(supabase).run()
    }

    scheduler.scheduleRepeating("DealIngestionJob", intervalMinutes = 25) {
        DealIngestionJob(supabase).run()
    }

    // Telegram bot is optional — only starts if the token is present
    val telegramToken = env("TELEGRAM_BOT_TOKEN")
    if (!telegramToken.isNullOrBlank()) {
        val allowedChannels = env("TELEGRAM_ALLOWED_CHANNELS")
            ?.split(",")?.map { it.trim() } ?: emptyList()
        TelegramBot(supabase, telegramToken, allowedChannels).start(this)
        log.info("Telegram bot started for channels: $allowedChannels")
    }

    // Register OS signal handlers for graceful shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutdown signal received — stopping worker...")
        scheduler.stop()
        supabase.close()
        log.info("Worker stopped cleanly.")
    })

    scheduler.start()
    log.info("Savebucks worker running. Jobs: ExpiryJob (30m), DealIngestionJob (25m)")

    // Keep the process alive until a shutdown signal (SIGTERM/SIGINT) is received.
    // Without this, runBlocking returns immediately after registering the jobs because
    // the scheduler runs on its own CoroutineScope, not this one.
    awaitCancellation()
} }

/** Reads a variable from the OS environment first, then JVM system properties (set by loadDotEnv). */
private fun env(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(key)?.takeIf { it.isNotBlank() }

/**
 * Parses a .env file and loads each key into JVM system properties so
 * System.getProperty() can find them. Searches worker/.env then server/.env.
 * Real OS environment variables always take precedence and are never overwritten.
 */
private fun loadDotEnv() {
    val candidates = listOf(File(".env"), File("worker/.env"), File("server/.env"))
    val envFile = candidates.firstOrNull { it.exists() }
    if (envFile == null) {
        LoggerFactory.getLogger("WorkerApplication")
            .warn("No .env file found (checked: ${candidates.map { it.path }}). Relying on system environment variables.")
        return
    }
    var loaded = 0
    envFile.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || !trimmed.contains("=")) return@forEachLine
        val idx = trimmed.indexOf('=')
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (System.getenv(key) == null && System.getProperty(key) == null) {
            System.setProperty(key, value)
            loaded++
        }
    }
    LoggerFactory.getLogger("WorkerApplication")
        .info("Loaded $loaded variable(s) from ${envFile.path}")
}
