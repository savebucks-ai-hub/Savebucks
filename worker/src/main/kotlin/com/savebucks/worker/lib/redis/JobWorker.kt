package com.savebucks.worker.lib.redis

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JobWorker::class.java)

/**
 * Redis-backed job worker — mirrors BullMQ's Worker class.
 *
 * Spawns [concurrency] coroutine processors that each poll the queue and
 * process jobs. A separate repeat-checker loop fires repeating jobs when due.
 * A stall-recovery loop requeues jobs from crashed workers.
 *
 * Graceful shutdown: pauses the queue, waits up to [drainTimeoutMs] for active
 * jobs to finish, then cancels remaining coroutines.
 */
class JobWorker(
    val queue: JobQueue,
    private val concurrency: Int = 1,
    private val processor: suspend (Job) -> String  // returns result string
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processorJobs = mutableListOf<kotlinx.coroutines.Job>()

    // ── Event callbacks (mirror BullMQ's QueueEvents) ─────────────────────

    var onCompleted: ((Job, String) -> Unit)? = null
    var onFailed:    ((Job, String) -> Unit)? = null
    var onStalled:   ((String) -> Unit)? = null

    // ── Start ──────────────────────────────────────────────────────────────

    fun start() {
        // N processor coroutines — each continuously polls for the next job
        repeat(concurrency) { i ->
            processorJobs += scope.launch {
                log.debug("[${queue.name}] Worker coroutine $i started")
                while (isActive) {
                    runCatching { processNext() }
                        .onFailure { log.error("[${queue.name}] Worker $i error: ${it.message}", it) }
                }
            }
        }

        // Repeat-checker: fires repeating jobs when their nextRunAt has passed
        processorJobs += scope.launch {
            while (isActive) {
                runCatching { checkRepeats() }
                    .onFailure { log.warn("[${queue.name}] Repeat check error: ${it.message}") }
                delay(10_000) // check every 10 seconds
            }
        }

        // Stall-recovery: requeues jobs whose worker died, fires onStalled per job
        processorJobs += scope.launch {
            while (isActive) {
                delay(60_000) // check every minute
                runCatching { queue.recoverStalled(onStalled = onStalled) }
                    .onFailure { log.warn("[${queue.name}] Stall recovery error: ${it.message}") }
            }
        }

        log.info("[${queue.name}] Worker started (concurrency=$concurrency)")
    }

    // ── Core: pop → process → complete/fail ───────────────────────────────

    private suspend fun processNext() {
        val job = queue.pop()
        if (job == null) {
            delay(1_000) // nothing in queue — back off 1 second
            return
        }

        log.debug("[${queue.name}] Processing job ${job.id} '${job.name}' (attempt ${job.attempt}/${job.maxAttempts})")

        val startMs = System.currentTimeMillis()
        try {
            val result = processor(job)
            queue.complete(job.id, result)
            val ms = System.currentTimeMillis() - startMs
            log.info("[${queue.name}] Job ${job.id} '${job.name}' completed in ${ms}ms")
            onCompleted?.invoke(job, result)
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName ?: "Unknown error"
            val ms = System.currentTimeMillis() - startMs
            log.error("[${queue.name}] Job ${job.id} '${job.name}' failed in ${ms}ms (attempt ${job.attempt}/${job.maxAttempts}): $reason")
            queue.fail(job.id, reason, job.attempt, job.maxAttempts)
            onFailed?.invoke(job, reason)
        }
    }

    // ── Repeat scheduler ──────────────────────────────────────────────────

    private suspend fun checkRepeats() {
        val now = System.currentTimeMillis()
        val configs = queue.loadRepeatConfigs()
        for ((key, config) in configs) {
            if (config.nextRunAt > now) continue
            log.debug("[${queue.name}] Firing repeat '${config.name}'")
            val jobId = queue.add(
                name = config.name,
                data = config.data,
                opts = JobOptions(maxAttempts = config.maxAttempts)
            )
            val nextRunAt = now + config.intervalMs
            queue.updateRepeatNextRun(key, nextRunAt, jobId)
        }
    }

    // ── Graceful shutdown ─────────────────────────────────────────────────

    suspend fun shutdown(drainTimeoutMs: Long = 30_000L) {
        log.info("[${queue.name}] Shutdown initiated — pausing queue")
        queue.pause()

        val deadline = System.currentTimeMillis() + drainTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val stats = queue.getStats()
            if (stats.active == 0L) break
            log.info("[${queue.name}] Waiting for ${stats.active} active job(s)…")
            delay(1_000)
        }

        processorJobs.forEach { it.cancel() }
        scope.cancel()
        log.info("[${queue.name}] Worker stopped")
    }
}
