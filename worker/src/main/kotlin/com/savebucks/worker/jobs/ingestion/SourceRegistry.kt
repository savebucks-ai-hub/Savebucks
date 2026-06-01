package com.savebucks.worker.jobs.ingestion

enum class SourceType { RSS_DEALS, RSS_COUPONS }

data class IngestionSource(
    val key: String,
    val name: String,
    val url: String,
    val type: SourceType,
    val enabled: Boolean,
    val intervalMinutes: Long,
    val priority: Int = 5,
    val disabledReason: String? = null
)

object SourceRegistry {

    val ALL: List<IngestionSource> = listOf(
        IngestionSource(
            key = "slickdeals_rss",
            name = "Slickdeals Frontpage RSS",
            url = "https://slickdeals.net/newsearch.php?mode=frontpage&searcharea=deals&searchin=first&rss=1",
            type = SourceType.RSS_DEALS,
            enabled = true,
            intervalMinutes = 25,
            priority = 1
        ),
        IngestionSource(
            key = "slickdeals_coupons",
            name = "Slickdeals Coupons RSS",
            url = "https://slickdeals.net/newsearch.php?mode=frontpage&searcharea=coupons&searchin=first&rss=1",
            type = SourceType.RSS_COUPONS,
            enabled = true,
            intervalMinutes = 30,
            priority = 2
        ),
        IngestionSource(
            key = "techbargains",
            name = "TechBargains RSS",
            url = "https://feeds.feedburner.com/techbargains",
            type = SourceType.RSS_DEALS,
            enabled = false,
            intervalMinutes = 30,
            priority = 3,
            disabledReason = "Returns 403 Forbidden — feed is permanently blocked"
        )
    )

    fun getEnabled(): List<IngestionSource> = ALL.filter { it.enabled }.sortedBy { it.priority }

    fun getByKey(key: String): IngestionSource? = ALL.firstOrNull { it.key == key }
}
