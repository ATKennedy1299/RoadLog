package com.fantasyanalyzer

import com.fantasyanalyzer.analysis.EfficiencyRow
import com.fantasyanalyzer.analysis.TeamGame
import com.fantasyanalyzer.analysis.WeeklyRecap
import com.fantasyanalyzer.data.RosterEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyRecapTest {
    private fun game(id: Int, name: String, pts: Double, opp: Int, oppName: String, oppPts: Double) =
        listOf(TeamGame(5, id, name, pts, opp, oppName, oppPts), TeamGame(5, opp, oppName, oppPts, id, name, pts))

    @Test
    fun headlines() {
        val games = game(1, "Castle Bravo", 151.2, 2, "The Hogs", 88.0) + game(3, "Who Dey", 101.5, 4, "Rival", 100.9)
        val rosters = mapOf(
            1 to listOf(RosterEntry("Ja'Marr Chase", "WR", 4, 38.4, listOf(4)), RosterEntry("Bench Guy", "RB", 20, 45.0, listOf(2))),
            3 to listOf(RosterEntry("Bo Nix", "QB", 0, 22.0, listOf(0))),
        )
        val eff = listOf(EfficiencyRow(5, 4, "Rival", 100.9, 121.0, "Bench Guy"))
        val lines = WeeklyRecap.lines(games, rosters, { if (it == 1) "Castle Bravo" else "Team $it" }, eff).associateBy { it.label }
        assertEquals("Castle Bravo", lines.getValue("High score").headline)
        assertEquals("The Hogs", lines.getValue("Low score").headline)
        assertEquals("Castle Bravo over The Hogs", lines.getValue("Biggest blowout").headline)
        assertEquals("Who Dey over Rival", lines.getValue("Closest game").headline)
        assertEquals("101.5–100.9, by 0.6", lines.getValue("Closest game").detail)
        assertEquals("Ja'Marr Chase (WR)", lines.getValue("Top player").headline) // bench points don't count
        assertEquals("Rival", lines.getValue("Bench blunder").headline)
    }
}
