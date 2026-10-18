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
import com.fantasyanalyzer.analysis.Record
import com.fantasyanalyzer.analysis.ScheduleRow
import com.fantasyanalyzer.analysis.StandingRow
import com.fantasyanalyzer.analysis.TeamGame
import com.fantasyanalyzer.data.EspnClient
import com.fantasyanalyzer.data.EspnException
import com.fantasyanalyzer.data.EspnLogin
import com.fantasyanalyzer.data.FileJsonCache
import com.fantasyanalyzer.data.JsonCache
import com.fantasyanalyzer.data.LeagueData
import com.fantasyanalyzer.data.LeagueRef
import com.fantasyanalyzer.data.LineupMove
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.SampleData
import com.fantasyanalyzer.data.Settings
import com.fantasyanalyzer.data.SettingsStore
import com.fantasyanalyzer.data.WeekLine
import com.fantasyanalyzer.data.AddDrop
import com.fantasyanalyzer.data.PendingClaim
import com.fantasyanalyzer.data.EspnParser
import com.fantasyanalyzer.data.withGames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The season-long numbers: they depend only on scores, so live refreshes reuse them. */
class LeagueAnalytics(data: LeagueData) {
    private val league = data.league
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
}

/**
 * Everything the dashboard shows. [signedInTeamId] is the team the signed-in
 * ESPN account manages, if any.
 */
class Dashboard(
    val data: LeagueData,
    val signedInTeamId: Int? = null,
    private val analytics: LeagueAnalytics = LeagueAnalytics(data),
) {
    val league get() = data.league
    val standings get() = analytics.standings
    val games get() = analytics.games
    val headToHead get() = analytics.headToHead
    val scheduleSwap get() = analytics.scheduleSwap
    val notable get() = analytics.notable
    val efficiency get() = analytics.efficiency
    val efficiencySummary get() = analytics.efficiencySummary
    val optimalRecord get() = analytics.optimalRecord
    val playoffOdds get() = analytics.playoffOdds
    val schedule get() = analytics.schedule

    /** Same season, fresher current week (live scores, a saved lineup). */
    fun withCurrentWeek(data: LeagueData) = Dashboard(data, signedInTeamId, analytics)

    /** The user's team: their own pick if it's in this league, else the one their account manages. */
    fun myTeamId(settings: Settings): Int? =
        settings.myTeamId.takeIf { it in league.teams } ?: signedInTeamId

    /** Any NFL game this week in progress. */
    val gamesInProgress: Boolean get() = data.scoreboard.values.any { it.state == NflGame.IN }
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

/** Sending lineup changes to ESPN. */
sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Saved(val message: String) : SaveState
    data class Failed(val message: String) : SaveState
}

/** A week's lineups for the Matchup and My Team tabs. */
sealed interface WeekLineups {
    data object Loading : WeekLineups
    data class Loaded(val rosters: Map<Int, List<RosterSpot>>) : WeekLineups
    data class Unavailable(val message: String) : WeekLineups
}

sealed interface UiState {
    data object NeedsSetup : UiState
    data class Loading(val message: String) : UiState

    /**
     * [refreshing]: showing saved data while fresh data loads. [refreshError]:
     * the last refresh failed, so what's shown may be out of date.
     */
    data class Loaded(
        val dashboard: Dashboard,
        val refreshing: Boolean = false,
        val refreshError: String? = null,
    ) : UiState

    data class Error(val message: String, val authProblem: Boolean = false) : UiState
}

class LeagueViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val cacheRoot = File(app.filesDir, "espn-cache")

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _state = MutableStateFlow<UiState>(UiState.Loading("Loading…"))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _leagueSearch = MutableStateFlow<LeagueSearch>(LeagueSearch.Idle)
    val leagueSearch: StateFlow<LeagueSearch> = _leagueSearch.asStateFlow()

    private val _weekLineups = MutableStateFlow<Map<Int, WeekLineups>>(emptyMap())
    val weekLineups: StateFlow<Map<Int, WeekLineups>> = _weekLineups.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private val _txState = MutableStateFlow<SaveState>(SaveState.Idle)
    /** The last add, drop or waiver claim sent to ESPN. */
    val txState: StateFlow<SaveState> = _txState.asStateFlow()

    private val _pending = MutableStateFlow<List<PendingClaim>?>(null)
    /** The user's waiver claims waiting to process; null until loaded. */
    val pending: StateFlow<List<PendingClaim>?> = _pending.asStateFlow()

    /** Sends an add/drop or waiver claim to ESPN, then reloads (roster and free agents both change). */
    fun submitAddDrop(teamId: Int, move: AddDrop) {
        val s = _settings.value
        val d = loaded ?: return
        if (s.useDemo) {
            _txState.value = SaveState.Failed("This is the demo league, so there's no ESPN team to change.")
            return
        }
        if (_txState.value == SaveState.Saving) return
        _txState.value = SaveState.Saving
        val net = client(s)
        val period = d.league.currentScoringPeriod
        viewModelScope.launch {
            _txState.value = try {
                SaveState.Saved(withContext(Dispatchers.IO) { net.submitAddDrop(teamId, period, move) })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SaveState.Failed(e.message ?: "Couldn't make that move.")
            }
            if (_txState.value is SaveState.Saved) {
                if (move.claim) requestPending(teamId, force = true) else load()
            }
        }
    }

    fun clearTxState() {
        if (_txState.value != SaveState.Saving) _txState.value = SaveState.Idle
    }

    /** Loads the user's pending waiver claims (best effort: an empty list if ESPN won't say). */
    fun requestPending(teamId: Int, force: Boolean = false) {
        val s = _settings.value
        if (s.useDemo) {
            _pending.value = emptyList()
            return
        }
        if (!force && _pending.value != null) return
        val net = client(s)
        viewModelScope.launch {
            _pending.value = try {
                withContext(Dispatchers.IO) { EspnParser.parsePendingClaims(net.pendingTransactions(), teamId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private val _playerWeeks = MutableStateFlow<Map<Long, Map<Int, WeekLine>>>(emptyMap())
    /** Player id -> their whole season, loaded when their sheet is opened. */
    val playerWeeks: StateFlow<Map<Long, Map<Int, WeekLine>>> = _playerWeeks.asStateFlow()

    /** Loads [playerId]'s season week by week for the player sheet (once per session). */
    fun requestPlayer(playerId: Long) {
        val s = _settings.value
        if (s.useDemo || playerId in _playerWeeks.value) return
        val net = client(s)
        viewModelScope.launch {
            val weeks = try {
                withContext(Dispatchers.IO) { EspnParser.parsePlayerWeeks(net.playerCard(playerId), playerId, s.year) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (weeks != null && weeks.isNotEmpty()) _playerWeeks.value = _playerWeeks.value + (playerId to weeks)
        }
    }

    private val _liveMode = MutableStateFlow(false)
    val liveMode: StateFlow<Boolean> = _liveMode.asStateFlow()

    private var loadJob: Job? = null
    private var liveJob: Job? = null

    /** Bumped on every full load, so results fetched for an older load are thrown away. */
    private var generation = 0

    // ESPN's edge sometimes turns away requests that don't look like a browser.
    private val userAgent: String? = try {
        WebSettings.getDefaultUserAgent(app)
    } catch (e: Exception) {
        null
    }

    init {
        load()
    }

    private fun cacheFor(s: Settings): JsonCache = FileJsonCache(File(cacheRoot, "${s.leagueId}-${s.year}"))

    private fun client(s: Settings, offline: Boolean = false) =
        EspnClient(s.leagueId.toLong(), s.year, cookieCandidates(s), userAgent, cache = cacheFor(s), offline = offline)

    private fun cookieCandidates(s: Settings): List<String> = EspnLogin.cookieCandidates(
        BrowserSession.apiCookieHeader(),
        BrowserSession.otherCookieHeaders(),
        EspnClient.cookieHeader(s.espnS2, s.swid),
    )

    /** ESPN account ids the user is signed in as, for spotting their team. */
    private fun accountIds(s: Settings): List<String> =
        if (s.useDemo) {
            listOf(SampleData.DEMO_SWID)
        } else {
            EspnLogin.cookieValues(listOf(BrowserSession.apiCookieHeader()) + BrowserSession.otherCookieHeaders(), "SWID") +
                listOfNotNull(s.swid.ifBlank { null })
        }

    private val loaded: Dashboard? get() = (_state.value as? UiState.Loaded)?.dashboard

    // --- Loading ------------------------------------------------------------------------

    /**
     * Shows whatever was saved last time straight away, then refreshes from ESPN.
     * Finished weeks come from the cache, so a refresh only downloads what can
     * still change.
     */
    fun load() {
        val s = _settings.value
        if (!s.useDemo && !s.hasLeague) {
            _state.value = UiState.NeedsSetup
            return
        }
        loadJob?.cancel()
        val gen = ++generation
        _weekLineups.value = emptyMap()
        val accounts = accountIds(s)
        // Built here on the main thread: they read the WebView's cookies.
        val offlineClient = if (s.useDemo) null else client(s, offline = true)
        val netClient = if (s.useDemo) null else client(s)
        loadJob = viewModelScope.launch {
            if (s.useDemo || offlineClient == null || netClient == null) {
                _state.value = UiState.Loading("Building demo league…")
                val d = withContext(Dispatchers.Default) {
                    val data = SampleData.generate()
                    Dashboard(data, data.league.teamOwnedBy(accounts))
                }
                _state.value = UiState.Loaded(d)
                return@launch
            }

            // 1. Instant start from the cache, if there is one.
            val shownFromCache = loaded?.takeIf { it.league.id == s.leagueId.toLong() } ?: try {
                withContext(Dispatchers.IO) {
                    val data = offlineClient.loadAll()
                    Dashboard(data, data.league.teamOwnedBy(accounts))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (shownFromCache != null) {
                _state.value = UiState.Loaded(shownFromCache, refreshing = true)
            } else {
                _state.value = UiState.Loading("Connecting to ESPN…")
            }

            // 2. Fresh from ESPN.
            try {
                val fresh = withContext(Dispatchers.IO) {
                    val data = netClient.loadAll { msg ->
                        if (shownFromCache == null) _state.value = UiState.Loading(msg)
                    }
                    if (shownFromCache == null) _state.value = UiState.Loading("Simulating the rest of the season…")
                    Dashboard(data, data.league.teamOwnedBy(accounts))
                }
                if (gen != generation) return@launch
                _weekLineups.value = emptyMap()
                _state.value = UiState.Loaded(fresh)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = when (e) {
                    is EspnException -> e.message ?: "Couldn't load the league."
                    else -> "Something went wrong reading the league: ${e.message ?: e.javaClass.simpleName}"
                }
                _state.value = if (shownFromCache != null) {
                    UiState.Loaded(shownFromCache, refreshError = msg)
                } else {
                    UiState.Error(msg, (e as? EspnException)?.authProblem == true)
                }
            }
        }
    }

    /**
     * Just the current week: lineups, points and NFL scores. What live mode runs
     * every minute, and what a lineup save refreshes. Season numbers are reused.
     */
    fun refreshLive(force: Boolean = false) {
        val d = loaded ?: return
        val s = _settings.value
        if (s.useDemo || loadJob?.isActive == true) return
        if (liveJob?.isActive == true) {
            if (!force) return
            liveJob?.cancel()
        }
        val gen = generation
        val net = client(s)
        liveJob = viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val (rosters, scoreboard) = net.currentWeek(d.league, d.data.proTeams, d.data.ratings)
                    val outlook = d.data.outlook?.copy(rosters = rosters)
                    d.data.copy(outlook = outlook, scoreboard = scoreboard.ifEmpty { d.data.scoreboard }, fetchedAt = System.currentTimeMillis())
                }
                if (gen != generation) return@launch
                val fresh = d.withCurrentWeek(data)
                _state.value = UiState.Loaded(fresh)
                data.outlook?.let { putWeek(d.league.currentWeek, WeekLineups.Loaded(it.rosters), gen) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                (_state.value as? UiState.Loaded)?.let {
                    _state.value = it.copy(refreshError = e.message ?: "Couldn't refresh.")
                }
            }
        }
    }

    fun setLiveMode(on: Boolean) {
        _liveMode.value = on
        if (on) refreshLive()
    }

    // --- Weeks for the Matchup and My Team tabs --------------------------------------------

    private fun putWeek(week: Int, value: WeekLineups, gen: Int = generation) {
        if (gen == generation) _weekLineups.value = _weekLineups.value + (week to value)
    }

    /**
     * Makes sure [week]'s lineups are loaded (or loading). The current week comes
     * from what's already loaded; other weeks are fetched once and cached
     * (finished weeks for good). [fresh] skips the cache, e.g. after a save.
     */
    fun requestWeek(week: Int, fresh: Boolean = false) {
        val d = loaded ?: return
        val existing = _weekLineups.value[week]
        if (!fresh && (existing is WeekLineups.Loaded || existing is WeekLineups.Loading)) return
        val data = d.data
        val league = data.league
        val fromBox = data.boxScores[week]?.mapValues { (_, r) -> r.map { it.toSpot() } }
        val outlook = data.outlook
        if (outlook != null && week == league.currentWeek && !fresh) {
            putWeek(week, WeekLineups.Loaded(outlook.rosters))
            return
        }
        val s = _settings.value
        if (s.useDemo) {
            putWeek(week, fromBox?.let { WeekLineups.Loaded(it) } ?: WeekLineups.Unavailable("The demo doesn't have lineups for this week."))
            return
        }
        if (existing !is WeekLineups.Loaded) putWeek(week, WeekLineups.Loading)
        val gen = generation
        val net = client(s)
        viewModelScope.launch {
            val result = try {
                val rosters = withContext(Dispatchers.IO) {
                    net.weekRosters(league, week, data.proTeams, data.ratings, fresh = fresh)
                        .let { if (week == league.currentWeek) it.withGames(data.scoreboard) else it }
                }
                if (rosters.isNotEmpty()) WeekLineups.Loaded(rosters) else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            putWeek(week, result
                ?: (existing as? WeekLineups.Loaded)
                ?: fromBox?.let { WeekLineups.Loaded(it) }
                ?: WeekLineups.Unavailable("ESPN didn't return lineups for week $week."), gen)
        }
    }

    // --- Lineup changes -----------------------------------------------------------------------

    /**
     * Sends lineup [moves] for [teamId] to ESPN, then re-fetches just that week
     * (not the whole league) so the app shows what ESPN has.
     */
    fun submitLineup(teamId: Int, week: Int, scoringPeriod: Int, moves: List<LineupMove>) {
        val s = _settings.value
        if (s.useDemo) {
            _saveState.value = SaveState.Failed("This is the demo league, so there's no ESPN team to save to.")
            return
        }
        if (moves.isEmpty() || _saveState.value == SaveState.Saving) return
        _saveState.value = SaveState.Saving
        val net = client(s)
        viewModelScope.launch {
            _saveState.value = try {
                val msg = withContext(Dispatchers.IO) { net.submitLineup(teamId, scoringPeriod, moves) }
                SaveState.Saved(msg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                SaveState.Failed(e.message ?: "Couldn't save the lineup.")
            }
            if (_saveState.value is SaveState.Saved) {
                val d = loaded
                if (d != null && week == d.league.currentWeek) refreshLive(force = true) else requestWeek(week, fresh = true)
            }
        }
    }

    fun clearSaveState() {
        if (_saveState.value != SaveState.Saving) _saveState.value = SaveState.Idle
    }

    // --- Settings and sign-in ---------------------------------------------------------------

    fun updateSettings(s: Settings) {
        val prev = _settings.value
        // A team picked in one league means nothing in another.
        val next = if (s.leagueId != prev.leagueId || s.useDemo != prev.useDemo) s.copy(myTeamId = 0) else s
        store.save(next)
        _settings.value = next
        if (next.leagueId != prev.leagueId || next.year != prev.year || next.useDemo != prev.useDemo) {
            _state.value = UiState.Loading("Loading…") // don't flash the old league
        }
        load()
    }

    /** Remembers which team is the user's; no reload needed. */
    fun setMyTeam(teamId: Int) {
        val s = _settings.value.copy(myTeamId = teamId)
        store.save(s)
        _settings.value = s
    }

    /** Forgets every saved ESPN response (on sign-out). */
    fun clearCache() {
        cacheRoot.deleteRecursively()
    }

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
}
