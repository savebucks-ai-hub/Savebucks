package com.savebucks.worker.jobs

import com.savebucks.worker.lib.IngestionConfig
import com.savebucks.worker.lib.SupabaseWorkerClient
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger(ExpiryJob::class.java)

/**
 * Periodic cleanup job — mirrors the JS expiryManager.js behaviour:
 *
 *  1. Marks approved + pending deals/coupons as "expired" when expires_at < now
 *  2. Hard-deletes expired deals older than 60 days
 *  3. Hard-deletes expired coupons older than 90 days
 *
 * Uses PostgREST server-side now() for timestamps so timezone drift doesn't matter.
 * Idempotent — safe to re-run if the scheduler restarts mid-cycle.
 */
class ExpiryJob(private val supabase: SupabaseWorkerClient) {

    suspend fun run() {
        val now = Instant.now().toString()
        val dealsCutoff = Instant.now().minus(IngestionConfig.Expiry.DEALS_RETENTION_DAYS, ChronoUnit.DAYS).toString()
        val couponsCutoff = Instant.now().minus(IngestionConfig.Expiry.COUPONS_RETENTION_DAYS, ChronoUnit.DAYS).toString()

        val expiredDeals = expireDeals(now)
        val deletedDeals = deleteOldDeals(dealsCutoff)
        val expiredCoupons = expireCoupons(now)
        val deletedCoupons = deleteOldCoupons(couponsCutoff)

        log.info(
            "ExpiryJob done — expired: $expiredDeals deals, $expiredCoupons coupons; " +
            "deleted: $deletedDeals deals (>${IngestionConfig.Expiry.DEALS_RETENTION_DAYS}d), " +
            "$deletedCoupons coupons (>${IngestionConfig.Expiry.COUPONS_RETENTION_DAYS}d)"
        )
    }

    private suspend fun expireDeals(now: String): Int {
        // JS expires both "approved" and "pending" deals
        val expired = supabase.select("deals", mapOf(
            "expires_at" to "lt.$now",
            "status" to "in.(approved,pending)",
            "select" to "id"
        ))
        if (expired.isEmpty()) {
            log.debug("ExpiryJob: no expired deals found")
            return 0
        }
        log.info("ExpiryJob: expiring ${expired.size} deal(s)...")
        supabase.update(
            table = "deals",
            data = buildJsonObject { put("status", "expired") },
            filter = mapOf("expires_at" to "lt.$now", "status" to "in.(approved,pending)")
        )
        return expired.size
    }

    private suspend fun deleteOldDeals(cutoff: String): Int {
        val count = supabase.delete("deals", mapOf(
            "status" to "eq.expired",
            "updated_at" to "lt.$cutoff"
        ))
        if (count > 0) log.info("ExpiryJob: deleted $count old expired deal(s)")
        return count
    }

    private suspend fun expireCoupons(now: String): Int {
        val expired = supabase.select("coupons", mapOf(
            "expires_at" to "lt.$now",
            "status" to "in.(approved,pending)",
            "select" to "id"
        ))
        if (expired.isEmpty()) {
            log.debug("ExpiryJob: no expired coupons found")
            return 0
        }
        log.info("ExpiryJob: expiring ${expired.size} coupon(s)...")
        supabase.update(
            table = "coupons",
            data = buildJsonObject { put("status", "expired") },
            filter = mapOf("expires_at" to "lt.$now", "status" to "in.(approved,pending)")
        )
        return expired.size
    }

    private suspend fun deleteOldCoupons(cutoff: String): Int {
        val count = supabase.delete("coupons", mapOf(
            "status" to "eq.expired",
            "updated_at" to "lt.$cutoff"
        ))
        if (count > 0) log.info("ExpiryJob: deleted $count old expired coupon(s)")
        return count
    }
}
