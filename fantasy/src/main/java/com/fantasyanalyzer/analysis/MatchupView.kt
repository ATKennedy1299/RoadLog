package com.fantasyanalyzer.analysis

import com.fantasyanalyzer.data.BENCH
import com.fantasyanalyzer.data.IR
import com.fantasyanalyzer.data.League
import com.fantasyanalyzer.data.Matchup
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.slotName
import kotlin.math.sqrt

data class MatchupSide(
    val teamId: Int,
    /** Points so far (official final once the matchup is over). */
    val actual: Double,
    /** Projected total: pre-game projections for a finished week, live projection otherwise; null without lineups. */
    val projected: Double?,
)

/** One line of the side-by-side lineup: a slot with each team's player in it (either may be empty). */
data class SlotRow(val slot: String, val home: RosterSpot?, val away: RosterSpot?)

data class MatchupView(
    val matchup: Matchup,
    val home: MatchupSide,
    val away: MatchupSide?,
    /** Home team's chance to win; null when there's nothing to base it on (or a bye). */
    val homeWinProbability: Double?,
    val starters: List<SlotRow>,
    val bench: List<SlotRow>,
)

/** The same matchup with [teamId] on the home (left) side, if they're playing in it. */
fun Matchup.orientedFor(teamId: Int?): Matchup =
    if (teamId == null || awayId != teamId) this
    else copy(
        homeId = teamId, awayId = homeId, homePoints = awayPoints, awayPoints = homePoints,
        winner = when (winner) { "HOME" -> "AWAY"; "AWAY" -> "HOME"; else -> winner },
    )

object Matchups {
    /** ESPN's lineup order: QB, RB, RB/WR, WR, WR/TE, TE, FLEX, OP, IDP slots, D/ST, K, P, HC. */
    private val SLOT_ORDER = listOf(0, 1, 2, 3, 4, 5, 6, 23, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 24)

    fun slotRank(slotId: Int): Int = SLOT_ORDER.indexOf(slotId).let { if (it < 0) SLOT_ORDER.size + slotId else it }

    /** Matchups in [week], the user's first. */
    fun inWeek(league: League, week: Int, myTeam: Int?): List<Matchup> =
        league.matchups.filter { it.week == week }
            .sortedByDescending { it.homeId == myTeam || it.awayId == myTeam }

    fun view(league: League, m: Matchup, rosters: Map<Int, List<RosterSpot>>?, now: Long = System.currentTimeMillis()): MatchupView {
        val homeRoster = rosters?.get(m.homeId)
        val awayRoster = m.awayId?.let { rosters?.get(it) }

        fun side(teamId: Int, official: Double, roster: List<RosterSpot>?): MatchupSide {
            val starters = roster?.filter { it.started }
            val actual = when {
                m.complete || starters == null -> official
                else -> starters.sumOf { it.player.pointsSoFar(now) }
            }
            val projected = starters?.sumOf { s ->
                if (m.complete) s.player.projected ?: 0.0 else s.player.expectedFinal(now)
            }
            return MatchupSide(teamId, actual, projected?.takeIf { starters.isNotEmpty() })
        }

        val home = side(m.homeId, m.homePoints, homeRoster)
        val away = m.awayId?.let { side(it, m.awayPoints, awayRoster) }

        val prob = when {
            away == null -> null
            m.complete -> when (m.winner) { "HOME" -> 1.0; "AWAY" -> 0.0; else -> 0.5 }
            homeRoster == null || awayRoster == null || home.projected == null || away.projected == null -> null
            else -> {
                val teamSd = Odds.scoreModel(league).sd
                fun sdLeft(r: List<RosterSpot>): Double {
                    val st = r.filter { it.started }
                    val total = st.sumOf { it.player.expected }
                    if (total <= 0) return 0.0
                    return teamSd * sqrt(st.sumOf { it.player.expected * it.player.remainingShare(now) } / total)
                }
                val sd = sqrt(sdLeft(homeRoster).let { it * it } + sdLeft(awayRoster).let { it * it })
                val diff = home.projected - away.projected
                if (sd < 1e-6) (if (diff > 0) 1.0 else if (diff < 0) 0.0 else 0.5) else Outlook.normalCdf(diff / sd)
            }
        }

        return MatchupView(
            matchup = m,
            home = home,
            away = away,
            homeWinProbability = prob,
            starters = starterRows(league, homeRoster.orEmpty(), awayRoster.orEmpty()),
            bench = benchRows(homeRoster.orEmpty(), awayRoster.orEmpty()),
        )
    }

    /** Pairs the two lineups slot by slot in ESPN's order, padding the side with fewer. */
    fun starterRows(league: League, home: List<RosterSpot>, away: List<RosterSpot>): List<SlotRow> {
        val slots = (league.lineupSlotCounts.keys + home.map { it.slotId } + away.map { it.slotId })
            .filter { it != BENCH && it != IR }
            .distinct()
            .sortedBy { slotRank(it) }
        return slots.flatMap { slot ->
            val h = home.filter { it.slotId == slot }
            val a = away.filter { it.slotId == slot }
            val n = maxOf(league.lineupSlotCounts[slot] ?: 0, h.size, a.size)
            (0 until n).map { i -> SlotRow(slotName(slot), h.getOrNull(i), a.getOrNull(i)) }
        }
    }

    /** Bench and IR players side by side, best first. */
    fun benchRows(home: List<RosterSpot>, away: List<RosterSpot>): List<SlotRow> {
        fun order(r: List<RosterSpot>) = r.filter { !it.started }.sortedWith(
            compareBy<RosterSpot> { it.slotId == IR }.thenByDescending { it.player.actual ?: it.player.expected }
        )
        val h = order(home)
        val a = order(away)
        return (0 until maxOf(h.size, a.size)).map { i ->
            val slot = (h.getOrNull(i) ?: a.getOrNull(i))?.slotId ?: BENCH
            SlotRow(slotName(slot), h.getOrNull(i), a.getOrNull(i))
        }
    }
}
