package com.fantasyanalyzer.analysis

import com.fantasyanalyzer.data.League
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Each team's expected weekly score, and how much any team's score swings week to week. */
data class ScoreModel(val means: Map<Int, Double>, val sd: Double)

data class PlayoffOddsRow(
    val teamId: Int,
    val team: String,
    val record: Record,
    val projectedWins: Double,
    val playoffPct: Double,
    val byePct: Double,
    /** Null once the playoffs have started (the simulation doesn't replay a bracket in progress). */
    val champPct: Double?,
    val avgSeed: Double,
    /** Playoff chances if the team wins / loses its next game; null with no regular-season games left. */
    val ifWinNext: Double?,
    val ifLoseNext: Double?,
)

data class PlayoffOdds(
    val rows: List<PlayoffOddsRow>,
    val playoffSpots: Int,
    val byes: Int,
    val simulations: Int,
    val gamesLeft: Int,
    val usesDivisions: Boolean,
)

object Odds {
    /** Prior weight, in games, pulling each team's average toward the league's. */
    private const val SHRINK_GAMES = 2.0
    private const val DEFAULT_SD = 25.0

    fun scoreModel(league: League): ScoreModel {
        val games = Analysis.teamWeeks(league)
        if (games.isEmpty()) return ScoreModel(league.teams.keys.associateWith { 100.0 }, DEFAULT_SD)
        val leagueMean = games.map { it.points }.average()
        val byTeam = games.groupBy { it.teamId }
        val means = league.teams.keys.associateWith { id ->
            val pts = byTeam[id].orEmpty().map { it.points }
            (pts.sum() + SHRINK_GAMES * leagueMean) / (pts.size + SHRINK_GAMES)
        }
        // Pooled spread around each team's own average.
        var ss = 0.0
        var dof = 0
        for ((_, g) in byTeam) {
            val m = g.map { it.points }.average()
            ss += g.sumOf { (it.points - m) * (it.points - m) }
            dof += g.size - 1
        }
        val sd = if (dof >= 3) sqrt(ss / dof) else DEFAULT_SD
        return ScoreModel(means, sd)
    }

    /**
     * Monte Carlo of the rest of the regular season, then seeding and the
     * playoff bracket. Scores are drawn from each team's (shrunk) average with
     * the league's week-to-week spread. Seeding is by record, then points for;
     * with divisions, division winners are seeded first. ESPN's exact
     * tiebreakers can differ, so close races are approximate.
     */
    fun playoffOdds(league: League, simulations: Int = 10_000, seed: Long = league.id * 31 + league.currentWeek): PlayoffOdds {
        val ids = league.teams.keys.sorted()
        val n = ids.size
        val index = ids.withIndex().associate { (i, id) -> id to i }
        val model = scoreModel(league)
        val mean = DoubleArray(n) { model.means.getValue(ids[it]) }

        val wins = DoubleArray(n)
        val pf = DoubleArray(n)
        val games = IntArray(n)
        val record = Array(n) { IntArray(3) }
        for (g in Analysis.teamWeeks(league)) {
            val i = index[g.teamId] ?: continue
            wins[i] += when (g.result) { 'W' -> 1.0; 'T' -> 0.5; else -> 0.0 }
            pf[i] += g.points
            games[i]++
            record[i][when (g.result) { 'W' -> 0; 'L' -> 1; else -> 2 }]++
        }
        val remaining = league.matchups
            .filter { !it.isPlayoff && !it.complete && it.awayId != null && it.homeId in index && it.awayId in index }
            .sortedBy { it.week }
        val home = IntArray(remaining.size) { index.getValue(remaining[it].homeId) }
        val away = IntArray(remaining.size) { index.getValue(remaining[it].awayId!!) }
        // Each team's next game, as an index into remaining.
        val nextGame = IntArray(n) { i -> remaining.indices.firstOrNull { home[it] == i || away[it] == i } ?: -1 }

        val spots = league.playoffTeamCount.coerceIn(1, n)
        val bracketSize = Integer.highestOneBit(spots).let { if (it == spots) it else it * 2 }
        val byes = bracketSize - spots
        val divisions = ids.map { league.teams.getValue(it).divisionId }
        val usesDivisions = divisions.distinct().size > 1 && divisions.distinct().size <= spots
        val playoffsStarted = league.matchups.any { it.isPlayoff && it.complete }

        val made = IntArray(n)
        val bye = IntArray(n)
        val champ = IntArray(n)
        val seedSum = LongArray(n)
        val winsSum = DoubleArray(n)
        val madeIfWin = IntArray(n)
        val countIfWin = IntArray(n)
        val madeIfLose = IntArray(n)
        val countIfLose = IntArray(n)

        val rng = Random(seed)
        val w = DoubleArray(n)
        val p = DoubleArray(n)
        val wonNext = BooleanArray(n)
        val order = bracketOrder(bracketSize)

        repeat(simulations) {
            wins.copyInto(w)
            pf.copyInto(p)
            for (g in remaining.indices) {
                val h = home[g]
                val a = away[g]
                val hs = mean[h] + model.sd * rng.gauss()
                val asc = mean[a] + model.sd * rng.gauss()
                p[h] += hs
                p[a] += asc
                when {
                    hs > asc -> w[h] += 1.0
                    asc > hs -> w[a] += 1.0
                    else -> { w[h] += 0.5; w[a] += 0.5 }
                }
                if (nextGame[h] == g) wonNext[h] = hs > asc
                if (nextGame[a] == g) wonNext[a] = asc > hs
            }

            val seeds = seedTeams(n, w, p, divisions, usesDivisions, spots)
            seeds.forEachIndexed { s, team ->
                made[team]++
                seedSum[team] += (s + 1).toLong()
                if (s < byes) bye[team]++
            }
            for (i in 0 until n) {
                winsSum[i] += w[i]
                if (nextGame[i] >= 0) {
                    val inPlayoffs = i in seeds
                    if (wonNext[i]) { countIfWin[i]++; if (inPlayoffs) madeIfWin[i]++ } else { countIfLose[i]++; if (inPlayoffs) madeIfLose[i]++ }
                }
            }
            if (!playoffsStarted) {
                // Fixed bracket, no reseeding: slots beyond the last seed are byes.
                var alive = order.map { s -> if (s <= spots) seeds[s - 1] else -1 }
                while (alive.size > 1) {
                    alive = alive.chunked(2).map { (x, y) ->
                        when {
                            x < 0 -> y
                            y < 0 -> x
                            mean[x] + model.sd * rng.gauss() >= mean[y] + model.sd * rng.gauss() -> x
                            else -> y
                        }
                    }
                }
                if (alive[0] >= 0) champ[alive[0]]++
            }
        }

        val sims = simulations.toDouble()
        val rows = ids.indices.map { i ->
            PlayoffOddsRow(
                teamId = ids[i],
                team = league.teamName(ids[i]),
                record = Record(record[i][0], record[i][1], record[i][2]),
                projectedWins = winsSum[i] / sims,
                playoffPct = made[i] / sims,
                byePct = bye[i] / sims,
                champPct = if (playoffsStarted) null else champ[i] / sims,
                avgSeed = if (made[i] > 0) seedSum[i].toDouble() / made[i] else Double.NaN,
                ifWinNext = if (countIfWin[i] > 0) madeIfWin[i].toDouble() / countIfWin[i] else null,
                ifLoseNext = if (countIfLose[i] > 0) madeIfLose[i].toDouble() / countIfLose[i] else null,
            )
        }.sortedWith(compareByDescending<PlayoffOddsRow> { it.playoffPct }.thenByDescending { it.champPct ?: 0.0 }.thenByDescending { it.projectedWins })
        return PlayoffOdds(rows, spots, byes, simulations, remaining.size, usesDivisions)
    }

    /** Team indices in seed order (index 0 = 1st seed), [spots] long. */
    private fun seedTeams(n: Int, w: DoubleArray, p: DoubleArray, divisions: List<Int>, usesDivisions: Boolean, spots: Int): List<Int> {
        val ranked = (0 until n).sortedWith(compareByDescending<Int> { w[it] }.thenByDescending { p[it] })
        if (!usesDivisions) return ranked.take(spots)
        val divisionWinners = ranked.distinctBy { divisions[it] }
        return (divisionWinners + ranked.filter { it !in divisionWinners }).take(spots)
    }

    /** Standard bracket order for [size] slots, e.g. 8 -> 1,8,4,5,2,7,3,6. */
    fun bracketOrder(size: Int): List<Int> {
        var order = listOf(1)
        while (order.size < size) {
            val m = order.size * 2
            order = order.flatMap { listOf(it, m + 1 - it) }
        }
        return order
    }

    private fun Random.gauss(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2 * ln(u1)) * cos(2 * Math.PI * nextDouble())
    }
}
