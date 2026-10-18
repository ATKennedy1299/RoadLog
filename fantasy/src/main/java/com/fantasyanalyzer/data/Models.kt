package com.fantasyanalyzer.data

val POSITIONS = mapOf(1 to "QB", 2 to "RB", 3 to "WR", 4 to "TE", 5 to "K", 16 to "D/ST")

val SLOTS = mapOf(
    0 to "QB", 1 to "TQB", 2 to "RB", 3 to "RB/WR", 4 to "WR", 5 to "WR/TE", 6 to "TE", 7 to "OP",
    8 to "DT", 9 to "DE", 10 to "LB", 11 to "DL", 12 to "CB", 13 to "S", 14 to "DB", 15 to "DP",
    16 to "D/ST", 17 to "K", 18 to "P", 19 to "HC", 20 to "BE", 21 to "IR", 23 to "FLEX", 24 to "ER",
)
const val BENCH = 20
const val IR = 21

/** Slots that never count toward a starting lineup. */
val INACTIVE_SLOTS = setOf(BENCH, IR)

fun slotName(id: Int): String = SLOTS[id] ?: id.toString()

data class Team(val id: Int, val name: String, val abbrev: String, val owner: String)

data class Matchup(
    val week: Int,
    val homeId: Int,
    val awayId: Int?, // null for a playoff bye
    val homePoints: Double,
    val awayPoints: Double,
    val winner: String, // HOME, AWAY, TIE, UNDECIDED
    val isPlayoff: Boolean,
) {
    val complete: Boolean get() = winner != "UNDECIDED"
}

data class RosterEntry(
    val name: String,
    val position: String,
    val slotId: Int,
    val points: Double,
    val eligibleSlots: List<Int>,
) {
    val started: Boolean get() = slotId !in INACTIVE_SLOTS
}

data class League(
    val id: Long,
    val year: Int,
    val name: String,
    val teams: Map<Int, Team>,
    val matchups: List<Matchup>,
    val currentWeek: Int,
    val regularSeasonWeeks: Int,
    val lineupSlotCounts: Map<Int, Int>,
    /** matchup period -> scoring periods (usually one NFL week each) */
    val matchupPeriods: Map<Int, List<Int>> = emptyMap(),
) {
    fun teamName(id: Int): String = teams[id]?.name ?: "Team $id"

    fun completedWeeks(includePlayoffs: Boolean = false): List<Int> =
        matchups.filter { it.complete && it.awayId != null && (includePlayoffs || !it.isPlayoff) }
            .map { it.week }.distinct().sorted()
}

/** Week -> team id -> that team's roster with points for the week. */
typealias BoxScores = Map<Int, Map<Int, List<RosterEntry>>>

data class LeagueData(val league: League, val boxScores: BoxScores)
