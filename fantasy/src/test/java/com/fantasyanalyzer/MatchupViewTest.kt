package com.fantasyanalyzer

import com.fantasyanalyzer.analysis.Matchups
import com.fantasyanalyzer.data.League
import com.fantasyanalyzer.data.Matchup
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.RosterEntry
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.SampleData
import com.fantasyanalyzer.data.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchupViewTest {
    private val hour = 60 * 60 * 1000L
    private val teams = (1..4).associateWith { Team(it, "T$it", "T$it", "") }
    private val counts = mapOf(0 to 1, 2 to 2, 23 to 1, 20 to 3)

    private fun spot(id: Long, pos: String, slot: Int, proj: Double, actual: Double? = null, kickoff: Long? = null) =
        RosterSpot(Player(id, "P$id", pos, "KC", emptyList(), "ACTIVE", proj, emptyList(), false, actual = actual, gameStart = kickoff), slot)

    @Test
    fun finishedMatchupUsesOfficialScoresAndPregameProjections() {
        val m = Matchup(1, 1, 2, 101.5, 99.0, "HOME", false)
        val lg = League(1, 2026, "L", teams, listOf(m), 2, 14, counts)
        val rosters = mapOf(
            1 to listOf(spot(1, "QB", 0, 20.0, 25.0, 0), spot(2, "RB", 2, 10.0, 8.0, 0)),
            2 to listOf(spot(3, "QB", 0, 18.0, 30.0, 0), spot(4, "RB", 20, 9.0, 12.0, 0)),
        )
        val v = Matchups.view(lg, m, rosters, now = 100 * hour)
        assertEquals(101.5, v.home.actual, 1e-9) // official, not the sum of what we have
        assertEquals(30.0, v.home.projected!!, 1e-9) // pre-game projections of starters
        assertEquals(1.0, v.homeWinProbability!!, 1e-9)
        // QB, RB, RB, FLEX rows in ESPN's order; away's RB is benched, so its RB rows are empty.
        assertEquals(listOf("QB", "RB", "RB", "FLEX"), v.starters.map { it.slot })
        assertEquals(1L, v.starters[0].home!!.player.id)
        assertNull(v.starters[1].away)
        assertEquals(4L, v.bench.single().away!!.player.id)
    }

    @Test
    fun liveMatchupSumsPointsSoFar() {
        val m = Matchup(3, 1, 2, 0.0, 0.0, "UNDECIDED", false)
        val lg = League(1, 2026, "L", teams, listOf(m), 3, 14, counts)
        val kickoff = 100 * hour
        val rosters = mapOf(
            1 to listOf(spot(1, "QB", 0, 20.0, 12.0, kickoff), spot(2, "RB", 2, 10.0, null, kickoff + 48 * hour)),
            2 to listOf(spot(3, "QB", 0, 18.0, null, kickoff + 48 * hour)),
        )
        val v = Matchups.view(lg, m, rosters, now = kickoff + 10 * hour)
        assertEquals(12.0, v.home.actual, 1e-9)
        assertEquals(22.0, v.home.projected!!, 1e-9) // 12 final + 10 still to play
        assertEquals(0.0, v.away!!.actual, 1e-9)
        val p = v.homeWinProbability!!
        assertTrue(p > 0.5 && p < 1.0)
    }

    @Test
    fun noLineupsStillShowsScores() {
        val m = Matchup(9, 3, 4, 0.0, 0.0, "UNDECIDED", false)
        val lg = League(1, 2026, "L", teams, listOf(m), 3, 14, counts)
        val v = Matchups.view(lg, m, null)
        assertNull(v.homeWinProbability)
        assertNull(v.home.projected)
        assertTrue(v.starters.all { it.home == null && it.away == null })
    }

    @Test
    fun myMatchupComesFirst() {
        val lg = SampleData.generate().league
        val week = Matchups.inWeek(lg, 3, myTeam = 7)
        assertTrue(week.first().homeId == 7 || week.first().awayId == 7)
        assertEquals(5, week.size)
    }

    @Test
    fun boxScoreEntriesBecomeFinalSpots() {
        val s = RosterEntry("A B", "WR", 4, 17.5, listOf(4, 20), playerId = 9, projected = 11.0).toSpot()
        assertTrue(s.player.gameStarted(0))
        assertEquals(17.5, s.player.pointsSoFar(0), 1e-9)
        assertEquals(17.5, s.player.expectedFinal(0), 1e-9)
    }
}
