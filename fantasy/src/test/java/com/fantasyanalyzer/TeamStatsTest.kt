package com.fantasyanalyzer

import com.fantasyanalyzer.analysis.MatchupSide
import com.fantasyanalyzer.analysis.MatchupView
import com.fantasyanalyzer.analysis.Matchups
import com.fantasyanalyzer.analysis.Odds
import com.fantasyanalyzer.analysis.SlotRow
import com.fantasyanalyzer.analysis.TeamGame
import com.fantasyanalyzer.analysis.TeamStats
import com.fantasyanalyzer.data.League
import com.fantasyanalyzer.data.Matchup
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.RosterEntry
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamStatsTest {
    private val box = mapOf(
        1 to mapOf(
            1 to listOf(RosterEntry("QB A", "QB", 0, 20.0, listOf(0), 1, projected = 18.0),
                RosterEntry("WR A", "WR", 4, 10.0, listOf(4), 2, projected = 12.0),
                RosterEntry("Bench", "RB", 20, 30.0, listOf(2), 3, projected = 9.0)),
            2 to listOf(RosterEntry("QB B", "QB", 0, 15.0, listOf(0), 4, projected = 20.0)),
        ),
        2 to mapOf(
            1 to listOf(RosterEntry("QB A", "QB", 0, 25.0, listOf(0), 1, projected = 18.0),
                RosterEntry("WR A", "WR", 4, 14.0, listOf(4), 2, projected = 12.0)),
        ),
    )

    @Test
    fun vsProjectionAndPositions() {
        val rows = TeamStats.vsProjection(box) { "T$it" }
        val t1 = rows.first { it.teamId == 1 }
        assertEquals(69.0, t1.actual, 1e-9) // bench points don't count
        assertEquals(60.0, t1.projected, 1e-9)
        assertEquals(1, t1.weeksBeat) // week 1 was 30 on 30 projected: not a beat
        assertEquals(1, rows.indexOf(rows.first { it.teamId == 2 })) // worse per week, listed after
        val pos = TeamStats.pointsByPosition(box).getValue(1)
        assertEquals(45.0, pos.getValue("QB"), 1e-9)
        assertEquals(24.0, pos.getValue("WR"), 1e-9)
        assertNull(pos["RB"])
        val used = TeamStats.mostStarted(box, 1)
        assertEquals(listOf("QB A", "WR A"), used.map { it.name })
        assertEquals(2, used.first().starts)
    }

    @Test
    fun streaks() {
        fun g(w: Int, pts: Double) = TeamGame(w, 1, "A", pts, 2, "B", 100.0)
        val s = TeamStats.streaks(listOf(g(1, 110.0), g(2, 120.0), g(3, 130.0), g(4, 90.0), g(5, 80.0)))
        assertEquals("L2", s.currentText)
        assertEquals(3, s.longestWin)
        assertEquals(2, s.longestLoss)
    }

    @Test
    fun needsAndPositionEdge() {
        val live = NflGame(NflGame.IN, "Q3", "SEA", "DEN", 7, 3, 0.4)
        fun p(id: Long, pos: String, proj: Double, actual: Double?, game: NflGame?) =
            Player(id, "P$id", pos, "SEA", listOf(), "ACTIVE", proj, emptyList(), false, actual = actual, gameStart = 0, game = game)
        val done = NflGame(NflGame.POST, "Final", "SEA", "DEN", 7, 3, 0.0)
        val m = Matchup(5, 1, 2, 0.0, 0.0, "UNDECIDED", false)
        val v = MatchupView(
            m,
            MatchupSide(1, actual = 30.0, projected = 70.0),
            MatchupSide(2, actual = 50.0, projected = 90.0),
            0.3,
            listOf(
                SlotRow("QB", RosterSpot(p(1, "QB", 20.0, 22.0, done), 0), RosterSpot(p(4, "QB", 20.0, 30.0, done), 0)),
                SlotRow("WR", RosterSpot(p(2, "WR", 15.0, 8.0, live), 4), RosterSpot(p(5, "WR", 15.0, 20.0, live), 4)),
                SlotRow("WR", RosterSpot(p(3, "WR", 14.0, null, null).copy(gameStart = Long.MAX_VALUE), 4), null),
            ),
            emptyList(),
        )
        val n = Matchups.needs(v, 1000)!!
        assertEquals(60.0, n.points, 1e-9) // 90 projected for them minus 30 so far
        assertEquals(2, n.players) // the live WR and the one yet to play; the QB is done
        val edges = Matchups.positionEdges(v, 1000).associateBy { it.position }
        assertEquals(-8.0, edges.getValue("QB").edge, 1e-9)
        assertTrue(edges.getValue("WR").home > edges.getValue("WR").away) // 8+15*.4 + 14 vs 20+15*.4
        assertNull(Matchups.needs(v.copy(matchup = m.copy(winner = "HOME")), 1000)) // over: nothing to need
    }

    @Test
    fun magicWins() {
        val teams = (1..4).associateWith { Team(it, "T$it", "T$it", "") }
        val ms = listOf(
            Matchup(1, 1, 2, 150.0, 80.0, "HOME", false), Matchup(1, 3, 4, 100.0, 90.0, "HOME", false),
            Matchup(2, 1, 3, 0.0, 0.0, "UNDECIDED", false), Matchup(2, 2, 4, 0.0, 0.0, "UNDECIDED", false),
            Matchup(3, 1, 4, 0.0, 0.0, "UNDECIDED", false), Matchup(3, 2, 3, 0.0, 0.0, "UNDECIDED", false),
        )
        val league = League(9, 2026, "L", teams, ms, 2, 3, mapOf(0 to 1), playoffTeamCount = 2)
        val row = Odds.playoffOdds(league, simulations = 2000).rows.first { it.teamId == 1 }
        assertEquals(2, row.gamesLeft)
        assertEquals(3, row.byWinsLeft.size)
        assertEquals(1.0, row.byWinsLeft[2]!!, 1e-9) // 3-0 makes it in a 4-team, 2-spot league
        assertTrue(row.magicWins != null && row.magicWins!! <= 2)
    }
}
