package com.fantasyanalyzer.data

import java.time.LocalDate

data class Settings(
    val useDemo: Boolean = false,
    val leagueId: String = "",
    val year: Int = defaultSeason(),
    val espnS2: String = "",
    val swid: String = "",
    /** The user's team in this league; 0 = work it out from their ESPN sign-in. */
    val myTeamId: Int = 0,
) {
    val hasLeague: Boolean get() = leagueId.toLongOrNull() != null
    val hasCookies: Boolean get() = espnS2.isNotBlank() && swid.isNotBlank()
}

/** The NFL season label: a season that started in September is still "that year" through the playoffs. */
fun defaultSeason(today: LocalDate = LocalDate.now()): Int =
    if (today.monthValue >= 3) today.year else today.year - 1
