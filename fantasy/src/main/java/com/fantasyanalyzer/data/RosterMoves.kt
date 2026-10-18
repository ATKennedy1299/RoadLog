package com.fantasyanalyzer.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Adding a free agent or claiming a waiver player, optionally dropping
 * someone. [bid] is the waiver budget bid, for leagues that use one.
 */
data class AddDrop(val add: Player, val drop: Player?, val claim: Boolean, val bid: Int? = null)

/** A waiver claim waiting to process on ESPN. */
data class PendingClaim(
    val id: String,
    val addPlayerId: Long,
    val dropPlayerId: Long?,
    val bid: Int?,
    val processDate: Long?,
)

object RosterMoves {

    /**
     * Why [move] can't be sent for a team with [roster], or null if it's fine:
     * the roster has to have room once the drop (if any) is made, and the
     * player being dropped can't be locked into a game that's started.
     */
    fun problem(move: AddDrop, roster: List<RosterSpot>, rosterLimit: Int, budgetLeft: Int?, now: Long): String? {
        val outsideIr = roster.count { it.slotId != IR }
        val afterMove = outsideIr + 1 - (if (move.drop != null && roster.any { it.player.id == move.drop.id && it.slotId != IR }) 1 else 0)
        if (rosterLimit > 0 && afterMove > rosterLimit) return "Your roster is full. Pick someone to drop."
        val dropSpot = move.drop?.let { d -> roster.firstOrNull { it.player.id == d.id } }
        if (move.drop != null && dropSpot == null) return "${move.drop.name} isn't on your roster."
        if (dropSpot != null && dropSpot.started && dropSpot.isLocked(now)) {
            return "${dropSpot.player.name}'s game has started, so they can't be dropped from your lineup."
        }
        if (move.bid != null) {
            if (move.bid < 0) return "Bids can't be negative."
            if (budgetLeft != null && move.bid > budgetLeft) return "You only have $$budgetLeft left to bid."
        }
        return null
    }

    /** The request body ESPN's site sends for an add, drop or waiver claim. */
    fun transaction(teamId: Int, memberId: String, scoringPeriod: Int, move: AddDrop): JSONObject =
        JSONObject()
            .put("isLeagueManager", false)
            .put("teamId", teamId)
            .put("type", if (move.claim) "WAIVER" else "FREEAGENT")
            .put("memberId", memberId)
            .put("scoringPeriodId", scoringPeriod)
            .put("executionType", "EXECUTE")
            .apply { if (move.claim && move.bid != null) put("bidAmount", move.bid) }
            .put("items", JSONArray().apply {
                put(JSONObject().put("playerId", move.add.id).put("type", "ADD").put("toTeamId", teamId))
                move.drop?.let { put(JSONObject().put("playerId", it.id).put("type", "DROP").put("fromTeamId", teamId)) }
            })
}
