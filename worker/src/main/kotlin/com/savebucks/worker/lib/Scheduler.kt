package com.savebucks.worker.lib

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(Scheduler::class.java)

/**
 * Lightweight coroutine-based job scheduler.
 *
 * Replaces BullMQ from the original Node.js worker. Uses kotlinx.coroutines
 * periodic launch rather than a Redis-backed queue because our jobs are
 * idempotent and don't need distributed coordination.
 *
 * For high-throughput or distributed scheduling, consider migrating to
 * Quartz Scheduler or a Redis-backed queue in the future.
 */
class Scheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Schedules a repeating job with a fixed interval.
     *
     * @param name            Unique job name (used in logs).
     * @param intervalMinutes How often to run the job, in minutes.
     * @param block           The suspend function to execute each cycle.
     */
    fun scheduleRepeating(name: String, intervalMinutes: Long, block: suspend () -> Unit) {
        val job = scope.launch {
            // Run immediately on startup, then repeat on interval
            while (isActive) {
                log.info("[$name] Starting run...")
                runCatching { block() }
                    .onSuccess { log.info("[$name] Completed successfully.") }
                    .onFailure { log.error("[$name] Failed: ${it.message}", it) }

                delay(intervalMinutes * 60_000)
            }
        }
        jobs[name] = job
        log.info("Scheduled job '$name' every ${intervalMinutes}m")
    }

    /** Starts the scheduler (currently a no-op — jobs launch when registered). */
    fun start() = log.info("Scheduler running with ${jobs.size} job(s).")

    /** Cancels all running jobs and cleans up the coroutine scope. */
    fun stop() {
        jobs.values.forEach { it.cancel() }
        scope.cancel()
    }
}
