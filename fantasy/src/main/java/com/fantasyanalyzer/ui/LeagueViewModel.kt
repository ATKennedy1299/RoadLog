package com.fantasyanalyzer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyanalyzer.analysis.Analysis
import com.fantasyanalyzer.analysis.EfficiencyRow
import com.fantasyanalyzer.analysis.EfficiencySummary
import com.fantasyanalyzer.analysis.Matrix
import com.fantasyanalyzer.analysis.OptimalRecordRow
import com.fantasyanalyzer.analysis.Record
import com.fantasyanalyzer.analysis.StandingRow
import com.fantasyanalyzer.analysis.TeamGame
import com.fantasyanalyzer.data.EspnClient
import com.fantasyanalyzer.data.EspnException
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

/** Everything the dashboard shows, computed once per load. */
class Dashboard(val data: LeagueData) {
    val league = data.league
    val standings: List<StandingRow> = Analysis.standings(league)
    val games: List<TeamGame> = Analysis.teamWeeks(league)
    val headToHead: Matrix<Record?> = Analysis.headToHead(league)
    val scheduleSwap: Matrix<Double> = Analysis.scheduleSwap(league)
    val notable: List<Pair<String, List<TeamGame>>> = Analysis.notableGames(league)
    val efficiency: List<EfficiencyRow> = Analysis.lineupEfficiency(league, data.boxScores)
    val efficiencySummary: List<EfficiencySummary> = Analysis.efficiencySummary(efficiency)
    val optimalRecord: List<OptimalRecordRow> = Analysis.optimalRecord(league, efficiency)
}

sealed interface UiState {
    data object NeedsSetup : UiState
    data class Loading(val message: String) : UiState
    data class Loaded(val dashboard: Dashboard) : UiState
    data class Error(val message: String) : UiState
}

class LeagueViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _state = MutableStateFlow<UiState>(UiState.Loading("Loading…"))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun updateSettings(s: Settings) {
        store.save(s)
        _settings.value = s
        load()
    }

    fun load() {
        val s = _settings.value
        if (!s.useDemo && !s.hasLeague) {
            _state.value = UiState.NeedsSetup
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading(if (s.useDemo) "Building demo league…" else "Connecting to ESPN…")
            _state.value = try {
                val dashboard = withContext(Dispatchers.IO) {
                    val data = if (s.useDemo) {
                        SampleData.generate()
                    } else {
                        EspnClient(s.leagueId.toLong(), s.year, s.espnS2, s.swid).loadAll { msg ->
                            _state.value = UiState.Loading(msg)
                        }
                    }
                    Dashboard(data)
                }
                UiState.Loaded(dashboard)
            } catch (e: EspnException) {
                UiState.Error(e.message ?: "Couldn't load the league.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error("Something went wrong reading the league: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
