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
    /** ESPN takes changes on a separate host from reads. */
    private val writeUrl: String = WRITE_URL,
    /** Saved responses; see [cached]. */
    private val cache: JsonCache? = null,
    /** Read only from [cache], never the network (for an instant start). */
    private val offline: Boolean = false,
    private val scoreboardUrl: String = SCOREBOARD_URL,
) {
    private val candidates: List<String?> = cookieHeaders.distinct().ifEmpty { listOf(null) }
    private var working: String? = null

    /**
     * Gets [key] from the network, saving it to the cache. A cached copy younger
     * than [reuseFor] is used instead of the network (so data that can't change,
     * like a finished week, is only ever downloaded once). Offline, only the cache
     * is used, at any age.
     */
    private fun cached(key: String, reuseFor: Long = 0, net: () -> JSONObject): JSONObject {
        val c = cache
        if (offline) {
            return c?.read(key)?.let { parseObject(it) } ?: throw EspnException("Nothing saved for $key yet.")
        }
        if (c != null && reuseFor > 0) c.read(key, reuseFor)?.let { parseObject(it) }?.let { return it }
        val json = net()
        c?.write(key, json.toString())
        return json
    }

    /** Settings, teams, members, and the full season schedule with scores. */
    fun league(): JSONObject = cached("league") {
        get(listOf("view" to "mSettings", "view" to "mTeam", "view" to "mMatchup", "view" to "mStatus"))
    }

    /**
     * Every team's full roster (starters and bench) with points for one week.
     * [final] weeks are over and can't change, so they're kept for good.
     */
    fun boxScores(matchupPeriod: Int, scoringPeriod: Int, final: Boolean = false): JSONObject =
        // Final weeks get their own key, so a copy saved while the week was still on never passes for final.
        cached(if (final) "final-$matchupPeriod-$scoringPeriod" else "box-$matchupPeriod-$scoringPeriod",
            reuseFor = if (final) Long.MAX_VALUE else 0) {
            get(
                listOf("view" to "mMatchupScore", "view" to "mScoreboard", "scoringPeriodId" to scoringPeriod.toString()),
                filter = """{"schedule":{"filterMatchupPeriodIds":{"value":[$matchupPeriod]}}}""",
            )
        }

    /** How each NFL defense ranks against each position, as of [week]. */
    fun positionalRatings(week: Int): JSONObject = cached("ratings-$week", reuseFor = SIX_HOURS) {
        get(listOf("view" to "mPositionalRatings", "scoringPeriodId" to week.toString()))
    }

    /** ESPN's public NFL scoreboard for an NFL week: scores, quarter and clock. */
    fun nflScoreboard(week: Int): JSONObject = cached("scoreboard-$week") {
        val resp = request("$scoreboardUrl?seasontype=2&week=$week&dates=$year", null, emptyMap(), userAgent)
        if (resp.code in 200..299) parseObject(resp.body) ?: JSONObject() else throw EspnException("Scoreboard: HTTP ${resp.code}")
    }

    /** One player's whole season, week by week (ESPN's player card data). */
    fun playerCard(playerId: Long): JSONObject = cached("card2-$playerId", reuseFor = THIRTY_MINUTES) {
        get(
            listOf("view" to "kona_playercard"),
            filter = """{"players":{"filterIds":{"value":[$playerId]},""" +
                """"filterStatsForTopScoringPeriodIds":{"value":18,"additionalValue":["00$year","10$year"]}}}""",
        )
    }

    /** Rostered players' seasons so far, week by week, in one request. Refreshed every few hours. */
    fun seasonStats(week: Int, playerIds: Collection<Long>): JSONObject = cached("season-$week", reuseFor = SIX_HOURS) {
        get(
            listOf("view" to "kona_playercard"),
            filter = """{"players":{"filterIds":{"value":[${playerIds.sorted().joinToString(",")}]},""" +
                """"filterStatsForTopScoringPeriodIds":{"value":18,"additionalValue":["00$year","10$year"]}}}""",
        )
    }

    /** Free agents and waiver players who can fill [slotId], most-owned first. */
    fun freeAgents(week: Int, slotId: Int, limit: Int = 40): JSONObject = cached("fa3-$week-$slotId") { freeAgentsNet(week, slotId, limit) }

    private fun freeAgentsNet(week: Int, slotId: Int, limit: Int): JSONObject {
        // ESPN stat set ids are source + split + season (+ week): every week's actual
        // ("01") and projection ("11") so far, for the season form, and season totals.
        val faStatIds = (listOf("00$year", "10$year") +
            (1..week).flatMap { listOf("01$year$it", "11$year$it") }).joinToString(",") { "\"$it\"" }
        return get(
            listOf("view" to "kona_player_info", "scoringPeriodId" to week.toString()),
            filter = """{"players":{"filterStatus":{"value":["FREEAGENT","WAIVERS"]},""" +
                """"filterSlotIds":{"value":[$slotId]},"limit":$limit,"offset":0,""" +
                """"sortPercOwned":{"sortPriority":1,"sortAsc":false},""" +
                """"sortDraftRanks":{"sortPriority":100,"sortAsc":true,"value":"STANDARD"},""" +
                """"filterStatsForTopScoringPeriodIds":{"value":3,"additionalValue":[$faStatIds]}}}""",
        )
    }

    /**
     * Sends lineup changes to ESPN, the way ESPN's site does: one transaction
     * posted to the league's write API. Returns ESPN's confirmation, or throws
     * with ESPN's reason. Tries each cookie set until one is accepted.
     */
    fun submitLineup(teamId: Int, scoringPeriod: Int, moves: List<LineupMove>): String {
        require(moves.isNotEmpty())
        return submitTransaction("lineup", "Lineup saved on ESPN.") { swid ->
            LineupEditor.transaction(teamId, swid, scoringPeriod, moves)
        }
    }

    /** Adds a free agent or claims a waiver player, optionally dropping someone. */
    fun submitAddDrop(teamId: Int, scoringPeriod: Int, move: AddDrop): String =
        submitTransaction(if (move.claim) "claim" else "move", if (move.claim) "Waiver claim placed." else "Done on ESPN.") { swid ->
            RosterMoves.transaction(teamId, swid, scoringPeriod, move)
        }

    /**
     * Posts one transaction to the league's write API, as ESPN's site does.
     * [body] gets the member id (SWID) matching the cookies being tried.
     */
    private fun submitTransaction(what: String, success: String, body: (String) -> JSONObject): String {
        val url = "$writeUrl/seasons/$year/segments/0/leagues/$leagueId/transactions/"
        var last: Response? = null
        for (cookie in listOfNotNull(working) + candidates.filterNotNull()) {
            val swid = EspnLogin.cookieValues(listOf(cookie), "SWID").firstOrNull() ?: continue
            val resp = request(url, cookie, emptyMap(), userAgent, postJson = body(swid).toString())
            last = resp
            if (resp.code == 401 || resp.code == 403) continue
            if (resp.code !in 200..299) break
            val result = parseObject(resp.body)
            val status = result?.optString("status").orEmpty()
            if (status.contains("FAIL", ignoreCase = true) || status.contains("REJECT", ignoreCase = true)) {
                throw EspnException("ESPN didn't accept the $what: ${espnMessage(resp.body) ?: status}")
            }
            working = cookie
            return if (status.isNotBlank() && status != "null") "ESPN: ${status.lowercase()}" else success
        }
        val r = last ?: throw EspnException("Sign in to ESPN to make changes.", authProblem = true)
        throw EspnException(
            "ESPN didn't accept the $what (HTTP ${r.code})" + (espnMessage(r.body)?.let { ": $it" } ?: "."),
            authProblem = r.code == 401 || r.code == 403,
        )
    }

    /** The league's pending transactions (waiver claims waiting to process). Not cached. */
    fun pendingTransactions(): JSONObject = get(listOf("view" to "mPendingTransactions"))

    /** NFL teams with their bye weeks and kickoffs. Public; refreshed daily (games get flexed). */
    fun proTeams(): JSONObject = cached("pro", reuseFor = ONE_DAY) {
        val resp = request("$baseUrl/seasons/$year?view=proTeamSchedules_wl", null, emptyMap(), userAgent)
        if (resp.code in 200..299) parseObject(resp.body) ?: JSONObject() else throw EspnException("Pro schedule: HTTP ${resp.code}")
    }

    /** Best effort: optional data that the rest of the app can do without. */
    private fun <T> optional(fallback: T, block: () -> T): T = try { block() } catch (e: EspnException) { fallback }

    /**
     * The current week's rosters with projections and live games, the bits that
     * change minute to minute during games. Used for live refreshes.
     */
    fun currentWeek(league: League, pro: Map<Int, ProTeam>, ratings: PositionRatings): Pair<Map<Int, List<RosterSpot>>, Map<String, NflGame>> {
        val week = league.currentScoringPeriod
        val matchupPeriod = league.matchupPeriods.entries.firstOrNull { week in it.value }?.key ?: league.currentWeek
        val scoreboard = optional(emptyMap()) { EspnParser.parseScoreboard(nflScoreboard(week)) }
        val rosters = EspnParser.parseCurrentRosters(cached("current-$week") { boxScoresNet(matchupPeriod, week) }, week, pro, ratings)
        return rosters.withGames(scoreboard) to scoreboard
    }

    private fun boxScoresNet(matchupPeriod: Int, scoringPeriod: Int): JSONObject = get(
        listOf("view" to "mMatchupScore", "view" to "mScoreboard", "scoringPeriodId" to scoringPeriod.toString()),
        filter = """{"schedule":{"filterMatchupPeriodIds":{"value":[$matchupPeriod]}}}""",
    )

    /**
     * The coming week: rosters with projections for every team and the free
     * agent pool. Best effort: returns null rather than failing the whole load.
     */
    fun outlook(
        league: League,
        pro: Map<Int, ProTeam>,
        ratings: PositionRatings = emptyMap(),
        onProgress: (String) -> Unit = {},
    ): Pair<WeekOutlook, Map<String, NflGame>>? {
        if (league.seasonOver) return null
        val week = league.currentScoringPeriod
        return try {
            onProgress("Loading projections for week $week…")
            val (rosters, scoreboard) = currentWeek(league, pro, ratings)
            onProgress("Loading free agents…")
            val positionSlots = listOf(0, 2, 4, 6, 16, 17) // QB, RB, WR, TE, D/ST, K
                .filter { slot -> league.lineupSlotCounts.isEmpty() || positionNeeded(slot, league.lineupSlotCounts) }
            val freeAgents = positionSlots.flatMap { slot ->
                try {
                    EspnParser.parseFreeAgents(freeAgents(week, slot), week, pro, ratings, year)
                } catch (e: EspnException) {
                    emptyList()
                }
            }.distinctBy { it.id }.map { p -> scoreboard[p.proTeam]?.let { p.copy(game = it) } ?: p }
            onProgress("Loading season form…")
            val rostered = rosters.values.flatten().map { it.player.id }
            val seasons = optional(emptyMap()) { EspnParser.parseSeasons(seasonStats(week, rostered), year) } +
                positionSlots.flatMap { slot -> optional(emptyMap<Long, Map<Int, WeekLine>>()) {
                    EspnParser.parseSeasons(freeAgents(week, slot), year)
                }.entries }.associate { it.key to it.value }
            WeekOutlook(week, rosters, freeAgents, seasons) to scoreboard
        } catch (e: EspnException) {
            null
        }
    }

    /** Whether any starting slot in the league can use the position that [slot] holds. */
    private fun positionNeeded(slot: Int, counts: Map<Int, Int>): Boolean {
        val flexFor = mapOf(0 to setOf(7), 2 to setOf(3, 23, 7), 4 to setOf(3, 5, 23, 7), 6 to setOf(5, 23, 7))
        return (counts[slot] ?: 0) > 0 || flexFor[slot].orEmpty().any { (counts[it] ?: 0) > 0 }
    }

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
            // Weeks before the current one are final; they come from the cache after the first time.
            box[week] = EspnParser.parseBoxScores(boxScores(week, periods[0], final = week < league.currentWeek), periods[0])
        }
        val pro = optional(emptyMap()) { EspnParser.parseProTeams(proTeams()) }
        val ratings = optional(emptyMap()) { EspnParser.parseRatings(positionalRatings(league.currentScoringPeriod)) }
        val outlook = outlook(league, pro, ratings, onProgress)
        return LeagueData(
            league, box, outlook?.first, pro, ratings, outlook?.second.orEmpty(),
            fetchedAt = if (offline) cache?.savedAt("league") ?: 0L else System.currentTimeMillis(),
        )
    }

    /**
     * Every team's lineup for matchup period [week] with projections and
     * points, as ESPN shows it on the matchup page (past, current or future).
     */
    fun weekRosters(
        league: League,
        week: Int,
        pro: Map<Int, ProTeam>,
        ratings: PositionRatings = emptyMap(),
        fresh: Boolean = false,
    ): Map<Int, List<RosterSpot>> {
        val scoringPeriod = league.matchupPeriods[week]?.firstOrNull() ?: week
        val final = !fresh && week < league.currentWeek
        val json = if (fresh) cached("box-$week-$scoringPeriod") { boxScoresNet(week, scoringPeriod) }
        else boxScores(week, scoringPeriod, final)
        return EspnParser.parseCurrentRosters(json, scoringPeriod, pro, ratings)
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
        private const val WRITE_URL = "https://lm-api-writes.fantasy.espn.com/apis/v3/games/ffl"
        private const val SCOREBOARD_URL = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"
        private const val SIX_HOURS = 6L * 60 * 60 * 1000
        private const val THIRTY_MINUTES = 30L * 60 * 1000
        private const val ONE_DAY = 24L * 60 * 60 * 1000

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

        private fun request(
            url: String,
            cookie: String?,
            headers: Map<String, String>,
            userAgent: String?,
            postJson: String? = null,
        ): Response {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                cookie?.let { conn.setRequestProperty("Cookie", it) }
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (postJson != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(postJson.toByteArray()) }
                }
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
