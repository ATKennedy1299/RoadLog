package com.fantasyanalyzer

import com.fantasyanalyzer.data.EspnParser
import com.fantasyanalyzer.data.ScoringStyle
import com.fantasyanalyzer.data.SeasonForm
import com.fantasyanalyzer.data.WeekLine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonFormTest {
    private fun line(actual: Double?, proj: Double?) = WeekLine(actual, proj, null)

    @Test
    fun beatRecordSkipsByesInjuriesAndTheWeekInProgress() {
        val weeks = mapOf(
            1 to line(18.0, 14.0), 2 to line(9.0, 13.0), 3 to line(null, null), // bye
            4 to line(0.0, 0.0), // ruled out: no projection
            5 to line(15.0, 15.0), 6 to line(30.0, 12.0), // week 6 still being played
        )
        val form = SeasonForm.of(weeks, before = 6)!!
        assertEquals(listOf(true, false, true), form.results)
        assertEquals(2, form.beat)
        assertNull(form.range) // only 3 games
        assertNull(SeasonForm.of(mapOf(6 to line(10.0, 9.0)), before = 6))
    }

    @Test
    fun rangeAndStyle() {
        val steady = (1..6).associateWith { line(listOf(12.0, 14.0, 13.0, 15.0, 11.0, 14.0)[it - 1], 13.0) }
        val s = SeasonForm.of(steady, before = 7)!!
        assertEquals(ScoringStyle.STEADY, s.style)
        assertEquals(13.5, s.range!!.typical, 1e-9)
        assertEquals(12.0, s.range!!.floor, 1e-9) // 20% of the way through 11,12,13,14,14,15
        assertEquals(14.0, s.range!!.ceiling, 1e-9)

        val boom = (1..6).associateWith { line(listOf(2.0, 31.0, 4.0, 3.0, 26.0, 5.0)[it - 1], 12.0) }
        assertEquals(ScoringStyle.BOOM_BUST, SeasonForm.of(boom, before = 7)!!.style)
    }

    @Test
    fun parsesEveryPlayersSeason() {
        val raw = JSONObject(
            """{"players": [
              {"id": 1, "player": {"id": 1, "defaultPositionId": 2, "stats": [
                {"seasonId": 2026, "scoringPeriodId": 1, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 11.0},
                {"seasonId": 2026, "scoringPeriodId": 1, "statSplitTypeId": 1, "statSourceId": 1, "appliedTotal": 9.0},
                {"seasonId": 2025, "scoringPeriodId": 2, "statSplitTypeId": 1, "statSourceId": 0, "appliedTotal": 40.0}]}},
              {"id": 2, "player": {"id": 2, "defaultPositionId": 3, "stats": []}}]}"""
        )
        val seasons = EspnParser.parseSeasons(raw, 2026)
        assertEquals(setOf(1L), seasons.keys)
        val w1 = seasons.getValue(1).getValue(1)
        assertEquals(11.0, w1.actual!!, 1e-9)
        assertEquals(9.0, w1.projected!!, 1e-9)
        assertNull(seasons.getValue(1)[2])
    }
}
