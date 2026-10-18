package com.fantasyanalyzer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FantasyTheme { App() }
        }
    }
}

private enum class Screen { Dashboard, Settings, Login }

@Composable
private fun App(vm: LeagueViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val leagueSearch by vm.leagueSearch.collectAsStateWithLifecycle()
    val weekLineups by vm.weekLineups.collectAsStateWithLifecycle()
    val saveState by vm.saveState.collectAsStateWithLifecycle()
    val liveMode by vm.liveMode.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Dashboard) }
    // Edits on the setup screen, kept while the user detours through the ESPN sign-in.
    var draft by remember(settings) { mutableStateOf(settings) }

    when (screen) {
        Screen.Dashboard -> DashboardScreen(
            state = state,
            settings = settings,
            onRefresh = vm::load,
            onOpenSettings = { screen = Screen.Settings },
            onTryDemo = { vm.updateSettings(settings.copy(useDemo = true)) },
            onSetMyTeam = vm::setMyTeam,
            weekLineups = weekLineups,
            onRequestWeek = { vm.requestWeek(it) },
            lineupActions = LineupActions(saveState, vm::submitLineup, vm::clearSaveState),
            liveMode = liveMode,
            onLiveMode = vm::setLiveMode,
            onLiveTick = { vm.refreshLive() },
        )
        Screen.Settings -> SettingsScreen(
            draft = draft,
            leagueSearch = leagueSearch,
            onChange = { draft = it },
            onFindLeagues = { vm.findLeagues(draft) },
            onSignIn = { screen = Screen.Login },
            onSignOut = {
                BrowserSession.clear()
                vm.clearCache()
                vm.clearLeagueSearch()
                draft = draft.copy(espnS2 = "", swid = "")
            },
            onSave = {
                vm.updateSettings(draft)
                screen = Screen.Dashboard
            },
            onBack = {
                draft = settings
                screen = Screen.Dashboard
            },
        )
        Screen.Login -> EspnLoginScreen(
            onDone = { login ->
                draft = draft.copy(
                    useDemo = false,
                    espnS2 = login.espnS2 ?: draft.espnS2,
                    swid = login.swid ?: draft.swid,
                    leagueId = login.leagueId ?: draft.leagueId,
                    year = login.season ?: draft.year,
                )
                if (login.signedIn) vm.findLeagues(draft)
                screen = Screen.Settings
            },
            onCancel = { screen = Screen.Settings },
        )
    }
}
