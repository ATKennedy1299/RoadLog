package com.fantasyanalyzer.data

import kotlin.math.abs

/** One league scoring rule: points per unit of an ESPN stat, with per-position overrides keyed by lineup slot id. */
data class ScoringRule(val points: Double, val overrides: Map<Int, Double> = emptyMap())

/** One line of a player's score breakdown, e.g. Receiving Yards: 0.1 per, 134, 13.4 points. */
data class ScoreLine(val statId: Int, val label: String, val perUnit: Double?, val count: Double, val points: Double)

/** How a player's points add up, ESPN style: each scoring category, its value and the points it earned. */
object ScoreBreakdown {
    fun lines(p: Player, scoring: Map<Int, ScoringRule>): List<ScoreLine> {
        val slot = POSITION_SLOTS[p.position]
        fun per(id: Int) = scoring[id]?.let { r -> slot?.let { r.overrides[it] } ?: r.points }
        // ESPN sends the points each stat earned; when it doesn't, work them out from the league's rules.
        val earned = p.appliedStats.ifEmpty {
            p.weekStats.mapNotNull { (id, v) -> per(id)?.let { id to v * it } }.toMap()
        }
        return earned.filterValues { abs(it) >= 0.005 }.map { (id, pts) ->
            val count = p.weekStats[id] ?: 0.0
            ScoreLine(id, label(id), per(id) ?: if (count != 0.0) pts / count else null, count, pts)
        }.sortedBy { it.statId }
    }

    fun label(statId: Int): String = NAMES[statId] ?: "Stat $statId"

    private val POSITION_SLOTS = mapOf("QB" to 0, "RB" to 2, "WR" to 4, "TE" to 6, "D/ST" to 16, "K" to 17)

    // ESPN's stat ids, named the way ESPN's scoring breakdown names them.
    private val NAMES = mapOf(
        0 to "Pass Attempts", 1 to "Completions", 2 to "Incomplete Passes", 3 to "Passing Yards",
        4 to "TD Pass", 5 to "Every 5 Passing Yards", 6 to "Every 10 Passing Yards", 7 to "Every 20 Passing Yards",
        8 to "Every 25 Passing Yards", 9 to "Every 50 Passing Yards", 10 to "Every 100 Passing Yards",
        11 to "Every 5 Completions", 12 to "Every 10 Completions", 13 to "Every 5 Incompletions",
        14 to "Every 10 Incompletions", 15 to "40+ Yard TD Pass Bonus", 16 to "50+ Yard TD Pass Bonus",
        17 to "300-399 Yard Passing Game", 18 to "400+ Yard Passing Game", 19 to "2pt Passing Conversion",
        20 to "Interceptions Thrown", 21 to "Completion Percentage", 22 to "Passing Yards",
        23 to "Rushing Attempts", 24 to "Rushing Yards", 25 to "TD Rush", 26 to "2pt Rushing Conversion",
        27 to "Every 5 Rushing Yards", 28 to "Every 10 Rushing Yards", 29 to "Every 20 Rushing Yards",
        30 to "Every 25 Rushing Yards", 31 to "Every 50 Rushing Yards", 32 to "Every 100 Rushing Yards",
        33 to "Every 5 Rushing Attempts", 34 to "Every 10 Rushing Attempts", 35 to "40+ Yard TD Rush Bonus",
        36 to "50+ Yard TD Rush Bonus", 37 to "100-199 Yard Rushing Game", 38 to "200+ Yard Rushing Game",
        39 to "Yards per Rush", 40 to "Rushing Yards", 41 to "Receptions", 42 to "Receiving Yards",
        43 to "TD Reception", 44 to "2pt Receiving Conversion", 45 to "40+ Yard TD Reception Bonus",
        46 to "50+ Yard TD Reception Bonus", 47 to "Every 5 Receiving Yards", 48 to "Every 10 Receiving Yards",
        49 to "Every 20 Receiving Yards", 50 to "Every 25 Receiving Yards", 51 to "Every 50 Receiving Yards",
        52 to "Every 100 Receiving Yards", 53 to "Each Reception", 54 to "Every 5 Receptions",
        55 to "Every 10 Receptions", 56 to "100-199 Yard Receiving Game", 57 to "200+ Yard Receiving Game",
        58 to "Targets", 60 to "Yards per Reception", 62 to "2pt Conversion", 63 to "Fumble Recovered for TD",
        64 to "Times Sacked", 65 to "Passing Fumbles", 66 to "Rushing Fumbles", 67 to "Receiving Fumbles",
        68 to "Total Fumbles", 69 to "Passing Fumbles Lost", 70 to "Rushing Fumbles Lost",
        71 to "Receiving Fumbles Lost", 72 to "Total Fumbles Lost", 73 to "Total Turnovers",
        74 to "FG Made (50+ yards)", 75 to "FG Attempted (50+ yards)", 76 to "FG Missed (50+ yards)",
        77 to "FG Made (40-49 yards)", 78 to "FG Attempted (40-49 yards)", 79 to "FG Missed (40-49 yards)",
        80 to "FG Made (0-39 yards)", 81 to "FG Attempted (0-39 yards)", 82 to "FG Missed (0-39 yards)",
        83 to "Field Goals Made", 84 to "Field Goals Attempted", 85 to "Field Goals Missed",
        86 to "Each PAT Made", 87 to "Each PAT Attempted", 88 to "Each PAT Missed",
        89 to "0 Points Allowed", 90 to "1-6 Points Allowed", 91 to "7-13 Points Allowed",
        92 to "14-17 Points Allowed", 93 to "Blocked Punt or FG Return for TD", 94 to "Defensive TD",
        95 to "Each Interception", 96 to "Each Fumble Recovered", 97 to "Blocked Punt, PAT or FG",
        98 to "Each Safety", 99 to "Each Sack", 100 to "Half Sacks", 101 to "Kickoff Return TD",
        102 to "Punt Return TD", 103 to "Interception Return TD", 104 to "Fumble Return TD",
        105 to "Defensive + Special Teams TD", 106 to "Forced Fumbles", 107 to "Assisted Tackles",
        108 to "Solo Tackles", 109 to "Total Tackles", 110 to "Every 3 Tackles", 111 to "Every 5 Tackles",
        112 to "Stuffs", 113 to "Passes Defensed", 114 to "Kickoff Return Yards", 115 to "Punt Return Yards",
        116 to "Every 10 Kickoff Return Yards", 117 to "Every 25 Kickoff Return Yards",
        118 to "Every 10 Punt Return Yards", 119 to "Every 25 Punt Return Yards", 120 to "Points Allowed",
        121 to "18-20 Points Allowed", 122 to "21-27 Points Allowed", 123 to "28-34 Points Allowed",
        124 to "35-45 Points Allowed", 125 to "46+ Points Allowed", 126 to "Points Allowed per Game",
        127 to "Yards Allowed", 128 to "Less Than 100 Total Yards Allowed", 129 to "100-199 Total Yards Allowed",
        130 to "200-299 Total Yards Allowed", 131 to "300-349 Total Yards Allowed",
        132 to "350-399 Total Yards Allowed", 133 to "400-449 Total Yards Allowed",
        134 to "450-499 Total Yards Allowed", 135 to "500-549 Total Yards Allowed",
        136 to "550+ Total Yards Allowed", 198 to "FG Made (50-59 yards)", 199 to "FG Attempted (50-59 yards)",
        200 to "FG Missed (50-59 yards)", 201 to "FG Made (60+ yards)", 202 to "FG Attempted (60+ yards)",
        203 to "FG Missed (60+ yards)", 205 to "2pt Return", 206 to "1pt Safety",
    )
}
