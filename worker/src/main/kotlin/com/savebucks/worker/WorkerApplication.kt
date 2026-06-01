package com.savebucks.worker

import com.savebucks.worker.jobs.DealIngestionJob
import com.savebucks.worker.jobs.ingestion.*
import com.savebucks.worker.lib.*
import com.savebucks.worker.lib.redis.QueueManager
import com.savebucks.worker.lib.redis.RedisManager
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("WorkerApplication")

/**
 * Entry point for the background worker process.
 *
 * Architecture mirrors the JS index.js exactly:
 *   - Three Redis-backed queues: ingestion, cleanup, enrichment
 *   - Repeating jobs stored in Redis — survive restarts
 *   - Workers: ingestion (concurrency=3), cleanup (concurrency=1)
 *   - Graceful shutdown: pause → drain → close
 *   - Telegram bot: optional, long-polls when TELEGRAM_BOT_TOKEN is set
 *
 * Redis is required. Set REDIS_URL in worker/.env (redis:// or rediss:// for TLS).
 */
fun main() { runBlocking {
    loadDotEnv()
    log.info("Starting Savebucks worker...")

    val supabaseUrl    = env("SUPABASE_URL")         ?: error("SUPABASE_URL required")
    val serviceRoleKey = env("SUPABASE_SERVICE_ROLE") ?: error("SUPABASE_SERVICE_ROLE required")
    val redisUrl       = env("REDIS_URL")             ?: "redis://localhost:6379"
    val minTitleLen    = env("TELEGRAM_MIN_TITLE_LEN")?.toIntOrNull() ?: IngestionConfig.Telegram.MIN_TITLE_LENGTH

    // ── Shared infrastructure ──────────────────────────────────────────────
    val supabase = SupabaseWorkerClient(supabaseUrl, serviceRoleKey)
    val redis    = RedisManager(redisUrl)
    val scraper  = WebScraper()

    // ── Ingestion pipeline components ─────────────────────────────────────
    val circuitBreaker  = CircuitBreaker()
    val rateLimiter     = RateLimiter()
    val deduper         = Deduper(supabase)
    val companyMatcher  = CompanyMatcher(supabase)
    val imageExtractor  = ImageExtractor(scraper)
    val urlResolver     = UrlResolver(scraper)
    val dealProcessor   = DealProcessor(supabase, deduper, companyMatcher, imageExtractor, urlResolver)
    val couponProcessor = CouponProcessor(supabase, companyMatcher)
    val ingestionJob    = DealIngestionJob(supabase, dealProcessor, couponProcessor, circuitBreaker, rateLimiter, scraper)

    // ── Redis-backed queue manager (mirrors JS queue.js + index.js) ───────
    val queueManager = QueueManager(redis, supabase, ingestionJob)
    queueManager.start()

    // ── Telegram bot — optional ───────────────────────────────────────────
    val telegramToken = env("TELEGRAM_BOT_TOKEN")
    if (!telegramToken.isNullOrBlank()) {
        val allowedChannels = env("TELEGRAM_ALLOWED_CHANNELS")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        TelegramBot(supabase, telegramToken, allowedChannels, minTitleLen).start(this)
        log.info("Telegram bot started (channels: ${allowedChannels.ifEmpty { listOf("all") }.joinToString()})")
    }

    // ── OS signal handlers — graceful shutdown ────────────────────────────
    val mainJob = coroutineContext[kotlinx.coroutines.Job]
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutdown signal received — draining jobs...")
        runBlocking {
            queueManager.shutdown()  // pauseQueues → drain 30s → close redis
            scraper.close()
            supabase.close()
        }
        log.info("Worker stopped cleanly.")
        mainJob?.cancel()
    })

    val enabledSources = SourceRegistry.getEnabled()
    log.info(
        "Savebucks worker running. " +
        "Sources: ${enabledSources.joinToString { "${it.key}(${it.intervalMinutes}m)" }} | " +
        "ExpiryJob(30m) | Redis: $redisUrl"
    )

    awaitCancellation()
} }

// ── Helpers ───────────────────────────────────────────────────────────────────

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
