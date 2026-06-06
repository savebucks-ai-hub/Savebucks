package com.savebucks.lib.location

/**
 * Extracts location context from a query string and an optional zipcode hint
 * (supplied by the frontend via browser geolocation).
 *
 * Rule: include location filtering ONLY when the query has explicit local intent
 * or a zipcode is present. Never silently geo-filter a generic query.
 *
 * Frontend integration:
 *   1. Call navigator.geolocation.getCurrentPosition()
 *   2. Reverse-geocode the lat/lon to a zipcode (e.g. via api.bigdatacloud.net/geocoding)
 *   3. Send as the `X-User-Zipcode` request header on every API call
 *   Future: store zipcode in UserProfile.location when the user saves account settings
 */
object LocationDetector {

    data class LocationContext(
        /** 5-digit US zipcode if one was found; null otherwise. */
        val zipcode: String?,
        /** True when the query contains local-intent keywords or an inline zipcode. */
        val hasLocalIntent: Boolean
    )

    private val localIntentPattern = Regex(
        // Proximity keywords
        "near\\s+me|nearby|near\\s*by|close\\s+by|closest|nearest|" +
        "around\\s+me|around\\s+here|close\\s+to\\s+me|near\\s+my\\s+location|" +
        // Distance phrases
        "within\\s+walking|within\\s+\\d+\\s*miles?|" +
        // "In my/this …" phrases
        "in\\s+my\\s+(area|city|town|zip|neighborhood)|in\\s+this\\s+(area|city|town)|" +
        // local / locally as standalone words
        "\\blocal(ly)?\\b|" +
        // In-store / pickup signals
        "in[- ]store|in\\s+store|store\\s+pick.?up|pick.?up|stores?\\s+near|" +
        // Bare zip in query
        "near\\s+\\d{5}",
        RegexOption.IGNORE_CASE
    )

    /** Matches a bare 5-digit US zipcode anywhere in the text. */
    private val zipcodePattern = Regex("""\b(\d{5})\b""")

    /**
     * Resolves the location context for a query.
     *
     * Priority for zipcode:
     *   1. Zipcode embedded in the query ("deals near 94102")
     *   2. [headerZipcode] from X-User-Zipcode (browser geolocation)
     */
    fun detect(query: String, headerZipcode: String?): LocationContext {
        val zipcodeInQuery = zipcodePattern.find(query)?.groupValues?.get(1)
        val hasLocalKeyword = localIntentPattern.containsMatchIn(query)
        val zipcode = zipcodeInQuery ?: headerZipcode?.takeIf { it.matches(Regex("""\d{5}""")) }

        return LocationContext(
            zipcode = zipcode,
            hasLocalIntent = hasLocalKeyword || zipcodeInQuery != null
        )
    }

    /** Returns the zipcode from a request parameter or header, validating it is 5 digits. */
    fun resolveZipcode(paramZipcode: String?, headerZipcode: String?): String? {
        val raw = paramZipcode?.trim() ?: headerZipcode?.trim() ?: return null
        return raw.takeIf { it.matches(Regex("""\d{5}""")) }
    }
}
