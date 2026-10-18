package com.fantasyanalyzer.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class EspnException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thin client for ESPN's fantasy football v3 API. Blocking: call it off the
 * main thread.
 *
 * Private leagues need two cookies from a logged-in browser session,
 * `espn_s2` and `SWID`.
 */
class EspnClient(
    private val leagueId: Long,
    private val year: Int,
    espnS2: String? = null,
    swid: String? = null,
) {
    private val cookie: String? = if (!espnS2.isNullOrBlank() && !swid.isNullOrBlank()) {
        val s = swid.trim().let { if (it.startsWith("{")) it else "{$it}" }
        "espn_s2=${espnS2.trim()}; SWID=$s"
    } else null

    /** Settings, teams, members, and the full season schedule with scores. */
    fun league(): JSONObject = get(listOf("view" to "mSettings", "view" to "mTeam", "view" to "mMatchup", "view" to "mStatus"))

    /** Every team's full roster (starters and bench) with points for one week. */
    fun boxScores(matchupPeriod: Int, scoringPeriod: Int): JSONObject = get(
        listOf("view" to "mMatchupScore", "view" to "mScoreboard", "scoringPeriodId" to scoringPeriod.toString()),
        filter = """{"schedule":{"filterMatchupPeriodIds":{"value":[$matchupPeriod]}}}""",
    )

    /** Downloads the league plus box scores for every completed single-week matchup period. */
    fun loadAll(onProgress: (String) -> Unit = {}): LeagueData {
        onProgress("Loading league…")
        val league = EspnParser.parseLeague(league())
        val box = mutableMapOf<Int, Map<Int, List<RosterEntry>>>()
        val weeks = league.completedWeeks(includePlayoffs = true)
        for ((i, week) in weeks.withIndex()) {
            val periods = league.matchupPeriods[week] ?: listOf(week)
            if (periods.size != 1) continue // multi-week playoff matchups: skip lineup analysis
            onProgress("Loading lineups: week $week (${i + 1}/${weeks.size})")
            box[week] = EspnParser.parseBoxScores(boxScores(week, periods[0]), periods[0])
        }
        return LeagueData(league, box)
    }

    private fun get(params: List<Pair<String, String>>, filter: String? = null): JSONObject {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val url = URL("$BASE_URL/seasons/$year/segments/0/leagues/$leagueId?$query")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            cookie?.let { conn.setRequestProperty("Cookie", it) }
            filter?.let { conn.setRequestProperty("x-fantasy-filter", it) }

            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                throw EspnException("Couldn't reach ESPN. Check your internet connection.", e)
            }
            when (code) {
                401, 403 -> throw EspnException(
                    "ESPN refused access. For a private league, check that espn_s2 and SWID are " +
                        "current (they change when you log out of ESPN)."
                )
                404 -> throw EspnException("League $leagueId not found for the $year season.")
                in 300..399 -> throw EspnException("ESPN redirected to a login page; your cookies are probably expired.")
                !in 200..299 -> throw EspnException("ESPN returned HTTP $code.")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return when (val parsed = try { JSONTokener(body).nextValue() } catch (e: Exception) { null }) {
                is JSONObject -> parsed
                is JSONArray -> parsed.getJSONObject(0)
                else -> throw EspnException("ESPN returned a non-JSON response; your cookies are probably expired.")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl"
    }
}
