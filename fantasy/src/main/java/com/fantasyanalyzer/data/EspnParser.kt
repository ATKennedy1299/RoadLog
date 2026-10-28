package com.fantasyanalyzer.data

import org.json.JSONArray
import org.json.JSONObject

/** Parses raw ESPN v3 API JSON into the plain models the analysis works on. */
object EspnParser {

    fun parseLeague(raw: JSONObject): League {
        val settings = raw.optJSONObject("settings") ?: JSONObject()

        val members = raw.optJSONArray("members").objects().associateBy { it.optString("id") }
        val teams = raw.optJSONArray("teams").objects().associate { t ->
            val id = t.getInt("id")
            val ownerIds = t.optJSONArray("owners")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val owner = members[ownerIds.firstOrNull()]?.let { m ->
                m.optString("displayName").ifBlank {
                    "${m.optString("firstName")} ${m.optString("lastName")}".trim()
                }
            } ?: ""
            val logo = t.optString("logo").takeIf { it.startsWith("http") }
            val faabSpent = t.optJSONObject("transactionCounter")?.optInt("acquisitionBudgetSpent", 0) ?: 0
            id to Team(id, teamName(t), t.optString("abbrev"), owner, ownerIds, t.optInt("divisionId", 0), logo, faabSpent)
        }

        val sched = settings.optJSONObject("scheduleSettings") ?: JSONObject()
        val regWeeks = sched.optInt("matchupPeriodCount", 0)

        val matchups = raw.optJSONArray("schedule").objects().mapNotNull { m ->
            val home = m.optJSONObject("home") ?: return@mapNotNull null
            val away = m.optJSONObject("away")
            val week = m.getInt("matchupPeriodId")
            val tier = m.optString("playoffTierType", "NONE").ifBlank { "NONE" }
            Matchup(
                week = week,
                homeId = home.getInt("teamId"),
                awayId = away?.getInt("teamId"),
                homePoints = home.optDouble("totalPoints", 0.0).orZero(),
                awayPoints = away?.optDouble("totalPoints", 0.0)?.orZero() ?: 0.0,
                winner = m.optString("winner", "UNDECIDED").ifBlank { "UNDECIDED" },
                isPlayoff = tier != "NONE" || (regWeeks > 0 && week > regWeeks),
            )
        }

        val status = raw.optJSONObject("status")
        val current = status?.optInt("currentMatchupPeriod", 0)?.takeIf { it > 0 }
            ?: raw.optInt("scoringPeriodId", 1)
        val scoringPeriod = raw.optInt("scoringPeriodId", 0).takeIf { it > 0 }
            ?: status?.optInt("latestScoringPeriod", 0)?.takeIf { it > 0 } ?: current
        val finalPeriod = status?.optInt("finalScoringPeriod", 0)?.takeIf { it > 0 } ?: 17

        val slotCounts = mutableMapOf<Int, Int>()
        settings.optJSONObject("rosterSettings")?.optJSONObject("lineupSlotCounts")?.let { counts ->
            for (k in counts.keys()) {
                val v = counts.optInt(k, 0)
                if (v > 0) slotCounts[k.toInt()] = v
            }
        }

        val scoring = settings.optJSONObject("scoringSettings")?.optJSONArray("scoringItems").objects().associate { item ->
            val overrides = item.optJSONObject("pointsOverrides")?.let { o ->
                o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.optDouble(k, 0.0).orZero() } }.toMap()
            } ?: emptyMap()
            item.optInt("statId") to ScoringRule(item.optDouble("points", 0.0).orZero(), overrides)
        }

        val periods = mutableMapOf<Int, List<Int>>()
        sched.optJSONObject("matchupPeriods")?.let { mp ->
            for (k in mp.keys()) {
                val arr = mp.optJSONArray(k) ?: continue
                periods[k.toInt()] = (0 until arr.length()).map { arr.getInt(it) }
            }
        }

        return League(
            id = raw.optLong("id", 0),
            year = raw.optInt("seasonId", 0),
            name = settings.optString("name", "Fantasy League").ifBlank { "Fantasy League" },
            teams = teams,
            matchups = matchups,
            currentWeek = current,
            regularSeasonWeeks = regWeeks,
            lineupSlotCounts = slotCounts,
            matchupPeriods = periods,
            currentScoringPeriod = scoringPeriod,
            finalScoringPeriod = finalPeriod,
            scoring = scoring,
            playoffTeamCount = sched.optInt("playoffTeamCount", 4).coerceIn(1, teams.size.coerceAtLeast(1)),
            faabBudget = settings.optJSONObject("acquisitionSettings")?.let { a ->
                if (a.optBoolean("isUsingAcquisitionBudget", false)) a.optInt("acquisitionBudget", 100) else null
            },
        )
    }

    /** Returns team id -> roster for one week. */
    fun parseBoxScores(raw: JSONObject, scoringPeriod: Int): Map<Int, List<RosterEntry>> {
        val season = raw.optInt("seasonId", 0).takeIf { it > 0 }
        val rosters = mutableMapOf<Int, List<RosterEntry>>()
        for (m in raw.optJSONArray("schedule").objects()) {
            for (side in listOf("home", "away")) {
                val team = m.optJSONObject(side) ?: continue
                val roster = team.optJSONObject("rosterForCurrentScoringPeriod")
                    ?: team.optJSONObject("rosterForMatchupPeriod")
                    ?: continue
                val entries = roster.optJSONArray("entries").objects().map { e ->
                    val pool = e.optJSONObject("playerPoolEntry") ?: JSONObject()
                    val player = pool.optJSONObject("player") ?: JSONObject()
                    val elig = player.optJSONArray("eligibleSlots")
                    RosterEntry(
                        name = player.optString("fullName", "Unknown"),
                        position = POSITIONS[player.optInt("defaultPositionId", -1)] ?: "?",
                        slotId = e.optInt("lineupSlotId", BENCH),
                        points = playerPoints(pool, scoringPeriod, season),
                        eligibleSlots = elig?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                        playerId = player.optLong("id", e.optLong("playerId", 0)),
                        proTeam = PRO_TEAMS[player.optInt("proTeamId", 0)] ?: "",
                        projected = weekStat(player.optJSONArray("stats").objects(), scoringPeriod, PROJECTED, season)
                            ?.optDouble("appliedTotal", 0.0)?.orZero(),
                    )
                }
                if (entries.isNotEmpty()) rosters[team.getInt("teamId")] = entries
            }
        }
        return rosters
    }

    /** Pro team id -> abbreviation, bye week and kickoff times, from the season's pro schedule. */
    fun parseProTeams(raw: JSONObject): Map<Int, ProTeam> =
        raw.optJSONObject("settings")?.optJSONArray("proTeams").objects().associate { t ->
            val id = t.optInt("id")
            val games = mutableMapOf<Int, ProGame>()
            t.optJSONObject("proGamesByScoringPeriod")?.let { byWeek ->
                for (k in byWeek.keys()) {
                    val g = byWeek.optJSONArray(k).objects().firstOrNull() ?: continue
                    val date = g.optLong("date", 0L)
                    val homeId = g.optInt("homeProTeamId", 0)
                    val awayId = g.optInt("awayProTeamId", 0)
                    val home = homeId == id
                    if (date > 0) k.toIntOrNull()?.let { games[it] = ProGame(date, if (home) awayId else homeId, home) }
                }
            }
            id to ProTeam(t.optString("abbrev").uppercase(), t.optInt("byeWeek", 0), games)
        }

    /**
     * Every team's roster for [week] with ESPN's projections, from a box score
     * response requested for that week.
     */
    fun parseCurrentRosters(
        raw: JSONObject,
        week: Int,
        proTeams: Map<Int, ProTeam>,
        ratings: PositionRatings = emptyMap(),
    ): Map<Int, List<RosterSpot>> {
        val season = raw.optInt("seasonId", 0).takeIf { it > 0 }
        val rosters = mutableMapOf<Int, List<RosterSpot>>()
        for (m in raw.optJSONArray("schedule").objects()) {
            for (side in listOf("home", "away")) {
                val team = m.optJSONObject(side) ?: continue
                val roster = team.optJSONObject("rosterForCurrentScoringPeriod")
                    ?: team.optJSONObject("rosterForMatchupPeriod")
                    ?: continue
                val spots = roster.optJSONArray("entries").objects().map { e ->
                    val pool = e.optJSONObject("playerPoolEntry") ?: JSONObject()
                    RosterSpot(
                        player = parsePlayer(pool, week, proTeams, ratings, season),
                        slotId = e.optInt("lineupSlotId", BENCH),
                        locked = pool.optBoolean("lineupLocked", false) || e.optBoolean("lineupLocked", false),
                    )
                }
                if (spots.isNotEmpty()) rosters[team.getInt("teamId")] = spots
            }
        }
        return rosters
    }

    /** Free agents and waiver-wire players from a `kona_player_info` response. */
    fun parseFreeAgents(
        raw: JSONObject,
        week: Int,
        proTeams: Map<Int, ProTeam>,
        ratings: PositionRatings = emptyMap(),
        season: Int? = null,
    ): List<Player> =
        raw.optJSONArray("players").objects()
            .filter { it.optString("status", "FREEAGENT") in setOf("FREEAGENT", "WAIVERS") }
            .map { parsePlayer(it, week, proTeams, ratings, season).copy(onWaivers = it.optString("status") == "WAIVERS") }

    /**
     * [pool] is a `playerPoolEntry` (or free agent list item): `{id, player: {...}, ...}`.
     * Only [season]'s weekly stats are read; ESPN also sends other seasons' entries
     * for the same week numbers.
     */
    fun parsePlayer(
        pool: JSONObject,
        week: Int,
        proTeams: Map<Int, ProTeam>,
        ratings: PositionRatings = emptyMap(),
        season: Int? = null,
    ): Player {
        val player = pool.optJSONObject("player") ?: JSONObject()
        val stats = weekly(player.optJSONArray("stats").objects(), season)
        val projected = weekStat(stats, week, PROJECTED, season)?.optDouble("appliedTotal", 0.0)?.orZero()
        val actualStat = weekStat(stats, week, ACTUAL, season)
        val actual = actualStat?.optDouble("appliedTotal", 0.0)?.orZero()
        val recent = stats
            .filter { it.optInt("statSourceId", -1) == ACTUAL && it.optInt("scoringPeriodId") in (week - 3) until week }
            .sortedBy { it.optInt("scoringPeriodId") }
            .distinctBy { it.optInt("scoringPeriodId") }
            .map { it.optDouble("appliedTotal", 0.0).orZero() }
        val proTeamId = player.optInt("proTeamId", 0)
        val pro = proTeams[proTeamId] ?: ProTeam(PRO_TEAMS[proTeamId] ?: "FA", 0)
        val elig = player.optJSONArray("eligibleSlots")
        val ownership = player.optJSONObject("ownership")
        val positionId = player.optInt("defaultPositionId", -1)
        val game = pro.games[week]
        val status = player.optString("injuryStatus").takeIf { it.isNotBlank() && it != "null" }
            ?: pool.optString("injuryStatus").takeIf { it.isNotBlank() && it != "null" }
            ?: if (player.optBoolean("injured", false)) "QUESTIONABLE" else "ACTIVE"
        return Player(
            id = player.optLong("id", pool.optLong("id", 0)),
            name = player.optString("fullName", "Unknown"),
            position = POSITIONS[positionId] ?: "?",
            proTeam = pro.abbrev,
            eligibleSlots = elig?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
            injuryStatus = status.uppercase(),
            projected = projected,
            recentPoints = recent,
            onBye = pro.byeWeek == week,
            percentOwned = ownership?.optDouble("percentOwned", 0.0)?.orZero() ?: 0.0,
            percentChange = ownership?.optDouble("percentChange", 0.0)?.orZero() ?: 0.0,
            actual = actual,
            gameStart = game?.kickoff,
            percentStarted = ownership?.optDouble("percentStarted", 0.0)?.orZero() ?: 0.0,
            opponentRank = game?.let { ratings[positionId]?.get(it.opponentId) },
            statLine = actualStat?.optJSONObject("stats")?.let { statLine(it, POSITIONS[positionId]) },
            weekStats = numbers(actualStat?.optJSONObject("stats")),
            appliedStats = numbers(actualStat?.optJSONObject("appliedStats")),
            opponent = game?.let { g ->
                val opp = proTeams[g.opponentId]?.abbrev ?: PRO_TEAMS[g.opponentId] ?: return@let null
                if (g.home) opp else "@$opp"
            },
        )
    }

    /**
     * ESPN's box score line from raw stat ids, e.g. "18/27, 245 YDS, 2 TD" for a
     * QB or "12 REC, 134 YDS" for a receiver. Null when there's nothing to show.
     */
    fun statLine(s: JSONObject, position: String?): String? {
        fun v(id: Int) = s.optDouble(id.toString(), 0.0).let { if (it.isNaN()) 0.0 else it }.toInt()
        val parts = mutableListOf<String>()
        if (position == "QB" || v(0) > 0) {
            if (v(0) > 0) parts += "${v(1)}/${v(0)}"
            if (v(3) != 0) parts += "${v(3)} YDS"
            if (v(4) > 0) parts += "${v(4)} TD"
            if (v(20) > 0) parts += "${v(20)} INT"
            if (v(24) != 0) parts += "${v(24)} RUSH YDS"
        } else {
            // Lead with what the player mostly does, then touchdowns, then the rest.
            val rusher = v(24) > v(42)
            val rushing = listOfNotNull(
                "${v(23)} CAR".takeIf { v(23) > 0 },
                "${v(24)} ${if (rusher) "YDS" else "RUSH YDS"}".takeIf { v(24) != 0 },
            )
            val receiving = listOfNotNull(
                "${v(53)} REC".takeIf { v(53) > 0 },
                "${v(42)} ${if (rusher) "REC YDS" else "YDS"}".takeIf { v(42) != 0 },
            )
            val td = (v(25) + v(43)).takeIf { it > 0 }?.let { "$it TD" }
            parts += if (rusher) rushing + listOfNotNull(td) + receiving else receiving + listOfNotNull(td) + rushing
        }
        return parts.take(4).joinToString(", ").ifBlank { null }
    }

    /**
     * A player card response: NFL week -> points, projection and stat line for
     * [playerId]. Season totals (week 0) are skipped.
     */
    fun parsePlayerWeeks(raw: JSONObject, playerId: Long, season: Int? = null): Map<Int, WeekLine> {
        val entry = raw.optJSONArray("players").objects().firstOrNull {
            it.optLong("id", -1) == playerId || it.optJSONObject("player")?.optLong("id", -1) == playerId
        } ?: return emptyMap()
        return entryWeeks(entry, season)
    }

    /** Every player in a player list response (card or free agents): id -> week by week. */
    fun parseSeasons(raw: JSONObject, season: Int? = null): Map<Long, Map<Int, WeekLine>> =
        raw.optJSONArray("players").objects().mapNotNull { e ->
            val id = e.optJSONObject("player")?.optLong("id", 0)?.takeIf { it != 0L } ?: e.optLong("id", 0)
            entryWeeks(e, season).takeIf { it.isNotEmpty() }?.let { id to it }
        }.toMap()

    private fun entryWeeks(entry: JSONObject, season: Int?): Map<Int, WeekLine> {
        val player = entry.optJSONObject("player") ?: return emptyMap()
        val position = POSITIONS[player.optInt("defaultPositionId", -1)]
        val stats = weekly(player.optJSONArray("stats").objects(), season)
        val weeks = stats.map { it.optInt("scoringPeriodId") }.distinct().sorted()
        return weeks.associateWith { w ->
            val forWeek = stats.filter { it.optInt("scoringPeriodId") == w }
            val actual = forWeek.firstOrNull { it.optInt("statSourceId", -1) == ACTUAL }
            val proj = forWeek.firstOrNull { it.optInt("statSourceId", -1) == PROJECTED }
            WeekLine(
                actual = actual?.optDouble("appliedTotal", 0.0)?.orZero(),
                projected = proj?.optDouble("appliedTotal", 0.0)?.orZero(),
                statLine = actual?.optJSONObject("stats")?.let { statLine(it, position) },
            )
        }
    }

    /**
     * Pending waiver claims for [teamId] from an `mPendingTransactions` response.
     * Best effort: ESPN's shape for these isn't documented.
     */
    fun parsePendingClaims(raw: JSONObject, teamId: Int): List<PendingClaim> {
        val txs = (raw.optJSONArray("pendingTransactions").objects() + raw.optJSONArray("transactions").objects())
            .filter { it.optInt("teamId", -1) == teamId && it.optString("status").equals("PENDING", ignoreCase = true) }
        return txs.mapNotNull { t ->
            val items = t.optJSONArray("items").objects()
            val add = items.firstOrNull { it.optString("type") == "ADD" }?.optLong("playerId", 0) ?: return@mapNotNull null
            val drop = items.firstOrNull { it.optString("type") == "DROP" }?.optLong("playerId", 0)
            PendingClaim(
                id = t.optString("id"),
                addPlayerId = add,
                dropPlayerId = drop?.takeIf { it != 0L },
                bid = t.optInt("bidAmount", 0).takeIf { it > 0 },
                processDate = t.optLong("processDate", 0).takeIf { it > 0 },
            )
        }.distinctBy { it.id }
    }

    /** `mPositionalRatings`: position id -> opponent pro team id -> rank vs that position. */
    fun parseRatings(raw: JSONObject): PositionRatings {
        val byPos = raw.optJSONObject("positionAgainstOpponent")?.optJSONObject("positionalRatings") ?: return emptyMap()
        val out = mutableMapOf<Int, Map<Int, Int>>()
        for (pos in byPos.keys()) {
            val byOpp = byPos.optJSONObject(pos)?.optJSONObject("ratingsByOpponent") ?: continue
            val ranks = mutableMapOf<Int, Int>()
            for (opp in byOpp.keys()) {
                val rank = byOpp.optJSONObject(opp)?.optInt("rank", 0) ?: 0
                if (rank > 0) opp.toIntOrNull()?.let { ranks[it] = rank }
            }
            pos.toIntOrNull()?.let { out[it] = ranks }
        }
        return out
    }

    /** ESPN's public NFL scoreboard: each team's game this week, by abbreviation. */
    fun parseScoreboard(raw: JSONObject): Map<String, NflGame> {
        val out = mutableMapOf<String, NflGame>()
        for (event in raw.optJSONArray("events").objects()) {
            val comp = event.optJSONArray("competitions").objects().firstOrNull() ?: continue
            val teams = comp.optJSONArray("competitors").objects()
            val home = teams.firstOrNull { it.optString("homeAway") == "home" } ?: continue
            val away = teams.firstOrNull { it.optString("homeAway") == "away" } ?: continue
            val status = comp.optJSONObject("status") ?: event.optJSONObject("status") ?: continue
            val type = status.optJSONObject("type") ?: JSONObject()
            val state = type.optString("state", NflGame.PRE)
            val period = status.optInt("period", 0)
            val clock = status.optString("displayClock", "15:00")
            val remaining = when (state) {
                NflGame.PRE -> 1.0
                NflGame.POST -> 0.0
                else -> {
                    val secs = clock.split(":").let { p -> (p.getOrNull(0)?.toIntOrNull() ?: 15) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0) }
                    val quartersDone = (period - 1).coerceAtLeast(0) + (900 - secs.coerceIn(0, 900)) / 900.0
                    (1 - quartersDone / 4).coerceIn(0.0, 1.0)
                }
            }
            val ha = home.optJSONObject("team")?.optString("abbreviation").orEmpty().uppercase()
            val aa = away.optJSONObject("team")?.optString("abbreviation").orEmpty().uppercase()
            // Live situation: the possession team comes as ESPN's site team id.
            val situation = comp.optJSONObject("situation")
            val possessionId = situation?.optString("possession")?.takeIf { it.isNotBlank() && it != "null" }
            val possession = when (possessionId) {
                null -> null
                home.optJSONObject("team")?.optString("id"), home.optString("id") -> ha
                away.optJSONObject("team")?.optString("id"), away.optString("id") -> aa
                else -> null
            }
            val game = NflGame(
                state = state,
                detail = type.optString("shortDetail").ifBlank { type.optString("detail") },
                homeAbbrev = ha,
                awayAbbrev = aa,
                homeScore = home.optString("score").toIntOrNull() ?: 0,
                awayScore = away.optString("score").toIntOrNull() ?: 0,
                remainingShare = remaining,
                possession = possession.takeIf { state == NflGame.IN },
                redZone = state == NflGame.IN && situation?.optBoolean("isRedZone", false) == true,
            )
            if (ha.isNotBlank()) out[ha] = game
            if (aa.isNotBlank()) out[aa] = game
        }
        return out
    }

    // statSourceId: 0 = actual points, 1 = ESPN's projection
    private const val ACTUAL = 0
    private const val PROJECTED = 1

    /**
     * The single-week entries (statSplitTypeId 1) of one season. ESPN mixes in
     * season totals and, for some requests, last season's weeks under the same
     * scoringPeriodId, so both are dropped. With no [season] given, the latest
     * season present is used.
     */
    private fun weekly(stats: List<JSONObject>, season: Int?): List<JSONObject> {
        val year = season ?: stats.maxOfOrNull { it.optInt("seasonId", 0) }?.takeIf { it > 0 }
        return stats.filter { s ->
            s.optInt("scoringPeriodId") > 0 &&
                s.optInt("statSplitTypeId", 1) == 1 &&
                (year == null || !s.has("seasonId") || s.optInt("seasonId") == year)
        }
    }

    /** A JSON object of stat id -> number, e.g. ESPN's `stats` or `appliedStats`. */
    private fun numbers(o: JSONObject?): Map<Int, Double> = o?.keys()?.asSequence()
        ?.mapNotNull { k -> k.toIntOrNull()?.let { it to o.optDouble(k, 0.0).orZero() } }
        ?.filter { it.second != 0.0 }?.toMap() ?: emptyMap()

    private fun weekStat(stats: List<JSONObject>, week: Int, source: Int, season: Int?): JSONObject? =
        weekly(stats, season).firstOrNull { it.optInt("scoringPeriodId") == week && it.optInt("statSourceId", -1) == source }

    /** ESPN pro team ids, used when the pro schedule couldn't be loaded. */
    private val PRO_TEAMS = mapOf(
        1 to "ATL", 2 to "BUF", 3 to "CHI", 4 to "CIN", 5 to "CLE", 6 to "DAL", 7 to "DEN", 8 to "DET",
        9 to "GB", 10 to "TEN", 11 to "IND", 12 to "KC", 13 to "LV", 14 to "LAR", 15 to "MIA", 16 to "MIN",
        17 to "NE", 18 to "NO", 19 to "NYG", 20 to "NYJ", 21 to "PHI", 22 to "ARI", 23 to "PIT", 24 to "LAC",
        25 to "SF", 26 to "SEA", 27 to "TB", 28 to "WSH", 29 to "CAR", 30 to "JAX", 33 to "BAL", 34 to "HOU",
    )

    private fun playerPoints(pool: JSONObject, scoringPeriod: Int, season: Int?): Double {
        val stats = pool.optJSONObject("player")?.optJSONArray("stats").objects()
        weekStat(stats, scoringPeriod, ACTUAL, season)?.let { return it.optDouble("appliedTotal", 0.0).orZero() }
        return pool.optDouble("appliedStatTotal", 0.0).orZero()
    }

    private fun teamName(t: JSONObject): String {
        val name = t.optString("name").trim()
        if (name.isNotEmpty()) return name
        val legacy = "${t.optString("location")} ${t.optString("nickname")}".trim()
        return legacy.ifEmpty { "Team ${t.getInt("id")}" }
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
