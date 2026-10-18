package com.fantasyanalyzer

import com.fantasyanalyzer.data.EspnLogin
import com.fantasyanalyzer.data.defaultSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
