package com.fantasyanalyzer

import com.fantasyanalyzer.data.AddDrop
import com.fantasyanalyzer.data.EspnParser
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.RosterMoves
import com.fantasyanalyzer.data.RosterSpot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterMovesTest {
    private fun p(id: Long, kickoff: Long? = null) =
        Player(id, "P$id", "WR", "KC", listOf(4, 20), "ACTIVE", 10.0, emptyList(), false, gameStart = kickoff)

    private val roster = listOf(RosterSpot(p(1), 4), RosterSpot(p(2), 20), RosterSpot(p(3), 20), RosterSpot(p(9), 21))

    @Test
    fun freeAgentAddWithDrop() {
        val t = RosterMoves.transaction(4, "{ME}", 7, AddDrop(p(50), p(2), claim = false))
        assertEquals("FREEAGENT", t.getString("type"))
        assertFalse(t.has("bidAmount"))
        val items = t.getJSONArray("items")
        assertEquals("ADD", items.getJSONObject(0).getString("type"))
        assertEquals(4, items.getJSONObject(0).getInt("toTeamId"))
        assertEquals(2L, items.getJSONObject(1).getLong("playerId"))
        assertEquals(4, items.getJSONObject(1).getInt("fromTeamId"))
    }

    @Test
    fun waiverClaimCarriesTheBid() {
        val t = RosterMoves.transaction(4, "{ME}", 7, AddDrop(p(50), null, claim = true, bid = 12))
        assertEquals("WAIVER", t.getString("type"))
        assertEquals(12, t.getInt("bidAmount"))
        assertEquals(1, t.getJSONArray("items").length())
    }

    @Test
    fun fullRosterNeedsADrop() {
        // Three players outside IR, limit three: adding needs a drop. IR doesn't count.
        assertTrue(RosterMoves.problem(AddDrop(p(50), null, false), roster, 3, null, 0)!!.contains("full"))
        assertNull(RosterMoves.problem(AddDrop(p(50), p(3), false), roster, 3, null, 0))
        assertNull(RosterMoves.problem(AddDrop(p(50), null, false), roster, 4, null, 0))
    }

    @Test
    fun cantDropAStarterWhoseGameStartedOrOverbid() {
        val started = listOf(RosterSpot(p(1, kickoff = 0), 4), RosterSpot(p(2), 20))
        assertTrue(RosterMoves.problem(AddDrop(p(50), p(1), false), started, 2, null, now = 10)!!.contains("can't be dropped"))
        assertTrue(RosterMoves.problem(AddDrop(p(50), p(2), true, bid = 30), started, 2, budgetLeft = 20, now = 10)!!.contains("$20"))
        assertNull(RosterMoves.problem(AddDrop(p(50), p(2), true, bid = 20), started, 2, budgetLeft = 20, now = 10))
    }

    @Test
    fun parsesPendingClaimsAndBudget() {
        val raw = JSONObject(
            """
            {"pendingTransactions": [
              {"id": "a1", "teamId": 4, "status": "PENDING", "bidAmount": 7, "processDate": 1760000000000,
               "items": [{"playerId": 50, "type": "ADD"}, {"playerId": 2, "type": "DROP"}]},
              {"id": "b2", "teamId": 5, "status": "PENDING", "items": [{"playerId": 60, "type": "ADD"}]},
              {"id": "c3", "teamId": 4, "status": "EXECUTED", "items": [{"playerId": 61, "type": "ADD"}]}]}
            """
        )
        val claims = EspnParser.parsePendingClaims(raw, 4)
        assertEquals(1, claims.size)
        assertEquals(50L, claims[0].addPlayerId)
        assertEquals(2L, claims[0].dropPlayerId)
        assertEquals(7, claims[0].bid)

        val lg = EspnParser.parseLeague(JSONObject(
            """{"settings": {"acquisitionSettings": {"isUsingAcquisitionBudget": true, "acquisitionBudget": 100},
               "rosterSettings": {"lineupSlotCounts": {"0": 1, "20": 6, "21": 1}}},
              "teams": [{"id": 4, "name": "A", "transactionCounter": {"acquisitionBudgetSpent": 37}}]}"""
        ))
        assertEquals(100, lg.faabBudget)
        assertEquals(37, lg.teams.getValue(4).faabSpent)
        assertEquals(7, lg.rosterLimit)
        assertNull(EspnParser.parseLeague(JSONObject("""{"teams": []}""")).faabBudget)
    }
}
