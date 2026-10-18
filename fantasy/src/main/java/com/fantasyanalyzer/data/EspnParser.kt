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
            id to Team(id, teamName(t), t.optString("abbrev"), owner, ownerIds, t.optInt("divisionId", 0))
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
            playoffTeamCount = sched.optInt("playoffTeamCount", 4).coerceIn(1, teams.size.coerceAtLeast(1)),
        )
    }

    /** Returns team id -> roster for one week. */
    fun parseBoxScores(raw: JSONObject, scoringPeriod: Int): Map<Int, List<RosterEntry>> {
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
                        points = playerPoints(pool, scoringPeriod),
                        eligibleSlots = elig?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                        playerId = player.optLong("id", e.optLong("playerId", 0)),
                        proTeam = PRO_TEAMS[player.optInt("proTeamId", 0)] ?: "",
                        projected = player.optJSONArray("stats").objects()
                            .firstOrNull { it.optInt("scoringPeriodId") == scoringPeriod && it.optInt("statSourceId", -1) == 1 }
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
    fun parseCurrentRosters(raw: JSONObject, week: Int, proTeams: Map<Int, ProTeam>): Map<Int, List<RosterSpot>> {
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
                        player = parsePlayer(pool, week, proTeams),
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
    fun parseFreeAgents(raw: JSONObject, week: Int, proTeams: Map<Int, ProTeam>): List<Player> =
        raw.optJSONArray("players").objects()
            .filter { it.optString("status", "FREEAGENT") in setOf("FREEAGENT", "WAIVERS") }
            .map { parsePlayer(it, week, proTeams).copy(onWaivers = it.optString("status") == "WAIVERS") }

    /** [pool] is a `playerPoolEntry` (or free agent list item): `{id, player: {...}, ...}`. */
    fun parsePlayer(pool: JSONObject, week: Int, proTeams: Map<Int, ProTeam>): Player {
        val player = pool.optJSONObject("player") ?: JSONObject()
        val stats = player.optJSONArray("stats").objects()
        val projected = stats.firstOrNull { it.optInt("scoringPeriodId") == week && it.optInt("statSourceId", -1) == 1 }
            ?.optDouble("appliedTotal", 0.0)?.orZero()
        val actual = stats.firstOrNull { it.optInt("scoringPeriodId") == week && it.optInt("statSourceId", -1) == 0 }
            ?.optDouble("appliedTotal", 0.0)?.orZero()
        val recent = stats
            .filter { it.optInt("statSourceId", -1) == 0 && it.optInt("scoringPeriodId") in (week - 3) until week }
            .sortedBy { it.optInt("scoringPeriodId") }
            .distinctBy { it.optInt("scoringPeriodId") }
            .map { it.optDouble("appliedTotal", 0.0).orZero() }
        val proTeamId = player.optInt("proTeamId", 0)
        val pro = proTeams[proTeamId] ?: ProTeam(PRO_TEAMS[proTeamId] ?: "FA", 0)
        val elig = player.optJSONArray("eligibleSlots")
        val ownership = player.optJSONObject("ownership")
        val status = player.optString("injuryStatus").takeIf { it.isNotBlank() && it != "null" }
            ?: pool.optString("injuryStatus").takeIf { it.isNotBlank() && it != "null" }
            ?: if (player.optBoolean("injured", false)) "QUESTIONABLE" else "ACTIVE"
        return Player(
            id = player.optLong("id", pool.optLong("id", 0)),
            name = player.optString("fullName", "Unknown"),
            position = POSITIONS[player.optInt("defaultPositionId", -1)] ?: "?",
            proTeam = pro.abbrev,
            eligibleSlots = elig?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
            injuryStatus = status.uppercase(),
            projected = projected,
            recentPoints = recent,
            onBye = pro.byeWeek == week,
            percentOwned = ownership?.optDouble("percentOwned", 0.0)?.orZero() ?: 0.0,
            percentChange = ownership?.optDouble("percentChange", 0.0)?.orZero() ?: 0.0,
            actual = actual,
            gameStart = pro.games[week]?.kickoff,
            opponent = pro.games[week]?.let { g ->
                val opp = proTeams[g.opponentId]?.abbrev ?: PRO_TEAMS[g.opponentId] ?: return@let null
                if (g.home) opp else "@$opp"
            },
        )
    }

    /** ESPN pro team ids, used when the pro schedule couldn't be loaded. */
    private val PRO_TEAMS = mapOf(
        1 to "ATL", 2 to "BUF", 3 to "CHI", 4 to "CIN", 5 to "CLE", 6 to "DAL", 7 to "DEN", 8 to "DET",
        9 to "GB", 10 to "TEN", 11 to "IND", 12 to "KC", 13 to "LV", 14 to "LAR", 15 to "MIA", 16 to "MIN",
        17 to "NE", 18 to "NO", 19 to "NYG", 20 to "NYJ", 21 to "PHI", 22 to "ARI", 23 to "PIT", 24 to "LAC",
        25 to "SF", 26 to "SEA", 27 to "TB", 28 to "WSH", 29 to "CAR", 30 to "JAX", 33 to "BAL", 34 to "HOU",
    )

    private fun playerPoints(pool: JSONObject, scoringPeriod: Int): Double {
        val stats = pool.optJSONObject("player")?.optJSONArray("stats").objects()
        // statSourceId 0 = actual points, 1 = projection
        stats.firstOrNull { it.optInt("scoringPeriodId") == scoringPeriod && it.optInt("statSourceId", -1) == 0 }
            ?.let { return it.optDouble("appliedTotal", 0.0).orZero() }
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
