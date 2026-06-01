package com.savebucks.worker.lib.redis

import io.lettuce.core.LMoveArgs
import io.lettuce.core.Range
import kotlinx.coroutines.future.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JobQueue::class.java)

// ── Data models ──────────────────────────────────────────────────────────────

data class Job(
    val id: String,
    val name: String,
    val queue: String,
    val data: Map<String, String>,
    val attempt: Int,
    val maxAttempts: Int
)

data class JobOptions(
    val maxAttempts: Int = 3,
    val delayMs: Long? = null,
    val jobId: String? = null   // deduplication key (prevents duplicate scheduled jobs)
)

data class RepeatConfig(
    val name: String,
    val data: Map<String, String>,
    val intervalMs: Long,
    val nextRunAt: Long,
    val maxAttempts: Int = 3
)

data class QueueStats(
    val waiting: Long,
    val active: Long,
    val completed: Long,
    val failed: Long,
    val delayed: Long
)

// ── Queue ─────────────────────────────────────────────────────────────────────

/**
 * Redis-backed job queue — mirrors BullMQ's Queue class.
 *
 * Key patterns (BullMQ-compatible naming):
 *   bull:{name}:wait       List   — FIFO waiting jobs
 *   bull:{name}:active     List   — jobs currently being processed
 *   bull:{name}:delayed    ZSet   — jobs waiting for a scheduled time (score = runAt ms)
 *   bull:{name}:completed  ZSet   — finished jobs (score = finishedAt ms)
 *   bull:{name}:failed     ZSet   — failed jobs (score = finishedAt ms)
 *   bull:{name}:repeat     Hash   — repeat configurations (key → JSON)
 *   bull:{name}:id         String — auto-increment job ID counter
 *   bull:{name}:{id}       Hash   — individual job data fields
 */
class JobQueue(val name: String, private val redis: RedisManager) {

    private val px = "bull:$name"
    val waitKey      = "$px:wait"
    val activeKey    = "$px:active"
    val delayedKey   = "$px:delayed"
    val completedKey = "$px:completed"
    val failedKey    = "$px:failed"
    val repeatKey    = "$px:repeat"
    val idKey        = "$px:id"

    @Volatile private var paused = false
    private val cmd = redis.cmd

    // ── Adding jobs ───────────────────────────────────────────────────────────

    /** Add a one-shot job. Returns the generated job ID. */
    suspend fun add(name: String, data: Map<String, String> = emptyMap(), opts: JobOptions = JobOptions()): String {
        val id = opts.jobId ?: nextId()

        // Deduplication: skip if a non-terminal job with this ID already exists
        if (opts.jobId != null) {
            val state = cmd.hget("$px:$id", "state").await()
            if (state != null && state !in listOf("completed", "failed")) {
                log.debug("[$name] Duplicate suppressed (jobId=${opts.jobId}, state=$state)")
                return id
            }
        }

        val now = System.currentTimeMillis()
        val fields = buildMap {
            put("name", name)
            put("data", Json.encodeToString(data))
            put("attempts", "0")
            put("maxAttempts", opts.maxAttempts.toString())
            put("state", "waiting")
            put("createdAt", now.toString())
        }

        cmd.hmset("$px:$id", fields).await()

        val delay = opts.delayMs ?: 0L
        if (delay > 0) {
            cmd.zadd(delayedKey, (now + delay).toDouble(), id).await()
        } else {
            cmd.rpush(waitKey, id).await()
        }

        log.debug("[$name] Job queued: $id")
        return id
    }

    /**
     * Register a repeating job. Stored in Redis so the schedule survives restarts.
     * Idempotent — calling twice with the same name+interval is a no-op.
     */
    suspend fun addRepeating(
        name: String,
        data: Map<String, String> = emptyMap(),
        intervalMs: Long,
        opts: JobOptions = JobOptions()
    ): String {
        val key = "repeat:$name:$intervalMs"
        val existing = cmd.hget(repeatKey, key).await()
        if (existing != null) {
            log.debug("[$name] Repeat already registered: $key")
            return key
        }

        val config = buildJsonObject {
            put("name", name)
            put("data", Json.encodeToString(data))
            put("intervalMs", intervalMs)
            put("nextRunAt", System.currentTimeMillis()) // fire immediately on first run
            put("maxAttempts", opts.maxAttempts)
        }
        cmd.hset(repeatKey, key, config.toString()).await()
        log.info("[$name] Repeating job registered: every ${intervalMs / 60_000}m")
        return key
    }

    // ── Internal: called by JobWorker's repeat-checker loop ─────────────────

    internal suspend fun loadRepeatConfigs(): Map<String, RepeatConfig> {
        val all = cmd.hgetall(repeatKey).await() ?: return emptyMap()
        return all.entries.mapNotNull { (key, json) ->
            runCatching {
                val obj = Json.parseToJsonElement(json).jsonObject
                key to RepeatConfig(
                    name        = obj["name"]!!.jsonPrimitive.content,
                    data        = Json.decodeFromString(obj["data"]!!.jsonPrimitive.content),
                    intervalMs  = obj["intervalMs"]!!.jsonPrimitive.long,
                    nextRunAt   = obj["nextRunAt"]!!.jsonPrimitive.long,
                    maxAttempts = obj["maxAttempts"]?.jsonPrimitive?.intOrNull ?: 3
                )
            }.getOrNull()
        }.toMap()
    }

    internal suspend fun updateRepeatNextRun(key: String, nextRunAt: Long, lastJobId: String) {
        val existing = cmd.hget(repeatKey, key).await() ?: return
        val obj = Json.parseToJsonElement(existing).jsonObject.toMutableMap()
        obj["nextRunAt"] = JsonPrimitive(nextRunAt)
        obj["lastJobId"] = JsonPrimitive(lastJobId)
        cmd.hset(repeatKey, key, JsonObject(obj).toString()).await()
    }

    // ── Internal: called by JobWorker's processing loop ──────────────────────

    /** Promote delayed jobs whose runAt time has passed into the wait list. */
    internal suspend fun promoteDelayed() {
        val due = cmd.zrangebyscore(delayedKey, Range.create(0.0, System.currentTimeMillis().toDouble())).await()
        if (due.isNullOrEmpty()) return
        for (id in due) {
            cmd.zrem(delayedKey, id).await()
            cmd.rpush(waitKey, id).await()
        }
        log.debug("[$name] Promoted ${due.size} delayed job(s) to wait")
    }

    /**
     * Atomically pops the right-most job from wait and pushes to the left of active.
     * Returns null when the queue is empty or paused.
     */
    internal suspend fun pop(): Job? {
        if (paused) return null
        promoteDelayed()

        val jobId = cmd.lmove(waitKey, activeKey, LMoveArgs.Builder.rightLeft()).await() ?: return null

        val fields = cmd.hgetall("$px:$jobId").await() ?: emptyMap()
        if (fields.isEmpty()) {
            // Orphaned ID — remove from active and skip
            cmd.lrem(activeKey, 1, jobId).await()
            return null
        }

        val attempt = (fields["attempts"]?.toIntOrNull() ?: 0) + 1
        cmd.hmset("$px:$jobId", mapOf(
            "state"       to "active",
            "processedAt" to System.currentTimeMillis().toString(),
            "attempts"    to attempt.toString()
        )).await()

        return Job(
            id          = jobId,
            name        = fields["name"] ?: "unknown",
            queue       = name,
            data        = runCatching { Json.decodeFromString<Map<String, String>>(fields["data"] ?: "{}") }.getOrDefault(emptyMap()),
            attempt     = attempt,
            maxAttempts = fields["maxAttempts"]?.toIntOrNull() ?: 3
        )
    }

    internal suspend fun complete(jobId: String, returnValue: String = "") {
        val now = System.currentTimeMillis()
        cmd.hmset("$px:$jobId", mapOf("state" to "completed", "finishedAt" to now.toString(), "returnValue" to returnValue)).await()
        cmd.lrem(activeKey, 1, jobId).await()
        cmd.zadd(completedKey, now.toDouble(), jobId).await()
        // Retain completed jobs: 1 hour OR last 1000 — whichever limit is hit first
        cmd.zremrangebyscore(completedKey, Range.create(Double.NEGATIVE_INFINITY, (now - 3_600_000L).toDouble())).await()
        cmd.zremrangebyrank(completedKey, 0, -1001).await() // keep only the 1000 most recent
    }

    internal suspend fun fail(jobId: String, reason: String, attempt: Int, maxAttempts: Int) {
        val now = System.currentTimeMillis()
        cmd.lrem(activeKey, 1, jobId).await()

        if (attempt < maxAttempts) {
            // Exponential backoff: 2s, 4s, 8s … capped at 30s
            val backoff = minOf(2_000L shl (attempt - 1), 30_000L)
            cmd.hmset("$px:$jobId", mapOf("state" to "delayed", "failedReason" to reason.take(500))).await()
            cmd.zadd(delayedKey, (now + backoff).toDouble(), jobId).await()
            log.debug("[$name] Job $jobId retry ${attempt + 1}/$maxAttempts in ${backoff}ms")
        } else {
            cmd.hmset("$px:$jobId", mapOf("state" to "failed", "finishedAt" to now.toString(), "failedReason" to reason.take(500))).await()
            cmd.zadd(failedKey, now.toDouble(), jobId).await()
            // Keep failed jobs for 24 hours (matches BullMQ default)
            cmd.zremrangebyscore(failedKey, Range.create(Double.NEGATIVE_INFINITY, (now - 86_400_000L).toDouble())).await()
        }
    }

    /**
     * Move stalled active jobs back to waiting.
     * A job is considered stalled if it has been active for longer than [thresholdMs].
     * Fires [onStalled] for each recovered job — mirrors BullMQ's 'stalled' QueueEvent.
     */
    internal suspend fun recoverStalled(
        thresholdMs: Long = 5 * 60_000L,
        onStalled: ((String) -> Unit)? = null
    ) {
        val active = cmd.lrange(activeKey, 0, -1).await() ?: return
        val now = System.currentTimeMillis()
        for (jobId in active) {
            val processedAt = cmd.hget("$px:$jobId", "processedAt").await()?.toLongOrNull() ?: continue
            if (now - processedAt > thresholdMs) {
                log.warn("[$name] Stalled job detected: $jobId (active ${(now - processedAt) / 1000}s) — requeuing")
                cmd.lrem(activeKey, 1, jobId).await()
                cmd.rpush(waitKey, jobId).await()
                cmd.hset("$px:$jobId", "state", "waiting").await()
                onStalled?.invoke(jobId)  // fire QueueEvents-equivalent callback
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun getStats(): QueueStats = QueueStats(
        waiting   = cmd.llen(waitKey).await()      ?: 0L,
        active    = cmd.llen(activeKey).await()     ?: 0L,
        completed = cmd.zcard(completedKey).await() ?: 0L,
        failed    = cmd.zcard(failedKey).await()    ?: 0L,
        delayed   = cmd.zcard(delayedKey).await()   ?: 0L
    )

    fun pause()  { paused = true;  log.info("[$name] Queue paused") }
    fun resume() { paused = false; log.info("[$name] Queue resumed") }

    suspend fun clean(maxAgeMs: Long = 86_400_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val c = cmd.zremrangebyscore(completedKey, Range.create(Double.NEGATIVE_INFINITY, cutoff.toDouble())).await() ?: 0L
        val f = cmd.zremrangebyscore(failedKey,    Range.create(Double.NEGATIVE_INFINITY, (cutoff - 6 * 86_400_000L).toDouble())).await() ?: 0L
        log.info("[$name] Cleaned $c completed, $f failed jobs")
    }

    private suspend fun nextId(): String = (cmd.incr(idKey).await() ?: System.currentTimeMillis()).toString()
}
