package com.fantasyanalyzer.data

/** What the in-app ESPN sign-in has picked up so far. */
data class EspnLogin(
    val espnS2: String? = null,
    val swid: String? = null,
    val leagueId: String? = null,
    val season: Int? = null,
) {
    val signedIn: Boolean get() = espnS2 != null && swid != null

    /**
     * Folds in the cookies ESPN has set and the page the user is on. ESPN sets
     * SWID even for anonymous visitors; espn_s2 only appears after signing in.
     */
    fun update(cookieHeaders: List<String?>, url: String?): EspnLogin {
        return copy(
            espnS2 = cookieValues(cookieHeaders, "espn_s2").firstOrNull() ?: espnS2,
            swid = cookieValues(cookieHeaders, "SWID").firstOrNull() ?: swid,
            leagueId = leagueIdFrom(url) ?: leagueId,
            season = url?.let { SEASON.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: season,
        )
    }

    companion object {
        private val LEAGUE = Regex("""[?&]leagueId=(\d+)""")
        private val SEASON = Regex("""[?&]seasonId=(\d{4})""")

        /** Every non-blank value of cookie [name] across `Cookie` headers, in order, without repeats. */
        fun cookieValues(cookieHeaders: List<String?>, name: String): List<String> =
            cookieHeaders.filterNotNull().flatMap { it.split(";") }.map { it.trim() }
                .filter { it.startsWith("$name=") }.map { it.substringAfter("=") }
                .filter { it.isNotBlank() }.distinct()

        /**
         * Cookie headers to try against the league API, best first: exactly what
         * the in-app browser would send to the API host, then every espn_s2/SWID
         * pairing the browser has seen (ESPN swaps the anonymous SWID for the
         * account's on sign-in, and both can linger), then cookies the user typed in.
         */
        fun cookieCandidates(apiCookieHeader: String?, otherHeaders: List<String?>, stored: String?): List<String> {
            val all = listOf(apiCookieHeader) + otherHeaders
            val out = mutableListOf<String>()
            if (apiCookieHeader != null && cookieValues(listOf(apiCookieHeader), "espn_s2").isNotEmpty()) out += apiCookieHeader
            for (s2 in cookieValues(all, "espn_s2")) {
                for (swid in cookieValues(all, "SWID")) EspnClient.cookieHeader(s2, swid)?.let { out += it }
            }
            stored?.let { out += it }
            return out.distinct().take(MAX_CANDIDATES)
        }

        private const val MAX_CANDIDATES = 8

        /** Pulls the league ID out of an ESPN league URL; null if there isn't one. */
        fun leagueIdFrom(url: String?): String? = url?.let { LEAGUE.find(it)?.groupValues?.get(1) }
    }
}
