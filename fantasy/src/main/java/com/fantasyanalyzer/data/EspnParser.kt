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
            val ownerId = t.optJSONArray("owners")?.optString(0)
            val owner = members[ownerId]?.let { m ->
                m.optString("displayName").ifBlank {
                    "${m.optString("firstName")} ${m.optString("lastName")}".trim()
                }
            } ?: ""
            id to Team(id, teamName(t), t.optString("abbrev"), owner)
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
                    )
                }
                if (entries.isNotEmpty()) rosters[team.getInt("teamId")] = entries
            }
        }
        return rosters
    }

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
