package com.savebucks.worker

import com.savebucks.worker.jobs.DealIngestionJob
import com.savebucks.worker.jobs.ExpiryJob
import com.savebucks.worker.jobs.ingestion.*
import com.savebucks.worker.lib.*
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
 *  - ExpiryJob          — marks approved + pending deals/coupons expired; deletes very old records
 *  - DealIngestionJob   — scrapes enabled RSS sources (see SourceRegistry) for deals and coupons
 *  - TelegramBot        — optional Telegram channel monitor (enable via TELEGRAM_BOT_TOKEN)
 */
fun main() { runBlocking {
    loadDotEnv()
    log.info("Starting Savebucks worker...")

    val supabaseUrl = env("SUPABASE_URL") ?: error("SUPABASE_URL required — set it in worker/.env or server/.env")
    val serviceRoleKey = env("SUPABASE_SERVICE_ROLE") ?: error("SUPABASE_SERVICE_ROLE required — set it in worker/.env or server/.env")
    val minTitleLen = env("TELEGRAM_MIN_TITLE_LEN")?.toIntOrNull() ?: IngestionConfig.Telegram.MIN_TITLE_LENGTH

    // Shared infrastructure — one instance of each, reused across all jobs
    val supabase = SupabaseWorkerClient(supabaseUrl, serviceRoleKey)
    val scraper = WebScraper()
    val circuitBreaker = CircuitBreaker()
    val rateLimiter = RateLimiter()

    // Ingestion pipeline components
    val deduper = Deduper(supabase)
    val companyMatcher = CompanyMatcher(supabase)
    val imageExtractor = ImageExtractor(scraper)
    val urlResolver = UrlResolver(scraper)
    val dealProcessor = DealProcessor(supabase, deduper, companyMatcher, imageExtractor, urlResolver)
    val couponProcessor = CouponProcessor(supabase, companyMatcher)
    val ingestionJob = DealIngestionJob(supabase, dealProcessor, couponProcessor, circuitBreaker, rateLimiter, scraper)

    val scheduler = Scheduler()

    // Expiry cleanup runs every 30 minutes
    scheduler.scheduleRepeating("ExpiryJob", intervalMinutes = 30) {
        ExpiryJob(supabase).run()
    }

    // Register one ingestion job per enabled source with its own interval
    val enabledSources = SourceRegistry.getEnabled()
    if (enabledSources.isEmpty()) {
        log.warn("No ingestion sources are enabled — check SourceRegistry")
    }
    for (source in enabledSources) {
        scheduler.scheduleRepeating("Ingest[${source.key}]", intervalMinutes = source.intervalMinutes) {
            ingestionJob.runSource(source)
        }
    }

    // Telegram bot — optional, only starts when token is set
    val telegramToken = env("TELEGRAM_BOT_TOKEN")
    if (!telegramToken.isNullOrBlank()) {
        val allowedChannels = env("TELEGRAM_ALLOWED_CHANNELS")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        TelegramBot(supabase, telegramToken, allowedChannels, minTitleLen).start(this)
        log.info("Telegram bot started (channels: ${allowedChannels.ifEmpty { listOf("all") }.joinToString()})")
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutdown signal received — stopping worker...")
        scheduler.stop()
        scraper.close()
        supabase.close()
        log.info("Worker stopped cleanly.")
    })

    scheduler.start()

    val sourcesSummary = enabledSources.joinToString { "${it.key}(${it.intervalMinutes}m)" }
    log.info("Savebucks worker running. Sources: $sourcesSummary | ExpiryJob(30m)")

    awaitCancellation()
} }

private fun env(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: System.getProperty(key)?.takeIf { it.isNotBlank() }

private fun loadDotEnv() {
    val candidates = listOf(File(".env"), File("worker/.env"), File("server/.env"))
    val envFile = candidates.firstOrNull { it.exists() } ?: run {
        log.warn("No .env file found (checked: ${candidates.map { it.path }}). Relying on OS environment.")
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
    log.info("Loaded $loaded variable(s) from ${envFile.path}")
}
