package com.fantasyanalyzer.data

import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

/** Generates a believable fake league, for demo mode and tests. */
object SampleData {

    private val TEAM_NAMES = listOf(
        "Gridiron Gurus", "Touchdown Tyrants", "Fourth & Long", "Blitz Brigade",
        "End Zone Elite", "Hail Mary Heroes", "Pigskin Prophets", "Red Zone Raiders",
        "Sunday Scaries", "The Waiver Wire", "Bye Week Blues", "Taco's Corner",
    )
    private val SLOT_COUNTS = mapOf(0 to 1, 2 to 2, 4 to 2, 6 to 1, 23 to 1, 16 to 1, 17 to 1, 20 to 7, 21 to 1)

    private class Pos(val id: Int, val slots: List<Int>, val mean: Double, val sd: Double, val count: Int, val starters: List<Int>)
    private val POS = listOf(
        Pos(1, listOf(0, 7, 20, 21), 18.0, 7.0, 2, listOf(0)),
        Pos(2, listOf(2, 3, 23, 7, 20, 21), 11.0, 7.0, 5, listOf(2, 2)),
        Pos(3, listOf(4, 3, 5, 23, 7, 20, 21), 11.0, 7.0, 5, listOf(4, 4)),
        Pos(4, listOf(6, 5, 23, 7, 20, 21), 8.0, 5.0, 2, listOf(6)),
        Pos(16, listOf(16, 20, 21), 7.0, 5.0, 1, listOf(16)),
        Pos(5, listOf(17, 20, 21), 8.0, 4.0, 1, listOf(17)),
    )

    private class Player(val name: String, val pos: Pos, val mean: Double)

    private fun Random.gauss(mean: Double, sd: Double): Double {
        // Box-Muller
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return mean + sd * kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    private fun round1(d: Double) = (d * 10).roundToLong() / 10.0
    private fun round2(d: Double) = (d * 100).roundToLong() / 100.0

    fun generate(numTeams: Int = 10, weeksPlayed: Int = 6, regWeeks: Int = 14, seed: Int = 7): LeagueData {
        val rng = Random(seed)
        val ids = (1..numTeams).toList()
        var pid = 1000
        val rosters = ids.associateWith {
            val skill = rng.nextDouble(0.85, 1.15)
            POS.flatMap { p ->
                (0 until p.count).map { n ->
                    pid++
                    Player("Player $pid", p, p.mean * skill * rng.nextDouble(0.6, 1.3) * (if (n < 2) 1.0 else 0.8))
                }
            }
        }
        val managerSkill = ids.associateWith { rng.nextDouble(0.6, 1.4) }

        fun weekRoster(players: List<Player>, skill: Double): List<RosterEntry> {
            val pts = players.associateWith { max(0.0, round1(rng.gauss(it.mean, it.pos.sd))) }
            // Managers start by expectation plus noise; better managers have less noise.
            val guess = players.associateWith { it.mean + rng.gauss(0.0, 4 * (1.5 - skill)) }
            val ranked = players.sortedByDescending { guess.getValue(it) }
            val assigned = mutableMapOf<Player, Int>()
            for (p in POS) for (slot in p.starters) {
                assigned[ranked.first { it.pos === p && it !in assigned }] = slot
            }
            assigned[ranked.first { it.pos.id in setOf(2, 3, 4) && it !in assigned }] = 23
            return players.map {
                RosterEntry(it.name, POSITIONS.getValue(it.pos.id), assigned[it] ?: BENCH, pts.getValue(it), it.pos.slots)
            }
        }

        val matchups = mutableListOf<Matchup>()
        val box = mutableMapOf<Int, Map<Int, List<RosterEntry>>>()
        for (week in 1..regWeeks) {
            val weekBox = mutableMapOf<Int, List<RosterEntry>>()
            for ((h, a) in pairings(ids, week)) {
                if (week <= weeksPlayed) {
                    val he = weekRoster(rosters.getValue(h), managerSkill.getValue(h))
                    val ae = weekRoster(rosters.getValue(a), managerSkill.getValue(a))
                    val hp = round2(he.filter { it.started }.sumOf { it.points })
                    val ap = round2(ae.filter { it.started }.sumOf { it.points })
                    weekBox[h] = he; weekBox[a] = ae
                    val winner = when { hp > ap -> "HOME"; ap > hp -> "AWAY"; else -> "TIE" }
                    matchups += Matchup(week, h, a, hp, ap, winner, false)
                } else {
                    matchups += Matchup(week, h, a, 0.0, 0.0, "UNDECIDED", false)
                }
            }
            if (weekBox.isNotEmpty()) box[week] = weekBox
        }

        val league = League(
            id = 123456, year = 2026, name = "Demo League (sample data)",
            teams = ids.associateWith { Team(it, TEAM_NAMES[it - 1], TEAM_NAMES[it - 1].take(4).uppercase(), "owner$it") },
            matchups = matchups, currentWeek = weeksPlayed + 1, regularSeasonWeeks = regWeeks,
            lineupSlotCounts = SLOT_COUNTS, matchupPeriods = (1..regWeeks).associateWith { listOf(it) },
        )
        return LeagueData(league, box)
    }

    /** Round-robin circle method. */
    private fun pairings(ids: List<Int>, week: Int): List<Pair<Int, Int>> {
        val n = ids.size
        val rest = ids.drop(1)
        val rot = (week - 1) % (n - 1)
        val order = listOf(ids[0]) + rest.takeLast(rot) + rest.dropLast(rot)
        return (0 until n / 2).map { order[it] to order[n - 1 - it] }
    }
}
