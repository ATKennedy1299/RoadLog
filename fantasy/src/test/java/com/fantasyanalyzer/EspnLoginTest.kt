package com.fantasyanalyzer

import com.fantasyanalyzer.data.EspnClient
import com.fantasyanalyzer.data.EspnException
import com.fantasyanalyzer.data.EspnLogin
import org.json.JSONObject
import com.fantasyanalyzer.data.defaultSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.time.LocalDate

class EspnLoginTest {

    @Test
    fun anonymousVisitorIsNotSignedIn() {
        // ESPN sets SWID for everyone; only espn_s2 means a real login.
        val login = EspnLogin().update(listOf("SWID={ABC-123}; country=us", null), "https://fantasy.espn.com/football/")
        assertEquals("{ABC-123}", login.swid)
        assertFalse(login.signedIn)
    }

    @Test
    fun picksUpCookiesAndLeagueAcrossPages() {
        var login = EspnLogin().update(listOf("SWID={ABC-123}; espn_s2=AEBx%2Fy%3D"), "https://fantasy.espn.com/football/")
        assertTrue(login.signedIn)
        assertEquals("AEBx%2Fy%3D", login.espnS2)
        assertNull(login.leagueId)

        login = login.update(listOf(null), "https://fantasy.espn.com/football/league?leagueId=4815162&seasonId=2026")
        assertEquals("4815162", login.leagueId)
        assertEquals(2026, login.season)
        assertTrue(login.signedIn) // cookies from the earlier page are kept
    }

    @Test
    fun leagueIdFromUrl() {
        assertEquals("123", EspnLogin.leagueIdFrom("https://fantasy.espn.com/football/team?leagueId=123&teamId=4"))
        assertNull(EspnLogin.leagueIdFrom("https://fantasy.espn.com/football/team?teamId=4"))
        assertNull(EspnLogin.leagueIdFrom("12345"))
    }

    @Test
    fun seasonRollsOverInMarch() {
        assertEquals(2025, defaultSeason(LocalDate.of(2026, 1, 20)))
        assertEquals(2026, defaultSeason(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun cookieCandidatesPreferBrowserHeaderAndPairEverySwid() {
        val api = "SWID={ANON}; espn_s2=S2; country=us"
        val other = listOf("SWID={ACCOUNT}; espn_s2=S2", null)
        val c = EspnLogin.cookieCandidates(api, other, "espn_s2=typed; SWID={TYPED}")
        assertEquals(api, c.first())
        assertTrue("espn_s2=S2; SWID={ACCOUNT}" in c)
        assertEquals("espn_s2=typed; SWID={TYPED}", c.last())
        assertEquals(c, c.distinct())
    }

    @Test
    fun cookieCandidatesSkipAnonymousBrowserSession() {
        // No espn_s2 in the browser yet: only the typed-in cookies are worth trying.
        val c = EspnLogin.cookieCandidates("SWID={ANON}", listOf(null), "espn_s2=typed; SWID={TYPED}")
        assertEquals(listOf("espn_s2=typed; SWID={TYPED}"), c)
    }

    @Test
    fun parsesLeaguesFromFanProfile() {
        val raw = JSONObject(
            """
            {"preferences": [
              {"type": {"code": "fantasy"}, "metaData": {"entry": {"abbrev": "FFL", "gameId": 1, "seasonId": 2026,
                "entryLocation": "Team", "entryNickname": "Taco", "groups": [{"groupId": 4815162, "groupName": "Work League"}]}}},
              {"type": {"code": "fantasy"}, "metaData": {"entry": {"abbrev": "FBA", "gameId": 3, "seasonId": 2026,
                "groups": [{"groupId": 999, "groupName": "Hoops"}]}}},
              {"type": {"code": "team"}, "metaData": {"team": {"id": 1}}}
            ]}
            """
        )
        val leagues = EspnClient.parseFanLeagues(raw)
        assertEquals(1, leagues.size)
        assertEquals(4815162L, leagues[0].leagueId)
        assertEquals("Work League", leagues[0].leagueName)
        assertEquals("Team Taco", leagues[0].teamName)
        assertEquals(2026, leagues[0].season)
    }

    /**
     * Fake league API that only answers to one cookie, like ESPN for a private
     * league. A bare socket server, since Android unit tests don't get the JDK's
     * com.sun HTTP server.
     */
    private fun withFakeEspn(goodCookie: String, block: (baseUrl: String, hits: MutableList<String?>) -> Unit) {
        val hits = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    var cookie: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Cookie:", ignoreCase = true)) cookie = line.substringAfter(":").trim()
                    }
                    hits += cookie
                    val (status, body) = if (cookie == goodCookie) {
                        "200 OK" to """{"id": 1, "seasonId": 2026, "settings": {"name": "Real League"}, "teams": [], "schedule": []}"""
                    } else {
                        "401 Unauthorized" to """{"messages": ["You are not authorized to view this League."], "details": [{"type": "AUTH_LEAGUE_NOT_VISIBLE"}]}"""
                    }
                    val bytes = body.toByteArray()
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}", hits)
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun clientFallsBackToCookiesEspnAccepts() = withFakeEspn("espn_s2=S2; SWID={ACCOUNT}") { base, hits ->
        val client = EspnClient(1, 2026, listOf("espn_s2=S2; SWID={ANON}", "espn_s2=S2; SWID={ACCOUNT}"), baseUrl = base)
        val data = client.loadAll()
        assertEquals("Real League", data.league.name)
        assertEquals(listOf("espn_s2=S2; SWID={ANON}", "espn_s2=S2; SWID={ACCOUNT}"), hits.take(2))
        // Later requests (projections, free agents) reuse the cookies that worked instead of
        // retrying the bad ones. The pro schedule is public and goes without cookies.
        assertTrue(hits.size > 2)
        assertTrue(hits.drop(2).all { it == null || it == "espn_s2=S2; SWID={ACCOUNT}" })
    }

    @Test
    fun clientReportsEspnsOwnMessage() = withFakeEspn("nope") { base, _ ->
        try {
            EspnClient(42, 2026, listOf("espn_s2=S2; SWID={ANON}"), baseUrl = base).league()
            fail("expected an EspnException")
        } catch (e: EspnException) {
            assertTrue(e.authProblem)
            assertNotNull(e.message)
            assertTrue(e.message!!, e.message!!.contains("You are not authorized to view this League."))
            assertTrue(e.message!!.contains("42"))
        }
    }
}
