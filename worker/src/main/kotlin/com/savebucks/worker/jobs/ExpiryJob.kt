package com.savebucks.worker.jobs

import com.savebucks.worker.lib.SupabaseWorkerClient
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ExpiryJob::class.java)

/**
 * Periodic job that marks deals and coupons as expired when their
 * [expires_at] timestamp has passed.
 *
 * Runs every 30 minutes (configured in WorkerApplication).
 * Idempotent — safe to re-run if the scheduler restarts.
 */
class ExpiryJob(private val supabase: SupabaseWorkerClient) {

    /**
     * Finds all approved deals/coupons past their expiry date and
     * sets their status to "expired".
     *
     * PostgREST filter `expires_at=lt.now()` relies on Supabase's server-side
     * timestamp so timezone differences between worker and DB don't matter.
     */
    suspend fun run() {
        val expiredDeals = supabase.select("deals", mapOf(
            "status" to "eq.approved",
            "expires_at" to "lt.now()",
            "select" to "id,title,expires_at"
        ))

        if (expiredDeals.isEmpty()) {
            log.debug("ExpiryJob: no expired deals found.")
            return
        }

        log.info("ExpiryJob: expiring ${expiredDeals.size} deal(s)...")

        // Update in batch by filtering on expired_at rather than ID to avoid N+1 updates
        supabase.update(
            table = "deals",
            data = buildJsonObject { put("status", "expired") },
            filter = mapOf(
                "status" to "eq.approved",
                "expires_at" to "lt.now()"
            )
        )

        // Repeat for coupons
        val expiredCoupons = supabase.select("coupons", mapOf(
            "status" to "eq.approved",
            "expires_at" to "lt.now()",
            "select" to "id"
        ))

        if (expiredCoupons.isNotEmpty()) {
            log.info("ExpiryJob: expiring ${expiredCoupons.size} coupon(s)...")
            supabase.update(
                table = "coupons",
                data = buildJsonObject { put("status", "expired") },
                filter = mapOf("status" to "eq.approved", "expires_at" to "lt.now()")
            )
        }

        log.info("ExpiryJob: done. Expired ${expiredDeals.size} deal(s) + ${expiredCoupons.size} coupon(s).")
    }
}
