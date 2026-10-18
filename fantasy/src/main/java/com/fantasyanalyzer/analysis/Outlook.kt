package com.fantasyanalyzer.analysis

import com.fantasyanalyzer.data.IR
import com.fantasyanalyzer.data.INACTIVE_SLOTS
import com.fantasyanalyzer.data.League
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.RosterEntry
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.WeekOutlook
import com.fantasyanalyzer.data.slotName
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

data class LineupAlert(val player: Player, val message: String, val severe: Boolean)

data class StartSitAdvice(
    /** Points the current starters have scored so far this week. */
    val currentActual: Double,
    /** Projected final points of the lineup as it's set now (actual so far plus what's left). */
    val currentProjection: Double,
    /** Projected points of the best lineup from this roster. */
    val bestProjection: Double,
    /** Best lineup, slot name to player, including starters already locked in. */
    val bestLineup: List<Pair<String, Player>>,
    /** Bench players who should start. */
    val start: List<Player>,
    /** Starters who should sit. */
    val sit: List<Player>,
    val alerts: List<LineupAlert>,
    /** Starting slots with nobody in them. */
    val emptySlots: Int,
) {
    val gain: Double get() = bestProjection - currentProjection
}

data class MatchupOutlook(
    val opponentId: Int,
    val myActual: Double,
    val opponentActual: Double,
    /** Projected finals: points so far plus projections for games still to play. */
    val myProjection: Double,
    val myBestProjection: Double,
    val opponentProjection: Double,
    /** True once any starter on either side has kicked off. */
    val started: Boolean,
    /** Chance to win with the lineup as set. */
    val winProbability: Double,
    /** Chance to win with the best lineup. */
    val winProbabilityBest: Double,
)

data class WaiverIdea(val add: Player, val drop: Player?, val gain: Double)

data class FillIn(val out: Player, val options: List<Player>)

data class WaiverReport(
    /** Free agents worth more than someone on the roster, best upgrade first. */
    val upgrades: List<WaiverIdea>,
    /** Starters who won't play this week, with free agents who could cover. */
    val fillIns: List<FillIn>,
    val bestByPosition: List<Pair<String, List<Player>>>,
    val trending: List<Player>,
)

object Outlook {

    /** Best lineup by ESPN projections, keeping players whose games have started where they are. */
    fun startSit(roster: List<RosterSpot>, slotCounts: Map<Int, Int>, now: Long = System.currentTimeMillis()): StartSitAdvice {
        val lockedStarters = roster.filter { it.isLocked(now) && it.started }
        val counts = slotCounts.toMutableMap()
        lockedStarters.forEach { counts[it.slotId] = ((counts[it.slotId] ?: 0) - 1).coerceAtLeast(0) }

        // Movable players; anyone in an IR slot has to be activated before they can start.
        val movable = roster.filter { !it.isLocked(now) && it.slotId != IR }
        val entries = movable.map { RosterEntry(it.player.name, it.player.position, it.slotId, it.player.expected, it.player.eligibleSlots, it.player.id) }
        val opt = Analysis.optimalLineup(entries, counts)
        val byEntry = entries.zip(movable).toMap()
        val picks = opt.picks.map { (slot, e) -> slot to byEntry.getValue(e) }
        val pickedSpots = picks.map { it.second }.toSet()

        val currentStarters = roster.filter { it.started }
        val totalSlots = slotCounts.filterKeys { it !in INACTIVE_SLOTS }.values.sum()
        return StartSitAdvice(
            currentActual = currentStarters.sumOf { it.player.pointsSoFar(now) },
            currentProjection = currentStarters.sumOf { it.player.expectedFinal(now) },
            bestProjection = lockedStarters.sumOf { it.player.expectedFinal(now) } + opt.total,
            bestLineup = lockedStarters.map { slotName(it.slotId) to it.player } + picks.map { (slot, spot) -> slot to spot.player },
            start = pickedSpots.filter { !it.started }.map { it.player }.sortedByDescending { it.expected },
            sit = movable.filter { it.started && it !in pickedSpots }.map { it.player }.sortedBy { it.expected },
            // Nothing to warn about for players who can't be moved any more.
            alerts = currentStarters.filter { !it.isLocked(now) }.mapNotNull { alertFor(it.player) }.sortedByDescending { it.severe },
            emptySlots = (totalSlots - currentStarters.size).coerceAtLeast(0),
        )
    }

    private fun alertFor(p: Player): LineupAlert? = when {
        p.onBye -> LineupAlert(p, "is on bye this week", true)
        p.injuryStatus == "OUT" -> LineupAlert(p, "is ruled out", true)
        p.injuryStatus == "INJURY_RESERVE" -> LineupAlert(p, "is on injured reserve", true)
        p.injuryStatus == "SUSPENSION" -> LineupAlert(p, "is suspended", true)
        p.injuryStatus == "DOUBTFUL" -> LineupAlert(p, "is doubtful", true)
        p.injuryStatus == "QUESTIONABLE" -> LineupAlert(p, "is questionable; have a backup ready", false)
        p.projected == null -> LineupAlert(p, "has no ESPN projection", false)
        else -> null
    }

    /**
     * This week's matchup for [teamId]: points so far, projected finals, and win
     * chances. The uncertainty shrinks as each side's games get played.
     */
    fun matchup(league: League, outlook: WeekOutlook, teamId: Int, now: Long = System.currentTimeMillis()): MatchupOutlook? {
        val m = league.matchups.firstOrNull {
            it.week == league.currentWeek && !it.complete && (it.homeId == teamId || it.awayId == teamId)
        } ?: return null
        val oppId = (if (m.homeId == teamId) m.awayId else m.homeId) ?: return null
        val mine = outlook.rosters[teamId] ?: return null
        val theirs = outlook.rosters[oppId] ?: return null
        val advice = startSit(mine, league.lineupSlotCounts, now)
        val oppStarters = theirs.filter { it.started }
        val oppFinal = oppStarters.sumOf { it.player.expectedFinal(now) }
        val myStarters = mine.filter { it.started }

        // A team's weekly spread, scaled by the share of its projected points still to come.
        val teamSd = Odds.scoreModel(league).sd
        fun sdLeft(starters: List<RosterSpot>): Double {
            val total = starters.sumOf { it.player.expected }
            if (total <= 0) return 0.0
            val left = starters.sumOf { it.player.expected * it.player.remainingShare(now) }
            return teamSd * sqrt(left / total)
        }
        val sd = sqrt(sdLeft(myStarters).let { it * it } + sdLeft(oppStarters).let { it * it })
        fun winChance(mine: Double): Double = if (sd < 1e-6) {
            when { mine > oppFinal -> 1.0; mine < oppFinal -> 0.0; else -> 0.5 }
        } else {
            normalCdf((mine - oppFinal) / sd)
        }
        return MatchupOutlook(
            opponentId = oppId,
            myActual = advice.currentActual,
            opponentActual = oppStarters.sumOf { it.player.pointsSoFar(now) },
            myProjection = advice.currentProjection,
            myBestProjection = advice.bestProjection,
            opponentProjection = oppFinal,
            started = (myStarters + oppStarters).any { it.player.gameStarted(now) },
            winProbability = winChance(advice.currentProjection),
            winProbabilityBest = winChance(advice.bestProjection),
        )
    }

    /**
     * How much a player is worth picking up: mostly ESPN's projection, partly
     * recent production. Ignores byes, since a pickup is for more than one week.
     */
    fun waiverValue(p: Player): Double {
        if (p.injuryStatus in Player.OUT_STATUSES) return 0.0
        val base = p.projected ?: p.recentAvg ?: 0.0
        val form = p.recentAvg ?: base
        return 0.6 * base + 0.4 * form
    }

    fun waivers(outlook: WeekOutlook, teamId: Int, minGain: Double = 1.0): WaiverReport {
        val mine = outlook.rosters[teamId].orEmpty().filter { it.slotId != IR }.map { it.player }
        val pool = outlook.freeAgents

        // Upgrades: each free agent against the weakest player I have at their position.
        val weakest = mine.groupBy { it.position }.mapValues { (_, ps) -> ps.minBy { waiverValue(it) } }
        val candidates = pool.mapNotNull { fa ->
            val drop = weakest[fa.position]
            val gain = waiverValue(fa) - (drop?.let { waiverValue(it) } ?: 0.0)
            if (gain >= minGain) WaiverIdea(fa, drop, gain) else null
        }.sortedByDescending { it.gain }
        // Suggest each drop once, with its best replacement.
        val usedDrops = mutableSetOf<Long>()
        val upgrades = candidates.filter { idea ->
            val key = idea.drop?.id ?: -idea.add.id
            usedDrops.add(key)
        }.take(8)

        val fillIns = outlook.rosters[teamId].orEmpty()
            .filter { it.started && it.player.isOut }
            .map { spot ->
                FillIn(spot.player, pool.filter { fa -> fa.position == spot.player.position && fa.expected > 0 }
                    .sortedByDescending { it.expected }.take(3))
            }

        val bestByPosition = listOf("QB", "RB", "WR", "TE", "K", "D/ST").mapNotNull { pos ->
            pool.filter { it.position == pos }.sortedByDescending { waiverValue(it) }.take(5)
                .takeIf { it.isNotEmpty() }?.let { pos to it }
        }
        val trending = pool.filter { it.percentChange > 0 }.sortedByDescending { it.percentChange }.take(5)
        return WaiverReport(upgrades, fillIns, bestByPosition, trending)
    }

    /** Standard normal CDF (Abramowitz & Stegun 7.1.26; error < 1.5e-7). */
    fun normalCdf(z: Double): Double {
        val t = 1 / (1 + 0.3275911 * abs(z) / sqrt(2.0))
        val y = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) *
            exp(-z * z / 2)
        return if (z >= 0) (1 + y) / 2 else (1 - y) / 2
    }
}
