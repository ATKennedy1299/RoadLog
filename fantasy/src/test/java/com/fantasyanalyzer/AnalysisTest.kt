package com.fantasyanalyzer

import com.fantasyanalyzer.analysis.Analysis
import com.fantasyanalyzer.data.EspnParser
import com.fantasyanalyzer.data.RosterEntry
import com.fantasyanalyzer.data.SampleData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AnalysisTest {

    private val demo = SampleData.generate(numTeams = 10, weeksPlayed = 6)

    @Test
    fun parsesLeagueJson() {
        val raw = JSONObject(
            """
            {"id": 99, "seasonId": 2026, "status": {"currentMatchupPeriod": 2},
             "settings": {"name": "Test League",
               "rosterSettings": {"lineupSlotCounts": {"0": 1, "2": 2, "20": 6, "5": 0}},
               "scheduleSettings": {"matchupPeriodCount": 14, "matchupPeriods": {"1": [1], "15": [15, 16]}}},
             "members": [{"id": "{G1}", "displayName": "alice"}],
             "teams": [{"id": 1, "name": "Alpha", "abbrev": "ALP", "owners": ["{G1}"]},
                       {"id": 2, "location": "Big", "nickname": "Dogs", "owners": []}],
             "schedule": [
               {"matchupPeriodId": 1, "home": {"teamId": 1, "totalPoints": 101.5},
                "away": {"teamId": 2, "totalPoints": 99.2}, "winner": "HOME", "playoffTierType": "NONE"},
               {"matchupPeriodId": 15, "home": {"teamId": 1, "totalPoints": 0},
                "winner": "UNDECIDED", "playoffTierType": "WINNERS_BRACKET"}]}
            """
        )
        val lg = EspnParser.parseLeague(raw)
        assertEquals("Test League", lg.name)
        assertEquals("alice", lg.teams.getValue(1).owner)
        assertEquals("Big Dogs", lg.teams.getValue(2).name)
        assertEquals(mapOf(0 to 1, 2 to 2, 20 to 6), lg.lineupSlotCounts)
        assertEquals(listOf(15, 16), lg.matchupPeriods[15])
        assertEquals(listOf(1), lg.completedWeeks())
        assertNull(lg.matchups[1].awayId)
        assertTrue(lg.matchups[1].isPlayoff)
    }

    @Test
    fun parsesBoxScoresPreferringWeeklyActuals() {
        val raw = JSONObject(
            """
            {"schedule": [{"home": {"teamId": 3, "rosterForCurrentScoringPeriod": {"entries": [
              {"lineupSlotId": 0, "playerPoolEntry": {"appliedStatTotal": 5.0, "player": {
                "fullName": "QB One", "defaultPositionId": 1, "eligibleSlots": [0, 7, 20],
                "stats": [{"scoringPeriodId": 4, "statSourceId": 1, "appliedTotal": 18.0},
                          {"scoringPeriodId": 4, "statSourceId": 0, "appliedTotal": 22.4}]}}},
              {"lineupSlotId": 20, "playerPoolEntry": {"appliedStatTotal": 7.5, "player": {
                "fullName": "RB Two", "defaultPositionId": 2, "eligibleSlots": [2, 23]}}}]}}}]}
            """
        )
        val roster = EspnParser.parseBoxScores(raw, 4).getValue(3)
        assertEquals(22.4, roster[0].points, 1e-9)
        assertEquals("QB", roster[0].position)
        assertEquals(7.5, roster[1].points, 1e-9)
        assertTrue(roster[0].started && !roster[1].started)
    }

    @Test
    fun standingsAreConsistent() {
        val st = Analysis.standings(demo.league)
        assertEquals(10, st.size)
        st.forEach { assertEquals(6, it.record.w + it.record.l + it.record.t) }
        assertEquals(st.sumOf { it.record.w }, st.sumOf { it.record.l })
        assertEquals(st.sumOf { it.pointsFor }, st.sumOf { it.pointsAgainst }, 1e-6)
        // every all-play win is someone else's all-play loss, so luck nets to zero
        assertEquals(0.0, st.sumOf { it.luck }, 1e-6)
        st.forEach { assertTrue(it.power in 0.0..100.0) }
        assertEquals((1..10).toList(), st.map { it.rank })
    }

    @Test
    fun allPlayHandComputed() {
        val raw = JSONObject(
            """
            {"teams": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}, {"id": 3, "name": "C"}, {"id": 4, "name": "D"}],
             "schedule": [
               {"matchupPeriodId": 1, "home": {"teamId": 1, "totalPoints": 150}, "away": {"teamId": 2, "totalPoints": 140}, "winner": "HOME"},
               {"matchupPeriodId": 1, "home": {"teamId": 3, "totalPoints": 90}, "away": {"teamId": 4, "totalPoints": 80}, "winner": "HOME"}]}
            """
        )
        val st = Analysis.standings(EspnParser.parseLeague(raw)).associateBy { it.team }
        assertEquals("2-1", st.getValue("B").allPlay.toString())
        assertEquals(1 - 1.0 / 3, st.getValue("C").luck, 1e-9) // won with the 3rd-best score
    }

    @Test
    fun scheduleSwapDiagonalIsRealRecord() {
        val st = Analysis.standings(demo.league).associateBy { it.teamId }
        val swap = Analysis.scheduleSwap(demo.league)
        swap.teamIds.forEachIndexed { i, id ->
            val r = st.getValue(id).record
            assertEquals(r.w + 0.5 * r.t, swap.cells[i][i], 1e-9)
        }
    }

    @Test
    fun headToHeadIsSymmetric() {
        val h2h = Analysis.headToHead(demo.league)
        for (i in h2h.teamIds.indices) for (j in h2h.teamIds.indices) {
            val a = h2h.cells[i][j] ?: continue
            val b = h2h.cells[j][i]!!
            assertEquals(a.w, b.l); assertEquals(a.l, b.w)
        }
    }

    private fun bruteForce(roster: List<RosterEntry>, counts: Map<Int, Int>): Double {
        val players = roster.filter { it.slotId != 21 }
        val choices = players.map { p -> listOf<Int?>(null) + p.eligibleSlots.filter { it in counts && it != 20 && it != 21 } }
        var best = 0.0
        fun go(i: Int, used: Map<Int, Int>, total: Double) {
            if (i == players.size) { best = maxOf(best, total); return }
            for (s in choices[i]) {
                if (s == null) go(i + 1, used, total)
                else if ((used[s] ?: 0) < counts.getValue(s)) go(i + 1, used + (s to (used[s] ?: 0) + 1), total + players[i].points)
            }
        }
        go(0, emptyMap(), 0.0)
        return best
    }

    @Test
    fun optimalLineupMatchesBruteForce() {
        val rng = Random(1)
        val counts = mapOf(0 to 1, 2 to 1, 4 to 1, 23 to 1, 7 to 1, 20 to 3) // QB, RB, WR, FLEX, OP
        val elig = mapOf("QB" to listOf(0, 7), "RB" to listOf(2, 23, 7), "WR" to listOf(4, 23, 7), "TE" to listOf(6, 23, 7))
        repeat(50) {
            val roster = (0 until 8).map { i ->
                val pos = elig.keys.random(rng)
                RosterEntry("p$i", pos, 20, (rng.nextDouble(0.0, 30.0) * 10).toLong() / 10.0, elig.getValue(pos))
            }
            val opt = Analysis.optimalLineup(roster, counts)
            assertEquals(bruteForce(roster, counts), opt.total, 1e-9)
            assertEquals(opt.total, opt.picks.sumOf { it.second.points }, 1e-9)
            assertTrue(opt.picks.size <= 5)
        }
    }

    @Test
    fun optimalLineupSkipsInjuredReserve() {
        val roster = listOf(
            RosterEntry("Hurt", "QB", 21, 40.0, listOf(0, 20, 21)),
            RosterEntry("Healthy", "QB", 20, 10.0, listOf(0, 20, 21)),
        )
        val opt = Analysis.optimalLineup(roster, mapOf(0 to 1, 20 to 1, 21 to 1))
        assertEquals(10.0, opt.total, 1e-9)
        assertEquals("QB", opt.picks.single().first)
    }

    @Test
    fun lineupEfficiencyOnDemo() {
        val eff = Analysis.lineupEfficiency(demo.league, demo.boxScores)
        assertEquals(60, eff.size)
        eff.forEach {
            assertTrue(it.optimal >= it.actual - 1e-9)
            assertTrue(it.efficiency in 0.0..1.0)
        }
        // starters' points add up to the team score in the schedule
        val games = Analysis.teamWeeks(demo.league).associateBy { it.week to it.teamId }
        eff.forEach { assertTrue(abs(games.getValue(it.week to it.teamId).points - it.actual) < 0.01) }
        val rec = Analysis.optimalRecord(demo.league, eff)
        assertEquals(30, rec.sumOf { it.actualWins })
        assertTrue(rec.sumOf { it.optimalWins } <= 30)
        assertEquals(10, Analysis.efficiencySummary(eff).size)
    }

    @Test
    fun notableGames() {
        val notable = Analysis.notableGames(demo.league).toMap()
        assertEquals(5, notable.getValue("Highest scores").size)
        assertTrue(notable.getValue("Biggest blowouts").all { it.margin >= 0 })
    }
}
