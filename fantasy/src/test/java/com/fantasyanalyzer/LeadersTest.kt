package com.fantasyanalyzer

import com.fantasyanalyzer.data.EspnParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeadersTest {
    @Test
    fun parsesSeasonTotalsBestFirst() {
        val raw = JSONObject(
            """{"players": [
              {"id": 1, "onTeamId": 3, "player": {"id": 1, "fullName": "Ja'Marr Chase", "defaultPositionId": 3, "proTeamId": 4, "stats": [
                {"seasonId": 2025, "scoringPeriodId": 0, "statSplitTypeId": 0, "statSourceId": 0, "appliedTotal": 300.0, "stats": {"42": 1700}},
                {"seasonId": 2026, "scoringPeriodId": 0, "statSplitTypeId": 0, "statSourceId": 0, "appliedTotal": 140.5,
                 "stats": {"42": 812, "43": 6, "53": 55, "58": 80, "210": 9}},
                {"seasonId": 2026, "scoringPeriodId": 0, "statSplitTypeId": 0, "statSourceId": 1, "appliedTotal": 250.0},
                {"seasonId": 2026, "scoringPeriodId": 6, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 30.0, "stats": {"42": 50}},
                {"seasonId": 2026, "scoringPeriodId": 8, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 12.0, "stats": {"42": 60}},
                {"seasonId": 2026, "scoringPeriodId": 9, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 20.5, "stats": {"42": 90}},
                {"seasonId": 2026, "scoringPeriodId": 10, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 9.0, "stats": {"42": 10}}]}},
              {"id": 2, "onTeamId": 0, "player": {"id": 2, "fullName": "Free Guy", "defaultPositionId": 3, "proTeamId": 9, "stats": [
                {"seasonId": 2026, "scoringPeriodId": 0, "statSplitTypeId": 0, "statSourceId": 0, "appliedTotal": 160.0,
                 "stats": {"42": 900, "210": 8}}]}},
              {"id": 3, "player": {"id": 3, "fullName": "No Points", "defaultPositionId": 3, "stats": []}}]}"""
        )
        val leaders = EspnParser.parseLeaders(raw, 10, emptyMap(), season = 2026)
        assertEquals(listOf("Free Guy", "Ja'Marr Chase"), leaders.map { it.player.name })
        val chase = leaders[1]
        assertEquals(140.5, chase.points, 1e-9) // this season, not last, and not the projection
        assertEquals(812.0, chase.stats.getValue(42), 1e-9)
        assertEquals(9, chase.games)
        assertEquals(140.5 / 9, chase.average!!, 1e-9)
        assertEquals(3, chase.fantasyTeamId)
        assertNull(leaders[0].fantasyTeamId) // free agent

        // Last 3 finished weeks before week 10: 7, 8, 9 (he had no week 7 entry, a bye).
        val recent = chase.recentOnly()!!
        assertEquals(32.5, recent.points, 1e-9)
        assertEquals(2, recent.games)
        assertEquals(150.0, recent.stats.getValue(42), 1e-9)
        assertNull(leaders[0].recentOnly()) // no weekly stats sent for him
    }
}
