package com.fantasyanalyzer

import com.fantasyanalyzer.data.EspnClient
import com.fantasyanalyzer.data.EspnException
import com.fantasyanalyzer.data.LineupEditor
import com.fantasyanalyzer.data.LineupMove
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.RosterSpot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class LineupEditorTest {
    private val counts = mapOf(0 to 1, 2 to 2, 4 to 2, 23 to 1, 20 to 6, 21 to 1)
    private val now = 1_000L

    private fun spot(id: Long, pos: String, slot: Int, proj: Double = 10.0, kickoff: Long? = null): RosterSpot {
        val elig = when (pos) {
            "QB" -> listOf(0, 7, 20, 21)
            "RB" -> listOf(2, 3, 23, 7, 20, 21)
            else -> listOf(4, 3, 5, 23, 7, 20, 21)
        }
        return RosterSpot(Player(id, "P$id", pos, "KC", elig, "ACTIVE", proj, emptyList(), false, gameStart = kickoff), slot)
    }

    private val roster = listOf(
        spot(1, "QB", 0),
        spot(2, "RB", 2), spot(3, "RB", 2), spot(4, "RB", 20),
        spot(5, "WR", 4), spot(6, "WR", 4), spot(7, "WR", 23), spot(8, "WR", 20),
    )

    @Test
    fun movingIntoAFullSlotSwaps() {
        val start = LineupEditor.current(roster)
        val next = LineupEditor.move(start, roster, 4, 2, counts, now) // bench RB into RB
        assertEquals(2, next[4L])
        // One of the RBs takes the bench spot RB4 left.
        assertEquals(1, listOf(next[2L], next[3L]).count { it == 20 })
        assertNull(LineupEditor.problem(roster, next, counts, now))
        assertEquals(2, LineupEditor.moves(roster, next).size)
    }

    @Test
    fun swapPutsBumpedPlayerInTheOldSlotWhenEligible() {
        // FLEX WR into WR: a WR starter moves to FLEX rather than the bench.
        val next = LineupEditor.move(LineupEditor.current(roster), roster, 7, 4, counts, now)
        assertEquals(4, next[7L])
        assertEquals(1, listOf(next[5L], next[6L]).count { it == 23 })
    }

    @Test
    fun lockedPlayersAreNeverBumped() {
        val locked = roster.map { if (it.player.id == 2L || it.player.id == 3L) it.copy(locked = true) else it }
        val start = LineupEditor.current(locked)
        assertEquals(start, LineupEditor.move(start, locked, 4, 2, counts, now))
        // And moving one directly is caught before sending.
        val forced = start + (2L to 20)
        assertNotNull(LineupEditor.problem(locked, forced, counts, now))
    }

    @Test
    fun kickoffLocksToo() {
        val r = roster.map { if (it.player.id == 5L) spot(5, "WR", 4, kickoff = now - 1) else it }
        val target = LineupEditor.current(r) + (5L to 20)
        assertTrue(LineupEditor.problem(r, target, counts, now)!!.contains("P5"))
    }

    @Test
    fun problemCatchesIneligibleAndOverfull() {
        val start = LineupEditor.current(roster)
        assertTrue(LineupEditor.problem(roster, start + (1L to 2), counts, now)!!.contains("can't play RB"))
        assertTrue(LineupEditor.problem(roster, start + (4L to 2), counts, now)!!.contains("Too many"))
    }

    @Test
    fun lineupForKeepsSlotsWherePossible() {
        // Start RB4 instead of WR7 (in FLEX): RB4 should take FLEX; nobody else should move.
        val starters = setOf(1L, 2L, 3L, 4L, 5L, 6L)
        val target = LineupEditor.lineupFor(roster, starters, counts, now)!!
        val moves = LineupEditor.moves(roster, target).associateBy { it.playerId }
        assertEquals(setOf(4L, 7L), moves.keys)
        assertEquals(23, moves.getValue(4L).to)
        assertEquals(20, moves.getValue(7L).to)
        assertNull(LineupEditor.problem(roster, target, counts, now))
    }

    @Test
    fun lineupForFailsWhenStartersCantFit() {
        assertNull(LineupEditor.lineupFor(roster, setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), counts, now))
    }

    @Test
    fun allowedSlotsSkipSlotsTheLeagueDoesntUse() {
        val wr = roster.first { it.player.id == 5L }.player
        assertEquals(listOf(4, 23, 20, 21), LineupEditor.allowedSlots(wr, counts))
    }

    @Test
    fun transactionMatchesEspnsShape() {
        val t = LineupEditor.transaction(3, "{ABC}", 7, listOf(LineupMove(99, "X", 20, 2)))
        assertEquals("ROSTER", t.getString("type"))
        assertEquals("EXECUTE", t.getString("executionType"))
        assertEquals("{ABC}", t.getString("memberId"))
        val item = t.getJSONArray("items").getJSONObject(0)
        assertEquals(99L, item.getLong("playerId"))
        assertEquals("LINEUP", item.getString("type"))
        assertEquals(20, item.getInt("fromLineupSlotId"))
        assertEquals(2, item.getInt("toLineupSlotId"))
    }

    private class Captured(val method: String, val path: String, val cookie: String?, val body: String)

    /** One-shot-per-request fake of ESPN's write API. */
    private fun withWriteServer(respond: (Captured) -> Pair<String, String>, block: (String, List<Captured>) -> Unit) {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Captured>())
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                sock.use {
                    val input = sock.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val c = input.read()
                        if (c < 0) break
                        head.append(c.toChar())
                    }
                    val lines = head.lines()
                    val (method, path) = lines.first().split(" ").let { it[0] to it[1] }
                    val header = { name: String ->
                        lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(":")?.trim()
                    }
                    val len = header("Content-Length")?.toInt() ?: 0
                    val body = String(ByteArray(len).also { b -> var r = 0; while (r < len) r += input.read(b, r, len - r) })
                    val cap = Captured(method, path, header("Cookie"), body)
                    seen += cap
                    val (status, resp) = respond(cap)
                    val bytes = resp.toByteArray()
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}", seen)
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun submitPostsTransactionWithAcceptedCookies() = withWriteServer(
        respond = { c -> if (c.cookie!!.contains("{ACCOUNT}")) "200 OK" to """{"status": "EXECUTED"}""" else "401 Unauthorized" to "{}" },
    ) { base, seen ->
        val client = EspnClient(55, 2026, listOf("espn_s2=S; SWID={ANON}", "espn_s2=S; SWID={ACCOUNT}"), writeUrl = base)
        val msg = client.submitLineup(4, 7, listOf(LineupMove(99, "X", 20, 2)))
        assertEquals("ESPN: executed", msg)
        assertEquals(2, seen.size)
        val sent = seen.last()
        assertEquals("POST", sent.method)
        assertEquals("/seasons/2026/segments/0/leagues/55/transactions/", sent.path)
        val body = JSONObject(sent.body)
        assertEquals(4, body.getInt("teamId"))
        assertEquals("{ACCOUNT}", body.getString("memberId")) // member id matches the cookies sent
        assertEquals(7, body.getInt("scoringPeriodId"))
    }

    @Test
    fun submitReportsEspnsReason() = withWriteServer(
        respond = { "409 Conflict" to """{"messages": ["Player is locked."]}""" },
    ) { base, _ ->
        try {
            EspnClient(55, 2026, listOf("espn_s2=S; SWID={ACCOUNT}"), writeUrl = base)
                .submitLineup(4, 7, listOf(LineupMove(99, "X", 20, 2)))
            fail("expected EspnException")
        } catch (e: EspnException) {
            assertTrue(e.message!!, e.message!!.contains("Player is locked."))
        }
    }
}
