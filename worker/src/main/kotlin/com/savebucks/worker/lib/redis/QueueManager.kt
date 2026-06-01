package com.savebucks.worker.lib.redis

import com.savebucks.worker.jobs.DealIngestionJob
import com.savebucks.worker.jobs.ExpiryJob
import com.savebucks.worker.jobs.ingestion.SourceRegistry
import com.savebucks.worker.lib.IngestionConfig
import com.savebucks.worker.lib.SupabaseWorkerClient
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(QueueManager::class.java)

/**
 * Orchestrates the three BullMQ-equivalent queues — mirrors queue.js / index.js.
 *
 * Queues and retry policy (matches JS defaultJobOptions per queue):
 *   ingestion  — 3 retry attempts, exponential backoff starting at 2s (concurrency = 3)
 *   cleanup    — 2 retry attempts                                      (concurrency = 1)
 *   enrichment — 2 retry attempts, reserved for future enrichment jobs (concurrency = 1)
 *
 * Repeating jobs are persisted in Redis — schedule survives restarts.
 */
class QueueManager(
    private val redis: RedisManager,
    private val supabase: SupabaseWorkerClient,
    private val ingestionJob: DealIngestionJob
) {
    // ── Queues ────────────────────────────────────────────────────────────────

    val ingestion  = JobQueue("ingestion",  redis)
    val cleanup    = JobQueue("cleanup",    redis)
    val enrichment = JobQueue("enrichment", redis)

    // ── Workers ───────────────────────────────────────────────────────────────

    /** ingestion: concurrency=3, maxAttempts=3, exponential backoff starting at 2s */
    private val ingestionWorker = JobWorker(
        queue       = ingestion,
        concurrency = IngestionConfig.RateLimit.MAX_CONCURRENT_JOBS.coerceAtMost(3)
    ) { job ->
        val sourceKey = job.data["sourceKey"]
            ?: error("Job '${job.name}' missing sourceKey in data")
        val source = SourceRegistry.getByKey(sourceKey)
            ?: error("Unknown sourceKey: $sourceKey")
        ingestionJob.runSource(source)
        "ok"
    }.also {
        it.onCompleted = { job, _ -> log.info("[ingestion] ✅ ${job.name} completed (attempt ${job.attempt})") }
        it.onFailed    = { job, reason -> log.error("[ingestion] ❌ ${job.name} failed: $reason") }
        it.onStalled   = { jobId -> log.warn("[ingestion] ⚠️  Job $jobId stalled — requeued") }
    }

    /** cleanup: concurrency=1, maxAttempts=2 */
    private val cleanupWorker = JobWorker(
        queue       = cleanup,
        concurrency = 1
    ) { _ ->
        ExpiryJob(supabase).run()
        "ok"
    }.also {
        it.onCompleted = { job, _ -> log.info("[cleanup] ✅ ${job.name} completed") }
        it.onFailed    = { job, reason -> log.error("[cleanup] ❌ ${job.name} failed: $reason") }
        it.onStalled   = { jobId -> log.warn("[cleanup] ⚠️  Job $jobId stalled — requeued") }
    }

    /** enrichment: concurrency=1, maxAttempts=2, reserved for future use */
    private val enrichmentWorker = JobWorker(
        queue       = enrichment,
        concurrency = 1
    ) { job ->
        log.debug("[enrichment] No processor registered for job '${job.name}' — skipping")
        "noop"
    }.also {
        it.onCompleted = { job, _ -> log.info("[enrichment] ✅ ${job.name} completed") }
        it.onFailed    = { job, reason -> log.error("[enrichment] ❌ ${job.name} failed: $reason") }
        it.onStalled   = { jobId -> log.warn("[enrichment] ⚠️  Job $jobId stalled — requeued") }
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    suspend fun start() {
        registerRepeatJobs()
        ingestionWorker.start()
        cleanupWorker.start()
        enrichmentWorker.start()
        log.info("QueueManager started — ingestion(c=3,retries=3) | cleanup(c=1,retries=2) | enrichment(c=1,retries=2)")
    }

    private suspend fun registerRepeatJobs() {
        // ingestion: one repeating job per enabled source, 3 retry attempts
        val enabled = SourceRegistry.getEnabled()
        for (source in enabled) {
            ingestion.addRepeating(
                name       = source.key,
                data       = mapOf("sourceKey" to source.key),
                intervalMs = source.intervalMinutes * 60_000L,
                opts       = JobOptions(maxAttempts = 3, jobId = "scheduled-${source.key}")
            )
        }

        // cleanup: 2 retry attempts (mirrors JS cleanup queue's attempts: 2)
        cleanup.addRepeating(
            name       = "check-expired",
            intervalMs = 30 * 60_000L,
            opts       = JobOptions(maxAttempts = 2, jobId = "expiry-checker")
        )

        log.info("Registered ${enabled.size} ingestion source(s) + expiry cleanup as repeating jobs")
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    /** Returns waiting/active/completed/failed/delayed counts for all three queues. */
    suspend fun getQueueStats(): Map<String, QueueStats> = mapOf(
        "ingestion"  to ingestion.getStats(),
        "cleanup"    to cleanup.getStats(),
        "enrichment" to enrichment.getStats()
    )

    // ── Control ───────────────────────────────────────────────────────────────

    fun pauseQueues() {
        ingestion.pause(); cleanup.pause(); enrichment.pause()
        log.info("All queues paused")
    }

    fun resumeQueues() {
        ingestion.resume(); cleanup.resume(); enrichment.resume()
        log.info("All queues resumed")
    }

    // ── Graceful shutdown ─────────────────────────────────────────────────────

    /**
     * Pause → wait for active jobs (30s max per worker) → close Redis.
     * Matches JS gracefulShutdown() behaviour exactly.
     */
    suspend fun shutdown() {
        log.info("Graceful shutdown initiated")
        pauseQueues()

        ingestionWorker.shutdown(drainTimeoutMs = 30_000L)
        cleanupWorker.shutdown(drainTimeoutMs = 10_000L)
        enrichmentWorker.shutdown(drainTimeoutMs = 10_000L)

        redis.close()
        log.info("Graceful shutdown complete")
    }

    // ── Clean old jobs ────────────────────────────────────────────────────────

    suspend fun cleanOldJobs(maxAgeMs: Long = 86_400_000L) {
        ingestion.clean(maxAgeMs)
        cleanup.clean(maxAgeMs)
        enrichment.clean(maxAgeMs)
    }
}
