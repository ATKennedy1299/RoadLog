package com.fantasyanalyzer

import com.fantasyanalyzer.data.AlertEngine
import com.fantasyanalyzer.data.AlertSettings
import com.fantasyanalyzer.data.AlertSnapshot
import com.fantasyanalyzer.data.League
import com.fantasyanalyzer.data.Matchup
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private val hour = 60 * 60 * 1000L
    private val teams = mapOf(1 to Team(1, "Mine", "ME", ""), 2 to Team(2, "Rival", "RV", ""))
    private val league = League(1, 2026, "L", teams, listOf(Matchup(3, 1, 2, 0.0, 0.0, "UNDECIDED", false)), 3, 14,
        mapOf(0 to 1, 4 to 1, 20 to 3), currentScoringPeriod = 3)

    private fun live(remaining: Double) = NflGame(NflGame.IN, "Q2", "SEA", "DEN", 7, 3, remaining)

    private fun rosters(myWr: Double, oppQb: Double, done: Boolean = false, status: String = "ACTIVE") = mapOf(
        1 to listOf(
            RosterSpot(Player(10, "Jaxon Smith-Njigba", "WR", "SEA", listOf(4, 20), status, 15.0, emptyList(), false,
                actual = myWr, gameStart = 0, game = live(if (done) 0.0 else 0.5)), 4),
        ),
        2 to listOf(
            RosterSpot(Player(20, "Bo Nix", "QB", "DEN", listOf(0, 20), "ACTIVE", 18.0, emptyList(), false,
                actual = oppQb, gameStart = 0, game = live(if (done) 0.0 else 0.5)), 0),
        ),
    )

    private fun step(prev: AlertSnapshot?, r: Map<Int, List<RosterSpot>>, s: AlertSettings, now: Long = 10 * hour) =
        AlertEngine.alerts(prev, AlertEngine.snapshot(league, r, 1, now)!!, s, league, r, 1, now)

    private val on = AlertSettings(enabled = true)

    @Test
    fun scoringPlayWithMatchupLine() {
        val (first, snap) = step(null, rosters(4.0, 5.0), on)
        assertTrue(first.isEmpty()) // nothing to compare on the first look
        val (alerts, _) = step(snap, rosters(10.5, 5.0), on)
        val a = alerts.single()
        assertEquals("J. Smith-Njigba +6.5", a.title)
        assertTrue(a.text, a.text.startsWith("You 10.5 – 5.0 Rival"))
        assertTrue(a.text.contains("to win"))
    }

    @Test
    fun smallGainsAndOpponentScoringAreQuietByDefault() {
        val (_, snap) = step(null, rosters(4.0, 5.0), on)
        assertTrue(step(snap, rosters(6.0, 5.0), on).first.isEmpty()) // +2 is under the threshold
        assertTrue(step(snap, rosters(4.0, 11.0), on).first.isEmpty()) // opponent's TD
        val withOpp = on.copy(includeOpponent = true)
        assertEquals("Opp: B. Nix +6.0", step(snap, rosters(4.0, 11.0), withOpp).first.single().title)
    }

    @Test
    fun leadChangeWinSwingAndFinal() {
        val s = on.copy(playerScored = false, leadChange = true, winSwing = true, finalResult = true)
        val (_, snap) = step(null, rosters(4.0, 9.0), s)
        val (alerts, snap2) = step(snap, rosters(12.0, 9.0), s)
        assertTrue(alerts.any { it.title == "You took the lead" })
        assertTrue(alerts.any { it.title.startsWith("You're now favored") })
        val (end, _) = step(snap2, rosters(12.0, 9.0, done = true), s)
        assertEquals("Final: You won", end.single().title)
    }

    @Test
    fun redZoneOnlyWhenAskedFor() {
        val rz = NflGame(NflGame.IN, "Q2", "SEA", "DEN", 7, 3, 0.5, possession = "SEA", redZone = true)
        val inRz = rosters(4.0, 5.0).mapValues { (_, r) -> r.map { it.copy(player = it.player.copy(game = rz)) } }
        val (_, snap) = step(null, rosters(4.0, 5.0), on)
        assertTrue(step(snap, inRz, on).first.isEmpty())
        val alert = step(snap, inRz, on.copy(redZone = true)).first.single()
        assertEquals("Jaxon Smith-Njigba is in the red zone", alert.title)
    }

    @Test
    fun lineupReminderOncePerKickoff() {
        val kickoff = 10 * hour
        val now = kickoff - hour / 2
        val out = mapOf(1 to listOf(RosterSpot(Player(10, "A B", "WR", "SEA", listOf(4, 20), "OUT", 15.0, emptyList(), false,
            gameStart = kickoff), 4)), 2 to rosters(0.0, 0.0).getValue(2))
        val s = on.copy(lineupReminder = true)
        val (first, snap) = step(null, out, s, now)
        assertEquals("Check your lineup before kickoff", first.single().title)
        assertTrue(kickoff in snap.reminded)
        assertTrue(step(snap, out, s, now + 60_000).first.none { it.title.startsWith("Check") })
    }

    @Test
    fun snapshotSurvivesJson() {
        val (_, snap) = step(null, rosters(4.0, 5.0), on)
        val back = AlertSnapshot.fromJson(snap.toJson())
        assertNotNull(back)
        assertEquals(snap, back)
        assertEquals(null, AlertSnapshot.fromJson("not json"))
    }
}
