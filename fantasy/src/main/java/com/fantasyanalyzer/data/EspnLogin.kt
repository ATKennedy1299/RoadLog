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
        val cookies = cookieHeaders.filterNotNull().flatMap { it.split(";") }.map { it.trim() }
        fun cookie(name: String) = cookies.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")?.takeIf { it.isNotBlank() }
        return copy(
            espnS2 = cookie("espn_s2") ?: espnS2,
            swid = cookie("SWID") ?: swid,
            leagueId = leagueIdFrom(url) ?: leagueId,
            season = url?.let { SEASON.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: season,
        )
    }

    companion object {
        private val LEAGUE = Regex("""[?&]leagueId=(\d+)""")
        private val SEASON = Regex("""[?&]seasonId=(\d{4})""")

        /** Pulls the league ID out of an ESPN league URL; null if there isn't one. */
        fun leagueIdFrom(url: String?): String? = url?.let { LEAGUE.find(it)?.groupValues?.get(1) }
    }
}
