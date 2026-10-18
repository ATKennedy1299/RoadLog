package com.fantasyanalyzer

import com.fantasyanalyzer.data.EspnClient
import com.fantasyanalyzer.data.EspnException
import com.fantasyanalyzer.data.EspnParser
import com.fantasyanalyzer.data.FieldStatus
import com.fantasyanalyzer.data.FileJsonCache
import com.fantasyanalyzer.data.JsonCache
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.ProGame
import com.fantasyanalyzer.data.ProTeam
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.photoUrl
import com.fantasyanalyzer.data.withGames
import com.fantasyanalyzer.data.ScoreBreakdown
import com.fantasyanalyzer.data.ScoringRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class CacheAndLiveTest {

    private class MemoryCache : JsonCache {
        val map = mutableMapOf<String, String>()
        override fun read(key: String, maxAgeMs: Long) = map[key]
        override fun write(key: String, json: String) { map[key] = json }
        override fun savedAt(key: String): Long? = if (key in map) 42L else null
        override fun clear() = map.clear()
    }

    @Test
    fun fileCacheRoundTripsAndExpires() {
        var now = 1_000_000L
        val dir = Files.createTempDirectory("cache").toFile()
        val cache = FileJsonCache(dir) { now }
        assertNull(cache.read("league"))
        cache.write("league", """{"a": 1}""")
        assertEquals("""{"a": 1}""", cache.read("league"))
        val saved = cache.savedAt("league")!!
        now = saved + 10_000
        assertNull(cache.read("league", maxAgeMs = 5_000))
        assertEquals("""{"a": 1}""", cache.read("league", maxAgeMs = 60_000))
        cache.write("weird/key:1", "{}") // keys become safe file names
        assertEquals("{}", cache.read("weird/key:1"))
        cache.clear()
        assertFalse(dir.exists())
    }

    @Test
    fun offlineClientReadsOnlyTheCache() {
        val cache = MemoryCache()
        val client = EspnClient(1, 2026, cache = cache, offline = true, baseUrl = "http://127.0.0.1:9") // nothing listens there
        try {
            client.league()
            fail("expected a cache miss")
        } catch (e: EspnException) {
            assertTrue(e.message!!.contains("league"))
        }
        cache.write("league", """{"id": 1, "seasonId": 2026, "settings": {"name": "Saved"}, "teams": [], "schedule": []}""")
        val data = client.loadAll()
        assertEquals("Saved", data.league.name)
        assertEquals(42L, data.fetchedAt) // when the cache was saved, not now
    }

    @Test
    fun finishedWeeksComeFromTheCacheForGood() {
        val cache = MemoryCache()
        cache.write("final-3-3", """{"schedule": []}""")
        // Unreachable server: if the client tried the network this would throw.
        val client = EspnClient(1, 2026, cache = cache, baseUrl = "http://127.0.0.1:9")
        assertEquals(0, client.boxScores(3, 3, final = true).getJSONArray("schedule").length())
    }

    @Test
    fun parsesScoreboard() {
        val raw = JSONObject(
            """
            {"events": [
              {"competitions": [{"status": {"period": 3, "displayClock": "7:30", "type": {"state": "in", "shortDetail": "Q3 7:30"}},
                "competitors": [
                  {"homeAway": "home", "score": "17", "team": {"abbreviation": "DEN"}},
                  {"homeAway": "away", "score": "14", "team": {"abbreviation": "SEA"}}]}]},
              {"competitions": [{"status": {"period": 5, "displayClock": "0:00", "type": {"state": "post", "shortDetail": "Final/OT"}},
                "competitors": [
                  {"homeAway": "home", "score": "20", "team": {"abbreviation": "GB"}},
                  {"homeAway": "away", "score": "23", "team": {"abbreviation": "DAL"}}]}]}]}
            """
        )
        val sb = EspnParser.parseScoreboard(raw)
        val den = sb.getValue("DEN")
        assertEquals(NflGame.IN, den.state)
        assertEquals("Q3 7:30", den.detail)
        assertEquals("14-17", den.scoreFor("SEA"))
        assertEquals("17-14", den.scoreFor("DEN"))
        // Two full quarters plus half of the third played: 37.5% left.
        assertEquals(0.375, den.remainingShare, 1e-9)
        assertEquals(0.0, sb.getValue("DAL").remainingShare, 1e-9)
    }

    @Test
    fun scoreboardDrivesLiveState() {
        val game = NflGame(NflGame.IN, "Q2 0:00", "DEN", "SEA", 7, 3, 0.5)
        val p = Player(1, "A B", "WR", "SEA", emptyList(), "ACTIVE", 10.0, emptyList(), false, actual = 4.0,
            gameStart = 0) // the kickoff-time guess would say "over"; the scoreboard knows better
        val rosters = mapOf(1 to listOf(RosterSpot(p, 4))).withGames(mapOf("SEA" to game))
        val live = rosters.getValue(1).single().player
        assertTrue(live.gameStarted(Long.MAX_VALUE))
        assertEquals(0.5, live.remainingShare(Long.MAX_VALUE), 1e-9)
        assertEquals(9.0, live.expectedFinal(Long.MAX_VALUE), 1e-9)
        val pre = live.copy(game = game.copy(state = NflGame.PRE, remainingShare = 1.0))
        assertFalse(pre.gameStarted(Long.MAX_VALUE))
    }

    @Test
    fun parsesRatingsAndAttachesOpponentRank() {
        val ratings = EspnParser.parseRatings(JSONObject(
            """{"positionAgainstOpponent": {"positionalRatings": {"1": {"ratingsByOpponent": {"6": {"average": 22.1, "rank": 28}}}}}}"""
        ))
        assertEquals(28, ratings.getValue(1).getValue(6))
        val pro = mapOf(9 to ProTeam("GB", 5, mapOf(7 to ProGame(1L, 6, true))), 6 to ProTeam("DAL", 10))
        val pool = JSONObject(
            """{"id": 5, "player": {"id": 5, "fullName": "J Love", "defaultPositionId": 1, "proTeamId": 9,
              "ownership": {"percentOwned": 85.2, "percentStarted": 67.4},
              "stats": [{"scoringPeriodId": 7, "statSourceId": 0, "appliedTotal": 21.3,
                "stats": {"0": 30, "1": 21, "3": 245, "4": 2, "20": 1}}]}}"""
        )
        val p = EspnParser.parsePlayer(pool, 7, pro, ratings)
        assertEquals(28, p.opponentRank)
        assertEquals("DAL", p.opponent)
        assertEquals(67.4, p.percentStarted, 1e-9)
        assertEquals("21/30, 245 YDS, 2 TD, 1 INT", p.statLine)
    }

    @Test
    fun statLinesForSkillPositions() {
        assertEquals("12 REC, 134 YDS", EspnParser.statLine(JSONObject("""{"53": 12, "42": 134}"""), "WR"))
        // Short like ESPN's line, touchdowns kept ahead of the secondary stats.
        assertEquals("15 CAR, 78 YDS, 1 TD, 3 REC",
            EspnParser.statLine(JSONObject("""{"23": 15, "24": 78, "53": 3, "42": 22, "25": 1}"""), "RB"))
        assertEquals("6 REC, 88 YDS, 2 TD", EspnParser.statLine(JSONObject("""{"53": 6, "42": 88, "43": 2}"""), "TE"))
        assertNull(EspnParser.statLine(JSONObject("{}"), "K"))
    }

    @Test
    fun photosAndLogos() {
        val wr = Player(4430737, "A B", "WR", "SEA", emptyList(), "ACTIVE", null, emptyList(), false)
        assertEquals("https://a.espncdn.com/i/headshots/nfl/players/full/4430737.png", wr.photoUrl())
        val dst = wr.copy(id = -16026, name = "Seahawks D/ST", position = "D/ST")
        assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/sea.png", dst.photoUrl())
    }

    @Test
    fun parsesTeamLogo() {
        val lg = EspnParser.parseLeague(JSONObject(
            """{"teams": [{"id": 1, "name": "A", "logo": "https://example.com/a.png"}, {"id": 2, "name": "B", "logo": ""}]}"""
        ))
        assertEquals("https://example.com/a.png", lg.teams.getValue(1).logo)
        assertNull(lg.teams.getValue(2).logo)
    }

    @Test
    fun possessionAndRedZoneMarkWhoIsOnTheField() {
        val raw = JSONObject(
            """
            {"events": [{"competitions": [{
              "status": {"period": 2, "displayClock": "3:00", "type": {"state": "in", "shortDetail": "Q2 3:00"}},
              "situation": {"possession": "26", "isRedZone": true},
              "competitors": [
                {"homeAway": "home", "score": "7", "team": {"id": "7", "abbreviation": "DEN"}},
                {"homeAway": "away", "score": "3", "team": {"id": "26", "abbreviation": "SEA"}}]}]}]}
            """
        )
        val game = EspnParser.parseScoreboard(raw).getValue("SEA")
        assertEquals("SEA", game.possession)
        assertTrue(game.redZone)
        fun player(pos: String, team: String) =
            Player(1, "X", pos, team, emptyList(), "ACTIVE", 10.0, emptyList(), false, game = game)
        assertEquals(FieldStatus.RED_ZONE, player("WR", "SEA").fieldStatus)
        assertNull(player("WR", "DEN").fieldStatus)            // their offense is on the sideline
        assertEquals(FieldStatus.RED_ZONE, player("D/ST", "DEN").fieldStatus) // defending
        assertNull(player("D/ST", "SEA").fieldStatus)
        val midfield = game.copy(redZone = false)
        assertEquals(FieldStatus.ON_FIELD, player("RB", "SEA").copy(game = midfield).fieldStatus)
        val done = game.copy(state = NflGame.POST)
        assertNull(player("WR", "SEA").copy(game = done).fieldStatus)
    }

    @Test
    fun scoreBreakdownFromEspnAndFromRules() {
        val pool = JSONObject(
            """{"id": 3, "player": {"id": 3, "fullName": "J S-N", "defaultPositionId": 3, "proTeamId": 26, "stats": [
              {"seasonId": 2026, "scoringPeriodId": 6, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 25.4,
               "stats": {"42": 134, "53": 12, "58": 14}, "appliedStats": {"42": 13.4, "53": 12.0}}]}}"""
        )
        val p = EspnParser.parsePlayer(pool, 6, emptyMap(), season = 2026)
        val rules = mapOf(42 to ScoringRule(0.1), 53 to ScoringRule(1.0, mapOf(6 to 1.5)))
        val lines = ScoreBreakdown.lines(p, rules)
        assertEquals(listOf("Receiving Yards", "Each Reception"), lines.map { it.label })
        assertEquals(listOf(0.1, 1.0), lines.map { it.perUnit })
        assertEquals(listOf(134.0, 12.0), lines.map { it.count })
        assertEquals(25.4, lines.sumOf { it.points }, 1e-9)
        // No appliedStats from ESPN: worked out from the league's rules, TE override included.
        val te = p.copy(position = "TE", appliedStats = emptyMap())
        assertEquals(listOf(13.4, 18.0), ScoreBreakdown.lines(te, rules).map { it.points })
        val league = EspnParser.parseLeague(JSONObject(
            """{"id": 1, "seasonId": 2026, "settings": {"scoringSettings": {"scoringItems": [
              {"statId": 53, "points": 1.0, "pointsOverrides": {"6": 1.5}}]}}}"""
        ))
        assertEquals(ScoringRule(1.0, mapOf(6 to 1.5)), league.scoring[53])
    }

    @Test
    fun projectionMovesWithLiveScoring() {
        val base = Player(1, "A B", "WR", "SEA", listOf(4), "ACTIVE", 17.0, emptyList(), false, gameStart = 0)
        val live = base.copy(actual = 19.0, game = NflGame(NflGame.IN, "Q3", "SEA", "DEN", 21, 10, 0.4))
        assertEquals(19.0 + 17.0 * 0.4, live.liveProjection(1)!!, 1e-9)
        val before = base.copy(gameStart = 100, game = NflGame(NflGame.PRE, "", "SEA", "DEN", 0, 0, 1.0))
        assertEquals(17.0, before.liveProjection(1)!!, 1e-9)
        val over = live.copy(game = live.game!!.copy(state = NflGame.POST, remainingShare = 0.0))
        assertEquals(17.0, over.liveProjection(1)!!, 1e-9) // final: ESPN's original projection again
    }

    @Test
    fun ignoresOtherSeasonsStats() {
        // ESPN sends last season's week 6 alongside this season's; only 2026 counts.
        val pool = JSONObject(
            """{"id": 8, "player": {"id": 8, "fullName": "A Back", "defaultPositionId": 2, "proTeamId": 9, "stats": [
              {"seasonId": 2025, "scoringPeriodId": 6, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 33.0},
              {"seasonId": 2025, "scoringPeriodId": 6, "statSplitTypeId": 1, "statSourceId": 1, "appliedTotal": 0.0},
              {"seasonId": 2025, "scoringPeriodId": 5, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 25.0},
              {"seasonId": 2026, "scoringPeriodId": 0, "statSplitTypeId": 0, "statSourceId": 0, "appliedTotal": 61.0},
              {"seasonId": 2026, "scoringPeriodId": 5, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 9.5},
              {"seasonId": 2026, "scoringPeriodId": 6, "statSplitTypeId": 1, "statSourceId": 1, "appliedTotal": 11.2}]}}"""
        )
        for (season in listOf(2026, null)) { // explicit, or inferred as the latest season present
            val p = EspnParser.parsePlayer(pool, 6, emptyMap(), season = season)
            assertNull(p.actual)
            assertEquals(11.2, p.projected!!, 1e-9)
            assertEquals(listOf(9.5), p.recentPoints)
        }
        val weeks = EspnParser.parsePlayerWeeks(JSONObject("""{"players": [$pool]}"""), 8, 2026)
        assertEquals(setOf(5, 6), weeks.keys)
        assertEquals(9.5, weeks.getValue(5).actual!!, 1e-9)
        assertNull(weeks.getValue(6).actual)
    }

    @Test
    fun parsesPlayerCardWeeks() {
        val raw = JSONObject(
            """
            {"players": [{"id": 77, "player": {"id": 77, "defaultPositionId": 3, "stats": [
              {"scoringPeriodId": 0, "statSourceId": 0, "appliedTotal": 140.0},
              {"scoringPeriodId": 1, "statSourceId": 0, "appliedTotal": 18.4, "stats": {"53": 7, "42": 104}},
              {"scoringPeriodId": 1, "statSourceId": 1, "appliedTotal": 14.1},
              {"scoringPeriodId": 2, "statSourceId": 1, "appliedTotal": 12.0}]}}]}
            """
        )
        val weeks = EspnParser.parsePlayerWeeks(raw, 77)
        assertEquals(setOf(1, 2), weeks.keys)
        assertEquals(18.4, weeks.getValue(1).actual!!, 1e-9)
        assertEquals(14.1, weeks.getValue(1).projected!!, 1e-9)
        assertEquals("7 REC, 104 YDS", weeks.getValue(1).statLine)
        assertNull(weeks.getValue(2).actual) // not played yet
        assertTrue(EspnParser.parsePlayerWeeks(raw, 99).isEmpty())
    }
}
