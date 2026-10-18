package com.fantasyanalyzer.ui

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyanalyzer.analysis.Analysis
import com.fantasyanalyzer.analysis.EfficiencyRow
import com.fantasyanalyzer.analysis.EfficiencySummary
import com.fantasyanalyzer.analysis.Matrix
import com.fantasyanalyzer.analysis.Odds
import com.fantasyanalyzer.analysis.OptimalRecordRow
import com.fantasyanalyzer.analysis.PlayoffOdds
import com.fantasyanalyzer.analysis.ScheduleRow
import com.fantasyanalyzer.analysis.Record
import com.fantasyanalyzer.analysis.StandingRow
import com.fantasyanalyzer.analysis.TeamGame
import com.fantasyanalyzer.data.EspnClient
import com.fantasyanalyzer.data.EspnException
import com.fantasyanalyzer.data.EspnLogin
import com.fantasyanalyzer.data.LeagueRef
import com.fantasyanalyzer.data.LeagueData
import com.fantasyanalyzer.data.SampleData
import com.fantasyanalyzer.data.Settings
import com.fantasyanalyzer.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the dashboard shows, computed once per load. [signedInTeamId] is
 * the team the signed-in ESPN account manages, if any.
 */
class Dashboard(val data: LeagueData, val signedInTeamId: Int? = null) {
    val league = data.league
    val standings: List<StandingRow> = Analysis.standings(league)
    val games: List<TeamGame> = Analysis.teamWeeks(league)
    val headToHead: Matrix<Record?> = Analysis.headToHead(league)
    val scheduleSwap: Matrix<Double> = Analysis.scheduleSwap(league)
    val notable: List<Pair<String, List<TeamGame>>> = Analysis.notableGames(league)
    val efficiency: List<EfficiencyRow> = Analysis.lineupEfficiency(league, data.boxScores)
    val efficiencySummary: List<EfficiencySummary> = Analysis.efficiencySummary(efficiency)
    val optimalRecord: List<OptimalRecordRow> = Analysis.optimalRecord(league, efficiency)
    val playoffOdds: PlayoffOdds = Odds.playoffOdds(league)
    val schedule: List<ScheduleRow> = Odds.strengthOfSchedule(league)

    /** The user's team: their own pick if it's in this league, else the one their account manages. */
    fun myTeamId(settings: Settings): Int? =
        settings.myTeamId.takeIf { it in league.teams } ?: signedInTeamId
}

sealed interface LeagueSearch {
    data object Idle : LeagueSearch
    data object Searching : LeagueSearch
    data class Found(val leagues: List<LeagueRef>) : LeagueSearch
    data class Failed(val message: String) : LeagueSearch
}

/** The ESPN session held by the in-app sign-in WebView. */
object BrowserSession {
    private val HOSTS = listOf("https://fantasy.espn.com", "https://www.espn.com", "https://espn.com")

    private fun cookies(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (e: Exception) {
        null // no WebView installed/updating
    }

    fun apiCookieHeader(): String? = cookies(EspnClient.API_ORIGIN)
    fun otherCookieHeaders(): List<String?> = HOSTS.map { cookies(it) }

    fun clear() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // nothing to clear
        }
    }
}

sealed interface UiState {
    data object NeedsSetup : UiState
    data class Loading(val message: String) : UiState
    data class Loaded(val dashboard: Dashboard) : UiState
    data class Error(val message: String, val authProblem: Boolean = false) : UiState
}

class LeagueViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _state = MutableStateFlow<UiState>(UiState.Loading("Loading…"))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _leagueSearch = MutableStateFlow<LeagueSearch>(LeagueSearch.Idle)
    val leagueSearch: StateFlow<LeagueSearch> = _leagueSearch.asStateFlow()

    private var loadJob: Job? = null

    // ESPN's edge sometimes turns away requests that don't look like a browser.
    private val userAgent: String? = try {
        WebSettings.getDefaultUserAgent(app)
    } catch (e: Exception) {
        null
    }

    private fun cookieCandidates(s: Settings): List<String> = EspnLogin.cookieCandidates(
        BrowserSession.apiCookieHeader(),
        BrowserSession.otherCookieHeaders(),
        EspnClient.cookieHeader(s.espnS2, s.swid),
    )

    /** Looks up the signed-in account's football leagues so the user can just pick one. */
    fun findLeagues(s: Settings) {
        val cookies = cookieCandidates(s)
        val swids = EspnLogin.cookieValues(listOf(BrowserSession.apiCookieHeader()) + BrowserSession.otherCookieHeaders(), "SWID") +
            listOfNotNull(s.swid.ifBlank { null })
        if (cookies.isEmpty() || swids.isEmpty()) {
            _leagueSearch.value = LeagueSearch.Failed("Sign in to ESPN first.")
            return
        }
        _leagueSearch.value = LeagueSearch.Searching
        viewModelScope.launch {
            _leagueSearch.value = try {
                val leagues = withContext(Dispatchers.IO) { EspnClient.findLeagues(swids, cookies, userAgent) }
                if (leagues.isEmpty()) {
                    LeagueSearch.Failed("Couldn't list your leagues automatically. Open your league in the sign-in page, or type its ID.")
                } else {
                    LeagueSearch.Found(leagues)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LeagueSearch.Failed(e.message ?: "Couldn't list your leagues.")
            }
        }
    }

    fun clearLeagueSearch() {
        _leagueSearch.value = LeagueSearch.Idle
    }

    init {
        load()
    }

    fun updateSettings(s: Settings) {
        val prev = _settings.value
        // A team picked in one league means nothing in another.
        val next = if (s.leagueId != prev.leagueId || s.useDemo != prev.useDemo) s.copy(myTeamId = 0) else s
        store.save(next)
        _settings.value = next
        load()
    }

    /** Remembers which team is the user's; no reload needed. */
    fun setMyTeam(teamId: Int) {
        val s = _settings.value.copy(myTeamId = teamId)
        store.save(s)
        _settings.value = s
    }

    /** ESPN account ids the user is signed in as, for spotting their team. */
    private fun accountIds(s: Settings): List<String> =
        if (s.useDemo) {
            listOf(SampleData.DEMO_SWID)
        } else {
            EspnLogin.cookieValues(listOf(BrowserSession.apiCookieHeader()) + BrowserSession.otherCookieHeaders(), "SWID") +
                listOfNotNull(s.swid.ifBlank { null })
        }

    fun load() {
        val s = _settings.value
        if (!s.useDemo && !s.hasLeague) {
            _state.value = UiState.NeedsSetup
            return
        }
        loadJob?.cancel()
        val cookies = if (s.useDemo) emptyList() else cookieCandidates(s)
        val accounts = accountIds(s)
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading(if (s.useDemo) "Building demo league…" else "Connecting to ESPN…")
            _state.value = try {
                val dashboard = withContext(Dispatchers.IO) {
                    val data = if (s.useDemo) {
                        SampleData.generate()
                    } else {
                        EspnClient(s.leagueId.toLong(), s.year, cookies, userAgent).loadAll { msg ->
                            _state.value = UiState.Loading(msg)
                        }
                    }
                    _state.value = UiState.Loading("Simulating the rest of the season…")
                    Dashboard(data, data.league.teamOwnedBy(accounts))
                }
                UiState.Loaded(dashboard)
            } catch (e: EspnException) {
                UiState.Error(e.message ?: "Couldn't load the league.", e.authProblem)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error("Something went wrong reading the league: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
