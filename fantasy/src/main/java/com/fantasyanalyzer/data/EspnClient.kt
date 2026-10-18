package com.fantasyanalyzer.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class EspnException(message: String, val authProblem: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/** A fantasy football league the signed-in account belongs to. */
data class LeagueRef(val leagueId: Long, val season: Int, val leagueName: String, val teamName: String)

/**
 * Thin client for ESPN's fantasy football v3 API. Blocking: call it off the
 * main thread.
 *
 * Private leagues need the cookies of a signed-in ESPN session. [cookieHeaders]
 * are whole `Cookie` header values to try in order (e.g. the in-app browser's
 * session first, then cookies the user typed in); the first one ESPN accepts
 * is used for the rest of the requests.
 */
class EspnClient(
    private val leagueId: Long,
    private val year: Int,
    cookieHeaders: List<String> = emptyList(),
    private val userAgent: String? = null,
    private val baseUrl: String = BASE_URL,
) {
    private val candidates: List<String?> = cookieHeaders.distinct().ifEmpty { listOf(null) }
    private var working: String? = null

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
        val url = "$baseUrl/seasons/$year/segments/0/leagues/$leagueId?$query"
        val headers = filter?.let { mapOf("x-fantasy-filter" to it) } ?: emptyMap()

        working?.let { return request(url, it, headers, userAgent).json() }
        var lastAuthFailure: Response? = null
        for (cookie in candidates) {
            val resp = request(url, cookie, headers, userAgent)
            if (resp.code == 401 || resp.code == 403) {
                lastAuthFailure = resp
                continue
            }
            working = cookie
            return resp.json()
        }
        throw authError(lastAuthFailure!!)
    }

    private fun Response.json(): JSONObject {
        when (code) {
            in 200..299 -> {}
            401, 403 -> throw authError(this)
            404 -> throw EspnException("ESPN couldn't find league $leagueId for the $year season. Check the league ID and season.")
            in 300..399 -> throw EspnException("ESPN redirected to a login page, so the sign-in has probably expired. Sign in again.", authProblem = true)
            else -> throw EspnException("ESPN returned HTTP $code${espnMessage(body)?.let { ": $it" } ?: "."}")
        }
        return parseObject(body)
            ?: throw EspnException("ESPN returned a page instead of league data; the sign-in has probably expired. Sign in again.", authProblem = true)
    }

    private fun authError(resp: Response): EspnException {
        val espnSays = espnMessage(resp.body)
        val msg = when {
            espnSays != null ->
                "ESPN says: \"$espnSays\" (league $leagueId, $year season). Make sure you signed in with the " +
                    "ESPN account that's in this league and that the league ID is right."
            resp.body.contains("Access Denied", ignoreCase = true) ->
                "ESPN's servers blocked the request (HTTP ${resp.code}). Try again in a minute or on a different network."
            else ->
                "ESPN refused access to league $leagueId (HTTP ${resp.code}). Sign in again from League setup."
        }
        return EspnException(msg, authProblem = true)
    }

    companion object {
        private const val BASE_URL = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl"

        /** The host the league API lives on, for reading the in-app browser's cookies for it. */
        const val API_ORIGIN = "https://lm-api-reads.fantasy.espn.com"

        /** Builds a `Cookie` header from the two values ESPN needs, or null if either is missing. */
        fun cookieHeader(espnS2: String?, swid: String?): String? {
            if (espnS2.isNullOrBlank() || swid.isNullOrBlank()) return null
            val s = swid.trim().let { if (it.startsWith("{")) it else "{$it}" }
            return "espn_s2=${espnS2.trim()}; SWID=$s"
        }

        /**
         * Lists the account's fantasy football leagues via ESPN's fan profile API.
         * Tries each SWID (account id) the browser has seen with each cookie set.
         */
        fun findLeagues(swids: List<String>, cookieHeaders: List<String>, userAgent: String? = null): List<LeagueRef> {
            var lastError: Exception? = null
            for (swid in swids.distinct()) {
                val id = swid.trim().let { if (it.startsWith("{")) it else "{$it}" }
                val url = "https://fan.api.espn.com/apis/v2/fans/${URLEncoder.encode(id, "UTF-8")}" +
                    "?displayHiddenPrefs=true&source=espncom-fantasy-lm&lang=en&region=us"
                for (cookie in cookieHeaders.distinct().ifEmpty { listOf(null) }) {
                    try {
                        val resp = request(url, cookie, emptyMap(), userAgent)
                        if (resp.code !in 200..299) continue
                        val leagues = parseObject(resp.body)?.let { parseFanLeagues(it) } ?: continue
                        if (leagues.isNotEmpty()) return leagues
                    } catch (e: EspnException) {
                        lastError = e
                    }
                }
            }
            lastError?.let { throw it }
            return emptyList()
        }

        /** Pulls fantasy football leagues out of a fan profile response. */
        fun parseFanLeagues(raw: JSONObject): List<LeagueRef> {
            val out = mutableListOf<LeagueRef>()
            val prefs = raw.optJSONArray("preferences") ?: return out
            for (i in 0 until prefs.length()) {
                val entry = prefs.optJSONObject(i)?.optJSONObject("metaData")?.optJSONObject("entry") ?: continue
                val isFootball = entry.optString("abbrev").equals("FFL", ignoreCase = true) || entry.optInt("gameId") == 1
                if (!isFootball) continue
                val season = entry.optInt("seasonId", 0)
                val team = listOf(entry.optString("entryLocation"), entry.optString("entryNickname"))
                    .filter { it.isNotBlank() && it != "null" }.joinToString(" ")
                    .ifBlank { entry.optString("entryName").takeIf { it != "null" } ?: "" }
                val groups = entry.optJSONArray("groups") ?: continue
                for (g in 0 until groups.length()) {
                    val group = groups.optJSONObject(g) ?: continue
                    val id = group.optLong("groupId", 0)
                    if (id <= 0) continue
                    val name = group.optString("groupName").takeIf { it.isNotBlank() && it != "null" } ?: "League $id"
                    out += LeagueRef(id, season, name, team)
                }
            }
            return out.distinctBy { it.leagueId to it.season }.sortedWith(compareByDescending<LeagueRef> { it.season }.thenBy { it.leagueName })
        }

        private class Response(val code: Int, val body: String)

        private fun request(url: String, cookie: String?, headers: Map<String, String>, userAgent: String?): Response {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                cookie?.let { conn.setRequestProperty("Cookie", it) }
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                return Response(code, body)
            } catch (e: IOException) {
                throw EspnException("Couldn't reach ESPN. Check your internet connection.", cause = e)
            } finally {
                conn.disconnect()
            }
        }

        private fun parseObject(body: String): JSONObject? =
            when (val parsed = try { JSONTokener(body).nextValue() } catch (e: Exception) { null }) {
                is JSONObject -> parsed
                is JSONArray -> parsed.optJSONObject(0)
                else -> null
            }

        /** ESPN's own explanation from an error response, if it sent one. */
        internal fun espnMessage(body: String): String? {
            val obj = parseObject(body) ?: return null
            obj.optJSONArray("messages")?.optString(0)?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            return obj.optJSONArray("details")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
        }
    }
}
