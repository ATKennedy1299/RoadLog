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

data class Team(
    val id: Int,
    val name: String,
    val abbrev: String,
    val owner: String,
    /** ESPN account ids (SWIDs) of the team's managers. */
    val ownerIds: List<String> = emptyList(),
    val divisionId: Int = 0,
)

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
    val playerId: Long = 0,
    val proTeam: String = "",
    /** ESPN's projection for that week, when it sent one. */
    val projected: Double? = null,
) {
    val started: Boolean get() = slotId !in INACTIVE_SLOTS

    /** As a roster spot, for screens built on those; the points are final. */
    fun toSpot(): RosterSpot = RosterSpot(
        Player(playerId, name, position, proTeam, eligibleSlots, "ACTIVE", projected, emptyList(), false, actual = points),
        slotId,
    )
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
    /** The current NFL week (ESPN's scoring period). */
    val currentScoringPeriod: Int = currentWeek,
    /** Last NFL week of the fantasy season; past this there's nothing left to set. */
    val finalScoringPeriod: Int = 17,
    val playoffTeamCount: Int = 4,
) {
    fun teamName(id: Int): String = teams[id]?.name ?: "Team $id"

    /** The team managed by any of these ESPN account ids (SWIDs), if one is. */
    fun teamOwnedBy(swids: Collection<String>): Int? {
        val wanted = swids.map { it.trim().trim('{', '}').lowercase() }.toSet()
        return teams.values.firstOrNull { t -> t.ownerIds.any { it.trim('{', '}').lowercase() in wanted } }?.id
    }

    val seasonOver: Boolean get() = currentScoringPeriod > finalScoringPeriod

    fun completedWeeks(includePlayoffs: Boolean = false): List<Int> =
        matchups.filter { it.complete && it.awayId != null && (includePlayoffs || !it.isPlayoff) }
            .map { it.week }.distinct().sorted()
}

/** Week -> team id -> that team's roster with points for the week. */
typealias BoxScores = Map<Int, Map<Int, List<RosterEntry>>>

/** One NFL player with what's known about them for the coming week. */
data class Player(
    val id: Long,
    val name: String,
    val position: String,
    val proTeam: String,
    val eligibleSlots: List<Int>,
    /** ESPN's status: ACTIVE, QUESTIONABLE, DOUBTFUL, OUT, INJURY_RESERVE, SUSPENSION, ... */
    val injuryStatus: String,
    /** ESPN's projection for the week; null when ESPN has none (usually a bye). */
    val projected: Double?,
    /** Actual points in recent weeks, oldest first. */
    val recentPoints: List<Double>,
    val onBye: Boolean,
    val percentOwned: Double = 0.0,
    val percentChange: Double = 0.0,
    /** Free agent list only: on waivers (needs a claim) rather than free to add. */
    val onWaivers: Boolean = false,
    /** Points scored so far this week; null until their game starts. */
    val actual: Double? = null,
    /** Kickoff of their NFL game this week (epoch ms), when the pro schedule is known. */
    val gameStart: Long? = null,
    /** This week's NFL opponent, "@DEN" away or "DEN" at home, when known. */
    val opponent: String? = null,
) {
    val isOut: Boolean get() = onBye || injuryStatus in OUT_STATUSES

    /** What we expect from them this week: nothing if they won't play. */
    val expected: Double get() = if (isOut) 0.0 else projected ?: 0.0

    val recentAvg: Double? get() = recentPoints.takeIf { it.isNotEmpty() }?.average()

    /** Their game has kicked off: by the schedule if we have it, else because ESPN has points for them. */
    fun gameStarted(now: Long): Boolean = gameStart?.let { it <= now } ?: (actual != null)

    /** Points scored so far, counting only once their game has kicked off. */
    fun pointsSoFar(now: Long): Double = if (gameStarted(now)) actual ?: 0.0 else 0.0

    /** Share of their game still to play: 1 before kickoff, 0 once it's (probably) over. */
    fun remainingShare(now: Long): Double {
        if (!gameStarted(now)) return 1.0
        val start = gameStart ?: return 0.0 // no schedule: treat points as final
        return (1.0 - (now - start).toDouble() / GAME_LENGTH_MS).coerceIn(0.0, 1.0)
    }

    /** Points expected by the end of their game: scored so far plus the projection for what's left. */
    fun expectedFinal(now: Long): Double {
        if (!gameStarted(now)) return expected
        return (actual ?: 0.0) + (projected ?: 0.0) * remainingShare(now)
    }

    companion object {
        val OUT_STATUSES = setOf("OUT", "INJURY_RESERVE", "SUSPENSION")

        /** Kickoff to final whistle, roughly. */
        const val GAME_LENGTH_MS = 3L * 60 * 60 * 1000 + 15 * 60 * 1000
    }
}

/** [locked] is ESPN's own lineup-lock flag, when it sends one. */
data class RosterSpot(val player: Player, val slotId: Int, val locked: Boolean = false) {
    val started: Boolean get() = slotId !in INACTIVE_SLOTS

    /** Can't be moved: ESPN says so, or the player's game has kicked off. */
    fun isLocked(now: Long): Boolean = locked || player.gameStarted(now)
}

/** One NFL game from a team's side: kickoff (epoch ms), opponent's pro team id, and whether it's at home. */
data class ProGame(val kickoff: Long, val opponentId: Int, val home: Boolean)

/** An NFL team: abbreviation, bye week, and its game in each NFL week. */
data class ProTeam(val abbrev: String, val byeWeek: Int, val games: Map<Int, ProGame> = emptyMap()) {
    val kickoffs: Map<Int, Long> get() = games.mapValues { it.value.kickoff }
}

/** The week ahead: every team's current roster with projections, plus the free agent pool. */
data class WeekOutlook(
    val week: Int,
    val rosters: Map<Int, List<RosterSpot>>,
    val freeAgents: List<Player>,
)

data class LeagueData(
    val league: League,
    val boxScores: BoxScores,
    /** Null when the season is over or ESPN didn't return it. */
    val outlook: WeekOutlook? = null,
    /** NFL teams by ESPN pro team id; empty if the pro schedule couldn't be loaded. */
    val proTeams: Map<Int, ProTeam> = emptyMap(),
)
