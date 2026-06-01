package com.savebucks.worker.jobs.ingestion

import com.savebucks.worker.lib.SupabaseWorkerClient
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(CompanyMatcher::class.java)

/**
 * Matches incoming merchant names to existing company records using a three-stage strategy:
 *   1. Exact name match (case-insensitive)
 *   2. Slug match
 *   3. Jaccard word-set similarity (>80%) against up to 500 existing companies
 *
 * Creates a new pending company if no match is found. Mirrors companyMatcher.js.
 */
class CompanyMatcher(private val supabase: SupabaseWorkerClient) {

    suspend fun match(merchantName: String): JsonObject? {
        if (merchantName.isBlank()) return null
        val clean = merchantName.trim()

        // 1. Exact (case-insensitive)
        supabase.select("companies", mapOf("name" to "ilike.$clean", "select" to "id,name,slug", "limit" to "1"))
            .firstOrNull()?.jsonObject?.let { return it }

        // 2. Slug
        val slug = toSlug(clean)
        supabase.select("companies", mapOf("slug" to "eq.$slug", "select" to "id,name,slug", "limit" to "1"))
            .firstOrNull()?.jsonObject?.let { return it }

        // 3. Fuzzy — Jaccard similarity on lowercase word sets
        val all = supabase.select("companies", mapOf("select" to "id,name,slug", "limit" to "500"))
        val names = all.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        val best = bestMatch(clean.lowercase(), names.map { it.lowercase() })
        if (best != null && best.second >= 0.8) {
            return all[best.first].jsonObject
        }

        // 4. Create new company (pending admin review)
        return runCatching {
            supabase.insert("companies", buildJsonObject {
                put("name", clean)
                put("slug", slug)
                put("status", "pending")
                put("is_verified", false)
            }).also { log.info("Created new company: $clean") }
        }.onFailure { log.warn("Could not create company '$clean': ${it.message}") }.getOrNull()
    }

    private fun toSlug(name: String) =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun bestMatch(query: String, candidates: List<String>): Pair<Int, Double>? {
        if (candidates.isEmpty()) return null
        val qWords = query.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        var bestIdx = -1; var bestScore = 0.0
        candidates.forEachIndexed { idx, candidate ->
            val cWords = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
            val union = (qWords union cWords).size
            val score = if (union == 0) 0.0 else (qWords intersect cWords).size.toDouble() / union
            if (score > bestScore) { bestScore = score; bestIdx = idx }
        }
        return if (bestIdx >= 0) bestIdx to bestScore else null
    }
}
