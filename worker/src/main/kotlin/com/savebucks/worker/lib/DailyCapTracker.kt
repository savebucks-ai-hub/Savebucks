package com.savebucks.worker.lib

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(DailyCapTracker::class.java)

object DailyCapTracker {

    private class DayCount {
        var date: String = todayStr()
        var count: Int = 0
    }

    private val counts = ConcurrentHashMap<String, DayCount>()

    private fun todayStr(): String {
        val now = java.util.Calendar.getInstance()
        return "${now.get(java.util.Calendar.YEAR)}-${now.get(java.util.Calendar.MONTH) + 1}-${now.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    private fun getOrReset(source: String): DayCount {
        val today = todayStr()
        val entry = counts.getOrPut(source) { DayCount() }
        if (entry.date != today) { entry.date = today; entry.count = 0 }
        return entry
    }

    data class CapStatus(val allowed: Boolean, val current: Int, val cap: Int)

    fun check(source: String): CapStatus {
        val cap = IngestionConfig.DailyCaps.OVERRIDES[source] ?: IngestionConfig.DailyCaps.DEFAULT
        val entry = getOrReset(source)
        return CapStatus(entry.count < cap, entry.count, cap)
    }

    fun increment(source: String, amount: Int = 1) {
        val entry = getOrReset(source)
        entry.count += amount
        val cap = IngestionConfig.DailyCaps.OVERRIDES[source] ?: IngestionConfig.DailyCaps.DEFAULT
        val pct = entry.count * 100 / cap
        when {
            pct >= 100 -> log.warn("[$source] Daily cap REACHED (${entry.count}/$cap)")
            pct >= 80  -> log.warn("[$source] Daily cap at $pct% (${entry.count}/$cap)")
        }
    }

    fun getCounts(): Map<String, Int> = counts.mapValues { it.value.count }
}
