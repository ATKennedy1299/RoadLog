package com.fantasyanalyzer.data

import org.json.JSONArray
import org.json.JSONObject

/** Moving one player between lineup slots. */
data class LineupMove(val playerId: Long, val name: String, val from: Int, val to: Int)

/**
 * Editing a lineup before it's sent to ESPN. A lineup is a map of player id to
 * slot id; everything here is pure so it can be checked before anything is sent.
 */
object LineupEditor {

    fun current(roster: List<RosterSpot>): Map<Long, Int> = roster.associate { it.player.id to it.slotId }

    /** Slots [p] could be put in, in this league: starting slots they're eligible for, the bench, and IR if allowed. */
    fun allowedSlots(p: Player, slotCounts: Map<Int, Int>): List<Int> =
        p.eligibleSlots.filter { s -> s == BENCH || ((slotCounts[s] ?: 0) > 0) }.let { slots ->
            if (BENCH in slots) slots else slots + BENCH
        }.distinct()

    /**
     * Puts [playerId] in [toSlot]. If that slot is full, the player there who's
     * best placed to swap takes [playerId]'s old slot (or the bench if they
     * can't play it). Locked players never move.
     */
    fun move(
        lineup: Map<Long, Int>,
        roster: List<RosterSpot>,
        playerId: Long,
        toSlot: Int,
        slotCounts: Map<Int, Int>,
        now: Long,
    ): Map<Long, Int> {
        val byId = roster.associateBy { it.player.id }
        val from = lineup[playerId] ?: return lineup
        if (from == toSlot) return lineup
        val next = lineup.toMutableMap()
        next[playerId] = toSlot
        val capacity = slotCounts[toSlot] ?: 0
        if (toSlot != BENCH && next.count { it.value == toSlot } > capacity) {
            // Someone has to make room: prefer an unlocked occupant who can take the old slot.
            val occupants = next.filter { (id, s) -> s == toSlot && id != playerId }.keys
                .mapNotNull { byId[it] }.filter { !it.isLocked(now) }
            val bumped = occupants.firstOrNull { from in it.player.eligibleSlots || from == BENCH } ?: occupants.firstOrNull()
                ?: return lineup // everyone there is locked
            next[bumped.player.id] = if (from in bumped.player.eligibleSlots || from == BENCH) from else BENCH
        }
        return next
    }

    /**
     * Turns a set of chosen starters into slots, changing as few slots as
     * possible: each starter keeps their current slot when it still works.
     * Players not chosen go to the bench; IR stays IR; locked players stay put.
     */
    fun lineupFor(
        roster: List<RosterSpot>,
        starters: Set<Long>,
        slotCounts: Map<Int, Int>,
        now: Long,
    ): Map<Long, Int>? {
        val result = mutableMapOf<Long, Int>()
        val capacity = slotCounts.filterKeys { it !in INACTIVE_SLOTS }.toMutableMap()
        for (s in roster) {
            if (s.isLocked(now)) {
                result[s.player.id] = s.slotId
                if (s.started) capacity[s.slotId] = (capacity[s.slotId] ?: 0) - 1
            }
        }
        val toPlace = roster.filter { !it.isLocked(now) && it.player.id in starters }
        // Slot instances to fill, then a bipartite matching that tries each player's current slot first.
        val slots = capacity.flatMap { (slot, n) -> List(n.coerceAtLeast(0)) { slot } }
        val owner = arrayOfNulls<RosterSpot>(slots.size)
        fun options(s: RosterSpot) = slots.indices.filter { slots[it] in s.player.eligibleSlots }
            .sortedByDescending { slots[it] == s.slotId }
        fun place(s: RosterSpot, seen: BooleanArray): Boolean {
            for (i in options(s)) {
                if (seen[i]) continue
                seen[i] = true
                val other = owner[i]
                if (other == null || place(other, seen)) {
                    owner[i] = s
                    return true
                }
            }
            return false
        }
        // Current starters claim their own slots first; newcomers take a free slot before
        // displacing anyone, and only reshuffle others when there's no other way.
        val ordered = toPlace.sortedByDescending { it.started }
        for (s in ordered) {
            val free = options(s).firstOrNull { owner[it] == null }
            if (free != null) owner[free] = s else if (!place(s, BooleanArray(slots.size))) return null
        }
        owner.forEachIndexed { i, s -> if (s != null) result[s.player.id] = slots[i] }
        for (s in roster) {
            if (s.player.id !in result) result[s.player.id] = if (s.slotId == IR) IR else BENCH
        }
        return result
    }

    /** Moves needed to get from the roster as it is to [target]. */
    fun moves(roster: List<RosterSpot>, target: Map<Long, Int>): List<LineupMove> =
        roster.mapNotNull { s ->
            val to = target[s.player.id] ?: return@mapNotNull null
            if (to == s.slotId) null else LineupMove(s.player.id, s.player.name, s.slotId, to)
        }

    /** Why [target] can't be sent, or null if it's fine. */
    fun problem(roster: List<RosterSpot>, target: Map<Long, Int>, slotCounts: Map<Int, Int>, now: Long): String? {
        for (s in roster) {
            val to = target[s.player.id] ?: continue
            if (to != s.slotId && s.isLocked(now)) return "${s.player.name}'s game has started, so they can't be moved."
            if (to != s.slotId && to != BENCH && to !in s.player.eligibleSlots) {
                return "${s.player.name} can't play ${slotName(to)}."
            }
        }
        for ((slot, n) in target.values.groupingBy { it }.eachCount()) {
            if (slot == BENCH) continue
            val cap = slotCounts[slot] ?: 0
            if (n > cap) return "Too many players at ${slotName(slot)} ($n, the league allows $cap)."
        }
        return null
    }

    /**
     * The request body ESPN's site sends to change a lineup: one transaction
     * of LINEUP items for the team and NFL week.
     */
    fun transaction(teamId: Int, memberId: String, scoringPeriod: Int, moves: List<LineupMove>): JSONObject =
        JSONObject()
            .put("isLeagueManager", false)
            .put("teamId", teamId)
            .put("type", "ROSTER")
            .put("memberId", memberId)
            .put("scoringPeriodId", scoringPeriod)
            .put("executionType", "EXECUTE")
            .put("items", JSONArray().apply {
                moves.forEach { m ->
                    put(JSONObject()
                        .put("playerId", m.playerId)
                        .put("type", "LINEUP")
                        .put("fromLineupSlotId", m.from)
                        .put("toLineupSlotId", m.to))
                }
            })
}
