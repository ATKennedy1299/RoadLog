package com.fantasyanalyzer

import com.fantasyanalyzer.analysis.Odds
import com.fantasyanalyzer.analysis.Outlook
import com.fantasyanalyzer.data.EspnParser
import com.fantasyanalyzer.data.League
import com.fantasyanalyzer.data.Matchup
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.ProTeam
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.SampleData
import com.fantasyanalyzer.data.Team
import com.fantasyanalyzer.data.WeekOutlook
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlookTest {

    private fun p(
        id: Long, pos: String, proj: Double?, status: String = "ACTIVE", bye: Boolean = false,
        recent: List<Double> = emptyList(),
    ): Player {
        val slots = when (pos) {
            "QB" -> listOf(0, 7, 20, 21)
            "RB" -> listOf(2, 23, 7, 20, 21)
            "WR" -> listOf(4, 23, 7, 20, 21)
            else -> listOf(6, 23, 7, 20, 21)
        }
        return Player(id, "P$id", pos, "KC", slots, status, proj, recent, bye)
    }

    private val counts = mapOf(0 to 1, 2 to 1, 4 to 1, 23 to 1, 20 to 4, 21 to 1)

    @Test
    fun startSitSwapsInjuredAndByeStarters() {
        val roster = listOf(
            RosterSpot(p(1, "QB", 20.0), 0),
            RosterSpot(p(2, "RB", 15.0, bye = true), 2), // on bye
            RosterSpot(p(3, "WR", 12.0, status = "OUT"), 4), // ruled out
            RosterSpot(p(4, "RB", 9.0), 23),
            RosterSpot(p(5, "RB", 11.0), 20),
            RosterSpot(p(6, "WR", 8.0), 20),
            RosterSpot(p(7, "TE", 7.5), 20),
        )
        val a = Outlook.startSit(roster, counts)
        assertEquals(29.0, a.currentProjection, 1e-9) // 20 + 0 + 0 + 9
        assertEquals(20.0 + 11 + 8 + 9, a.bestProjection, 1e-9)
        assertEquals(setOf(5L, 6L), a.start.map { it.id }.toSet())
        assertEquals(setOf(2L, 3L), a.sit.map { it.id }.toSet())
        assertTrue(a.alerts.filter { it.severe }.map { it.player.id }.containsAll(listOf(2L, 3L)))
        assertEquals(0, a.emptySlots)
    }

    @Test
    fun startSitLeavesLockedPlayersAlone() {
        val roster = listOf(
            RosterSpot(p(1, "QB", 20.0), 0),
            RosterSpot(p(2, "RB", 3.0), 2, locked = true), // already played; can't move
            RosterSpot(p(3, "WR", 12.0), 4),
            RosterSpot(p(4, "RB", 9.0), 23),
            RosterSpot(p(5, "RB", 15.0), 20),
        )
        val a = Outlook.startSit(roster, counts)
        assertTrue(a.sit.none { it.id == 2L })
        assertEquals(listOf(5L), a.start.map { it.id }) // takes FLEX from the 9-pointer instead
        assertEquals(listOf(4L), a.sit.map { it.id })
        assertTrue(a.bestLineup.any { it.second.id == 2L && it.first == "RB" })
    }

    @Test
    fun countsEmptyStartingSlots() {
        val a = Outlook.startSit(listOf(RosterSpot(p(1, "QB", 20.0), 0), RosterSpot(p(2, "WR", 10.0), 20)), counts)
        assertEquals(3, a.emptySlots)
        assertEquals(listOf(2L), a.start.map { it.id })
    }

    @Test
    fun waiverUpgradesBeatTheWeakestAtPosition() {
        val outlook = WeekOutlook(
            week = 7,
            rosters = mapOf(1 to listOf(
                RosterSpot(p(1, "WR", 14.0), 4),
                RosterSpot(p(2, "WR", 4.0, recent = listOf(3.0, 5.0)), 20),
                RosterSpot(p(3, "RB", 12.0), 2),
                RosterSpot(p(4, "RB", 10.0, status = "OUT"), 23),
            )),
            freeAgents = listOf(
                p(10, "WR", 9.0, recent = listOf(12.0, 10.0)),
                p(11, "WR", 4.2),
                p(12, "RB", 8.0, bye = true, recent = listOf(9.0)),
                p(13, "TE", 6.0),
            ),
        )
        val r = Outlook.waivers(outlook, 1)
        val wr = r.upgrades.first { it.add.id == 10L }
        assertEquals(2L, wr.drop?.id)
        // P11 is barely better than P2: under the threshold.
        assertTrue(r.upgrades.none { it.add.id == 11L })
        // An injured-out RB is the weakest RB, so even a bye-week RB is an upgrade.
        assertEquals(4L, r.upgrades.first { it.add.id == 12L }.drop?.id)
        // Each drop is suggested once.
        assertEquals(r.upgrades.size, r.upgrades.map { it.drop?.id }.distinct().size)
        // The OUT starter needs covering this week; the bye-week free agent can't.
        val fill = r.fillIns.single()
        assertEquals(4L, fill.out.id)
        assertTrue(fill.options.none { it.id == 12L })
    }

    @Test
    fun parsesProjectionsInjuriesAndByes() {
        val raw = JSONObject(
            """
            {"schedule": [{"home": {"teamId": 2, "rosterForCurrentScoringPeriod": {"entries": [
              {"lineupSlotId": 2, "playerPoolEntry": {"id": 77, "lineupLocked": true, "player": {
                "id": 77, "fullName": "Run Ner", "defaultPositionId": 2, "eligibleSlots": [2, 23, 20],
                "proTeamId": 12, "injuryStatus": "QUESTIONABLE",
                "ownership": {"percentOwned": 88.5, "percentChange": -1.2},
                "stats": [
                  {"scoringPeriodId": 7, "statSourceId": 1, "statSplitTypeId": 1, "appliedTotal": 13.4},
                  {"scoringPeriodId": 6, "statSourceId": 0, "statSplitTypeId": 1, "appliedTotal": 20.0},
                  {"scoringPeriodId": 4, "statSourceId": 0, "statSplitTypeId": 1, "appliedTotal": 8.0},
                  {"scoringPeriodId": 2, "statSourceId": 0, "statSplitTypeId": 1, "appliedTotal": 30.0},
                  {"scoringPeriodId": 0, "statSourceId": 0, "statSplitTypeId": 0, "appliedTotal": 99.0}]}}},
              {"lineupSlotId": 20, "playerPoolEntry": {"id": 78, "player": {
                "id": 78, "fullName": "Bye Guy", "defaultPositionId": 3, "eligibleSlots": [4, 20], "proTeamId": 8,
                "stats": []}}}]}}}]}
            """
        )
        val pro = mapOf(12 to ProTeam("KC", 10), 8 to ProTeam("DET", 7))
        val roster = EspnParser.parseCurrentRosters(raw, 7, pro).getValue(2)
        val rb = roster[0].player
        assertEquals(13.4, rb.projected!!, 1e-9)
        assertEquals(listOf(8.0, 20.0), rb.recentPoints) // weeks 4-6 only, oldest first
        assertEquals("QUESTIONABLE", rb.injuryStatus)
        assertEquals("KC", rb.proTeam)
        assertEquals(88.5, rb.percentOwned, 1e-9)
        assertTrue(roster[0].locked)
        val bye = roster[1].player
        assertTrue(bye.onBye)
        assertNull(bye.projected)
        assertEquals(0.0, bye.expected, 1e-9)
        assertEquals("ACTIVE", bye.injuryStatus)
    }

    @Test
    fun parsesFreeAgentsSkippingRosteredPlayers() {
        val raw = JSONObject(
            """
            {"players": [
              {"id": 1, "status": "FREEAGENT", "player": {"id": 1, "fullName": "Free", "defaultPositionId": 3}},
              {"id": 2, "status": "WAIVERS", "player": {"id": 2, "fullName": "Claim", "defaultPositionId": 3}},
              {"id": 3, "status": "ONTEAM", "player": {"id": 3, "fullName": "Taken", "defaultPositionId": 3}}]}
            """
        )
        val fas = EspnParser.parseFreeAgents(raw, 7, emptyMap())
        assertEquals(listOf("Free", "Claim"), fas.map { it.name })
        assertFalse(fas[0].onWaivers)
        assertTrue(fas[1].onWaivers)
    }

    @Test
    fun bracketOrder() {
        assertEquals(listOf(1, 4, 2, 3), Odds.bracketOrder(4))
        assertEquals(listOf(1, 8, 4, 5, 2, 7, 3, 6), Odds.bracketOrder(8))
    }

    @Test
    fun playoffOddsAreConsistent() {
        val lg = SampleData.generate().league
        val odds = Odds.playoffOdds(lg, simulations = 4000)
        assertEquals(4, odds.playoffSpots)
        assertEquals(0, odds.byes)
        // Exactly playoffSpots teams make it each simulation, and one champion.
        assertEquals(4.0, odds.rows.sumOf { it.playoffPct }, 1e-9)
        assertEquals(1.0, odds.rows.sumOf { it.champPct!! }, 1e-9)
        odds.rows.forEach {
            assertTrue(it.champPct!! <= it.playoffPct + 1e-12)
            // Winning your next game can't hurt.
            if (it.ifWinNext != null && it.ifLoseNext != null) assertTrue(it.ifWinNext!! >= it.ifLoseNext!! - 0.05)
        }
        // Same seed, same answer.
        assertEquals(odds, Odds.playoffOdds(lg, simulations = 4000))
    }

    @Test
    fun clinchedAndEliminatedTeams() {
        // Four teams, two spots, one week left: A is 3-0 and can't drop below 2nd; D is 0-3 and can't reach 2nd.
        val teams = (1..4).associateWith { Team(it, "T$it", "T$it", "") }
        val played = listOf(
            Matchup(1, 1, 4, 120.0, 80.0, "HOME", false), Matchup(1, 2, 3, 100.0, 90.0, "HOME", false),
            Matchup(2, 1, 3, 110.0, 100.0, "HOME", false), Matchup(2, 2, 4, 100.0, 95.0, "HOME", false),
            Matchup(3, 1, 2, 105.0, 100.0, "HOME", false), Matchup(3, 3, 4, 100.0, 90.0, "HOME", false),
        )
        val left = listOf(Matchup(4, 1, 4, 0.0, 0.0, "UNDECIDED", false), Matchup(4, 2, 3, 0.0, 0.0, "UNDECIDED", false))
        val lg = League(1, 2026, "L", teams, played + left, 4, 4, emptyMap(), playoffTeamCount = 2)
        val odds = Odds.playoffOdds(lg, simulations = 2000).rows.associateBy { it.teamId }
        assertEquals(1.0, odds.getValue(1).playoffPct, 1e-9)
        assertEquals(0.0, odds.getValue(4).playoffPct, 1e-9)
        assertNotNull(odds.getValue(3).ifWinNext)
        assertEquals(0.0, odds.getValue(3).ifLoseNext!!, 1e-9) // loses -> 1-3, out
    }

    @Test
    fun demoOutlookWorksEndToEnd() {
        val data = SampleData.generate()
        val outlook = data.outlook!!
        assertEquals(1, data.league.teamOwnedBy(listOf(SampleData.DEMO_SWID)))
        val advice = Outlook.startSit(outlook.rosters.getValue(1), data.league.lineupSlotCounts)
        assertTrue(advice.bestProjection >= advice.currentProjection - 1e-9)
        val m = Outlook.matchup(data.league, outlook, 1)!!
        assertTrue(m.winProbability in 0.0..1.0)
        assertTrue(m.winProbabilityBest >= m.winProbability - 1e-9)
        Outlook.waivers(outlook, 1)
    }

    @Test
    fun normalCdf() {
        assertEquals(0.5, Outlook.normalCdf(0.0), 1e-7)
        assertEquals(0.841344746, Outlook.normalCdf(1.0), 1e-6)
        assertEquals(0.022750132, Outlook.normalCdf(-2.0), 1e-6)
    }

    @Test
    fun strengthOfScheduleRanksTougherOpponentsFirst() {
        // T1 scores big; whoever still has to play T1 has the hardest schedule.
        val teams = (1..4).associateWith { Team(it, "T$it", "T$it", "") }
        val played = listOf(
            Matchup(1, 1, 2, 150.0, 90.0, "HOME", false), Matchup(1, 3, 4, 100.0, 95.0, "HOME", false),
            Matchup(2, 1, 3, 140.0, 100.0, "HOME", false), Matchup(2, 2, 4, 92.0, 98.0, "AWAY", false),
        )
        val left = listOf(
            Matchup(3, 1, 4, 0.0, 0.0, "UNDECIDED", false), Matchup(3, 2, 3, 0.0, 0.0, "UNDECIDED", false),
            Matchup(4, 9, 9, 0.0, 0.0, "UNDECIDED", true), // playoffs: not part of the schedule
        )
        val lg = League(1, 2026, "L", teams, played + left, 3, 3, emptyMap())
        val sos = Odds.strengthOfSchedule(lg)
        assertEquals(4, sos.first().teamId)
        assertEquals(listOf(1), sos.first().opponents)
        assertTrue(sos.first().averageTeamWins < 0.5)
        val t1 = sos.single { it.teamId == 1 }
        assertTrue(t1.averageTeamWins > 0.5)
        assertEquals((1..4).toList(), sos.map { it.rank })
        assertNotNull(t1.pastOppAvg)
    }

    private val hour = 60 * 60 * 1000L

    private fun live(id: Long, pos: String, proj: Double, actual: Double?, kickoff: Long?) =
        p(id, pos, proj).copy(actual = actual, gameStart = kickoff)

    @Test
    fun kickoffLocksPlayersEvenWithoutEspnFlag() {
        val now = 100 * hour
        val played = RosterSpot(live(1, "RB", 10.0, 3.0, now - 5 * hour), 2) // game over, scored 3
        val upcoming = RosterSpot(live(2, "RB", 8.0, null, now + 2 * hour), 20)
        val noSchedule = RosterSpot(live(3, "WR", 9.0, 14.0, null), 4) // ESPN has points: it's started
        assertTrue(played.isLocked(now))
        assertFalse(upcoming.isLocked(now))
        assertTrue(noSchedule.isLocked(now))
        val a = Outlook.startSit(listOf(played, upcoming, noSchedule, RosterSpot(p(4, "QB", 20.0), 0)), counts, now)
        // The 3-point RB is locked in; the bench RB can only take the empty FLEX.
        assertTrue(a.sit.isEmpty())
        assertEquals(listOf(2L), a.start.map { it.id })
        assertEquals(3.0 + 14.0, a.currentActual, 1e-9)
        assertEquals(3.0 + 14.0 + 20.0, a.currentProjection, 1e-9)
        assertEquals(3.0 + 14.0 + 20.0 + 8.0, a.bestProjection, 1e-9)
    }

    @Test
    fun inProgressGameCountsPointsPlusRestOfProjection() {
        val start = 100 * hour
        val halfway = start + Player.GAME_LENGTH_MS / 2
        val pl = live(1, "WR", 10.0, 6.0, start)
        assertEquals(6.0 + 5.0, pl.expectedFinal(halfway), 1e-9)
        assertEquals(10.0, pl.expectedFinal(start - 1), 1e-9) // before kickoff: projection
        assertEquals(6.0, pl.expectedFinal(start + 10 * hour), 1e-9) // after: what they scored
    }

    @Test
    fun liveMatchupUsesActualPointsAndTightensWhenDone() {
        val teams = (1..2).associateWith { Team(it, "T$it", "T$it", "") }
        val week = listOf(Matchup(1, 1, 2, 0.0, 0.0, "UNDECIDED", false))
        val lg = League(1, 2026, "L", teams, week, 1, 1, mapOf(0 to 1, 2 to 1, 20 to 2))
        val kickoff = 100 * hour
        val done = kickoff + 10 * hour
        val outlook = WeekOutlook(1, mapOf(
            1 to listOf(RosterSpot(live(1, "QB", 20.0, 25.0, kickoff), 0), RosterSpot(live(2, "RB", 10.0, 12.0, kickoff), 2)),
            2 to listOf(RosterSpot(live(3, "QB", 22.0, 18.0, kickoff), 0), RosterSpot(live(4, "RB", 12.0, 10.0, kickoff), 2)),
        ), emptyList())
        val m = Outlook.matchup(lg, outlook, 1, done)!!
        assertTrue(m.started)
        assertEquals(37.0, m.myActual, 1e-9)
        assertEquals(28.0, m.opponentActual, 1e-9)
        assertEquals(37.0, m.myProjection, 1e-9)
        // Every game is over and I'm ahead: it's a win.
        assertEquals(1.0, m.winProbability, 1e-9)
        // Before kickoff the same matchup is a coin flip-ish projection game.
        val pre = Outlook.matchup(lg, outlook, 1, kickoff - hour)!!
        assertFalse(pre.started)
        assertEquals(0.0, pre.myActual, 1e-9)
        assertTrue(pre.winProbability in 0.05..0.95)
    }

    @Test
    fun parsesKickoffsFromProSchedule() {
        val raw = JSONObject(
            """
            {"settings": {"proTeams": [{"id": 12, "abbrev": "kc", "byeWeek": 10,
              "proGamesByScoringPeriod": {"7": [{"date": 1760900000000, "homeProTeamId": 12, "awayProTeamId": 8}]}}]}}
            """
        )
        val kc = EspnParser.parseProTeams(raw).getValue(12)
        assertEquals("KC", kc.abbrev)
        assertEquals(10, kc.byeWeek)
        assertEquals(1760900000000L, kc.kickoffs[7])
    }
}
