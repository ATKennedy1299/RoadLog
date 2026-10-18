package com.fantasyanalyzer.analysis

import com.fantasyanalyzer.data.BoxScores
import com.fantasyanalyzer.data.INACTIVE_SLOTS
import com.fantasyanalyzer.data.IR
import com.fantasyanalyzer.data.League
import com.fantasyanalyzer.data.RosterEntry
import com.fantasyanalyzer.data.slotName
import kotlin.math.sqrt

const val RECENT_WEEKS = 3

data class TeamGame(
    val week: Int,
    val teamId: Int,
    val team: String,
    val points: Double,
    val oppId: Int,
    val opponent: String,
    val oppPoints: Double,
) {
    val result: Char get() = when { points > oppPoints -> 'W'; points < oppPoints -> 'L'; else -> 'T' }
    val margin: Double get() = points - oppPoints
}

data class Record(val w: Int, val l: Int, val t: Int) {
    override fun toString() = if (t > 0) "$w-$l-$t" else "$w-$l"
}

data class StandingRow(
    val rank: Int,
    val teamId: Int,
    val team: String,
    val owner: String,
    val record: Record,
    val pointsFor: Double,
    val pointsAgainst: Double,
    val avg: Double,
    val stdDev: Double,
    val recentAvg: Double,
    val allPlay: Record,
    val allPlayPct: Double,
    /** Wins expected from all-play win rate. */
    val expectedWins: Double,
    /** Actual wins minus expected wins. */
    val luck: Double,
    val power: Double,
    val powerRank: Int,
)

data class EfficiencyRow(
    val week: Int,
    val teamId: Int,
    val team: String,
    val actual: Double,
    val optimal: Double,
    val topBenched: String,
) {
    val leftOnBench: Double get() = optimal - actual
    val efficiency: Double get() = if (optimal > 0) actual / optimal else 1.0
}

data class EfficiencySummary(
    val team: String,
    val actual: Double,
    val optimal: Double,
    val leftOnBench: Double,
    val efficiency: Double,
)

data class OptimalRecordRow(val team: String, val actualWins: Int, val optimalWins: Int) {
    val diff: Int get() = optimalWins - actualWins
}

data class OptimalLineup(val total: Double, val picks: List<Pair<String, RosterEntry>>)

/** Teams in a fixed order with a value for every (row, column) pair. */
data class Matrix<T>(val teamIds: List<Int>, val names: List<String>, val cells: List<List<T>>)

object Analysis {

    /** One row per team per completed game. */
    fun teamWeeks(league: League, includePlayoffs: Boolean = false): List<TeamGame> =
        league.matchups
            .filter { it.complete && it.awayId != null && (includePlayoffs || !it.isPlayoff) }
            .flatMap { m ->
                val away = m.awayId!!
                listOf(
                    TeamGame(m.week, m.homeId, league.teamName(m.homeId), m.homePoints, away, league.teamName(away), m.awayPoints),
                    TeamGame(m.week, away, league.teamName(away), m.awayPoints, m.homeId, league.teamName(m.homeId), m.homePoints),
                )
            }

    /** Record each team would have if it played every other team every week. */
    fun allPlay(games: List<TeamGame>): Map<Int, Record> {
        val acc = mutableMapOf<Int, IntArray>()
        for ((_, wk) in games.groupBy { it.week }) {
            for (g in wk) {
                val wins = wk.count { it.points < g.points }
                val ties = wk.count { it.points == g.points } - 1
                val losses = wk.size - 1 - wins - ties
                val a = acc.getOrPut(g.teamId) { IntArray(3) }
                a[0] += wins; a[1] += losses; a[2] += ties
            }
        }
        return acc.mapValues { (_, a) -> Record(a[0], a[1], a[2]) }
    }

    /**
     * Standings plus all-play record, luck, and a power ranking.
     *
     * Luck = actual wins minus the wins expected from all-play win rate. A team
     * that keeps posting the week's 2nd-best score but drawing the top scorer is
     * unlucky; one that wins with the 9th-best score is lucky.
     */
    fun standings(league: League): List<StandingRow> {
        val games = teamWeeks(league)
        if (games.isEmpty()) return emptyList()
        val ap = allPlay(games)
        val recentWeeks = games.map { it.week }.distinct().sorted().takeLast(RECENT_WEEKS).toSet()

        data class Base(val id: Int, val g: List<TeamGame>) {
            val pts = g.map { it.points }
            val avg = pts.average()
            val recent = g.filter { it.week in recentWeeks }.map { it.points }.average().let { if (it.isNaN()) avg else it }
        }
        val bases = games.groupBy { it.teamId }.map { (id, g) -> Base(id, g) }
        val avgNorm = minMax(bases.associate { it.id to it.avg })
        val recentNorm = minMax(bases.associate { it.id to it.recent })

        val rows = bases.map { b ->
            val w = b.g.count { it.result == 'W' }
            val l = b.g.count { it.result == 'L' }
            val t = b.g.count { it.result == 'T' }
            val apr = ap.getValue(b.id)
            val apGames = apr.w + apr.l + apr.t
            val apPct = if (apGames > 0) (apr.w + 0.5 * apr.t) / apGames else 0.0
            val xW = apPct * b.g.size
            val team = league.teams[b.id]
            StandingRow(
                rank = 0, teamId = b.id, team = league.teamName(b.id), owner = team?.owner ?: "",
                record = Record(w, l, t), pointsFor = b.pts.sum(), pointsAgainst = b.g.sumOf { it.oppPoints },
                avg = b.avg, stdDev = sampleStdDev(b.pts), recentAvg = b.recent,
                allPlay = apr, allPlayPct = apPct, expectedWins = xW, luck = (w + 0.5 * t) - xW,
                power = 100 * (0.5 * apPct + 0.3 * avgNorm.getValue(b.id) + 0.2 * recentNorm.getValue(b.id)),
                powerRank = 0,
            )
        }
        val sorted = rows.sortedWith(
            compareByDescending<StandingRow> { it.record.w }.thenByDescending { it.record.t }.thenByDescending { it.pointsFor }
        )
        return sorted.mapIndexed { i, r ->
            r.copy(rank = i + 1, powerRank = 1 + rows.count { it.power > r.power })
        }
    }

    /** Actual head-to-head records, row team vs. column team (playoffs included). */
    fun headToHead(league: League): Matrix<Record?> {
        val games = teamWeeks(league, includePlayoffs = true)
        val ids = sortedTeamIds(league)
        val byPair = games.groupBy { it.teamId to it.oppId }
        val cells = ids.map { a ->
            ids.map { b ->
                if (a == b) null else byPair[a to b]?.let { g ->
                    Record(g.count { it.result == 'W' }, g.count { it.result == 'L' }, g.count { it.result == 'T' })
                } ?: Record(0, 0, 0)
            }
        }
        return Matrix(ids, ids.map { league.teamName(it) }, cells)
    }

    /**
     * Wins each team (row) would have with another team's (column) schedule.
     * When the row team appears on the borrowed schedule, it plays the
     * schedule's owner instead, the usual convention for this table.
     */
    fun scheduleSwap(league: League): Matrix<Double> {
        val games = teamWeeks(league)
        val score = games.associate { (it.week to it.teamId) to it.points }
        val opp = games.associate { (it.week to it.teamId) to it.oppId }
        val weeks = games.map { it.week }.distinct().sorted()
        val ids = sortedTeamIds(league)
        val cells = ids.map { a ->
            ids.map { b ->
                var wins = 0.0
                for (w in weeks) {
                    val mine = score[w to a] ?: continue
                    var o = opp[w to b] ?: continue
                    if (o == a) o = b
                    if (o == a) continue
                    val theirs = score[w to o] ?: continue
                    wins += when { mine > theirs -> 1.0; mine == theirs -> 0.5; else -> 0.0 }
                }
                wins
            }
        }
        return Matrix(ids, ids.map { league.teamName(it) }, cells)
    }

    fun notableGames(league: League): List<Pair<String, List<TeamGame>>> {
        val games = teamWeeks(league, includePlayoffs = true)
        if (games.isEmpty()) return emptyList()
        // One row per game, from the winner's side.
        val winners = games.filter { it.margin > 0 || (it.margin == 0.0 && it.teamId < it.oppId) }
        return listOf(
            "Highest scores" to games.sortedByDescending { it.points }.take(5),
            "Lowest scores" to games.sortedBy { it.points }.take(5),
            "Biggest blowouts" to winners.sortedByDescending { it.margin }.take(5),
            "Closest games" to winners.sortedBy { it.margin }.take(5),
            "Most points in a loss" to games.filter { it.result == 'L' }.sortedByDescending { it.points }.take(5),
        )
    }

    // --- Lineup efficiency ---------------------------------------------------

    /**
     * Exact best possible starting lineup for one team-week.
     *
     * Dynamic program over players with the remaining count of every starting
     * slot as state. Rosters are ~16 players and ~7 slot types, so this is tiny,
     * and unlike a greedy fill it handles overlapping FLEX/OP/RB-WR slots.
     */
    fun optimalLineup(roster: List<RosterEntry>, slotCounts: Map<Int, Int>): OptimalLineup {
        val slotIds = slotCounts.filter { (s, c) -> c > 0 && s !in INACTIVE_SLOTS }.keys.sorted()
        require(slotIds.size <= 14 && slotIds.all { slotCounts.getValue(it) <= 15 }) { "Unsupported lineup settings" }
        val players = roster.filter { it.slotId != IR } // players stashed on IR couldn't have started
        val options = players.map { p -> slotIds.indices.filter { slotIds[it] in p.eligibleSlots } }

        // Remaining slot counts packed 4 bits per slot type (<= 56 bits, so state * 64 + i fits a Long).
        fun count(state: Long, k: Int) = ((state shr (4 * k)) and 0xF).toInt()
        val start = slotIds.foldIndexed(0L) { k, acc, s -> acc or (slotCounts.getValue(s).toLong() shl (4 * k)) }

        val memo = HashMap<Long, Double>()
        fun best(i: Int, state: Long): Double {
            if (i == players.size || state == 0L) return 0.0
            val key = state * 64 + i
            memo[key]?.let { return it }
            var top = best(i + 1, state)
            for (k in options[i]) {
                if (count(state, k) > 0) {
                    top = maxOf(top, players[i].points + best(i + 1, state - (1L shl (4 * k))))
                }
            }
            memo[key] = top
            return top
        }

        val total = best(0, start)
        // Walk the memo forward to recover which player went where.
        val picks = mutableListOf<Pair<String, RosterEntry>>()
        var state = start
        for (i in players.indices) {
            if (state == 0L) break
            val skip = best(i + 1, state)
            val here = best(i, state)
            if (here == skip) continue
            val k = options[i].first { k ->
                count(state, k) > 0 && players[i].points + best(i + 1, state - (1L shl (4 * k))) == here
            }
            picks += slotName(slotIds[k]) to players[i]
            state -= 1L shl (4 * k)
        }
        return OptimalLineup(total, picks)
    }

    fun lineupEfficiency(league: League, box: BoxScores): List<EfficiencyRow> =
        box.toSortedMap().flatMap { (week, rosters) ->
            rosters.map { (tid, roster) ->
                val actual = roster.filter { it.started }.sumOf { it.points }
                val opt = optimalLineup(roster, league.lineupSlotCounts)
                val shouldStart = opt.picks.map { it.second }.toSet()
                val benchedBest = roster.filter { !it.started && it in shouldStart }.maxByOrNull { it.points }
                EfficiencyRow(
                    week, tid, league.teamName(tid), actual, maxOf(opt.total, actual),
                    benchedBest?.let { "${it.name} (${fmt1(it.points)})" } ?: "",
                )
            }
        }

    fun efficiencySummary(rows: List<EfficiencyRow>): List<EfficiencySummary> =
        rows.groupBy { it.team }.map { (team, r) ->
            EfficiencySummary(team, r.sumOf { it.actual }, r.sumOf { it.optimal }, r.sumOf { it.leftOnBench },
                r.map { it.efficiency }.average())
        }.sortedByDescending { it.efficiency }

    /** What each team's record would be if both sides had set their best lineup. */
    fun optimalRecord(league: League, eff: List<EfficiencyRow>): List<OptimalRecordRow> {
        val opt = eff.associate { (it.week to it.teamId) to it.optimal }
        return teamWeeks(league)
            .filter { (it.week to it.teamId) in opt && (it.week to it.oppId) in opt }
            .groupBy { it.team }
            .map { (team, g) ->
                OptimalRecordRow(team, g.count { it.result == 'W' },
                    g.count { opt.getValue(it.week to it.teamId) > opt.getValue(it.week to it.oppId) })
            }
            .sortedByDescending { it.diff }
    }

    private fun sortedTeamIds(league: League) = league.teams.keys.sortedBy { league.teamName(it) }

    private fun minMax(values: Map<Int, Double>): Map<Int, Double> {
        val lo = values.values.min()
        val span = values.values.max() - lo
        return values.mapValues { (_, v) -> if (span > 0) (v - lo) / span else 0.5 }
    }

    private fun sampleStdDev(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val mean = xs.average()
        return sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
    }
}

fun fmt1(d: Double): String = String.format(java.util.Locale.US, "%.1f", d)
