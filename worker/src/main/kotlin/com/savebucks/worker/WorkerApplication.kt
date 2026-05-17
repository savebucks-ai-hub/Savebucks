package com.savebucks.worker

import com.savebucks.worker.jobs.ExpiryJob
import com.savebucks.worker.jobs.DealIngestionJob
import com.savebucks.worker.lib.Scheduler
import com.savebucks.worker.lib.SupabaseWorkerClient
import com.savebucks.worker.lib.TelegramBot
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

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
fun main() = runBlocking {
    log.info("Starting Savebucks worker...")

    val supabaseUrl = System.getenv("SUPABASE_URL") ?: error("SUPABASE_URL required")
    val serviceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE") ?: error("SUPABASE_SERVICE_ROLE required")

    val supabase = SupabaseWorkerClient(supabaseUrl, serviceRoleKey)
    val scheduler = Scheduler()

    // Register periodic jobs
    scheduler.scheduleRepeating("ExpiryJob", intervalMinutes = 30) {
        ExpiryJob(supabase).run()
    }

    scheduler.scheduleRepeating("DealIngestionJob", intervalMinutes = 60) {
        DealIngestionJob(supabase).run()
    }

    // Telegram bot is optional — only starts if the token is present
    val telegramToken = System.getenv("TELEGRAM_BOT_TOKEN")
    if (!telegramToken.isNullOrBlank()) {
        val allowedChannels = System.getenv("TELEGRAM_ALLOWED_CHANNELS")
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
    log.info("Savebucks worker running. Jobs: ExpiryJob (30m), DealIngestionJob (60m)")
}
