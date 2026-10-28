@file:OptIn(ExperimentalMaterial3Api::class)

package com.fantasyanalyzer.ui

import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fantasyanalyzer.analysis.Analysis
import com.fantasyanalyzer.analysis.RECENT_WEEKS
import com.fantasyanalyzer.analysis.TeamGame
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.SeasonForm
import com.fantasyanalyzer.data.Settings
import androidx.compose.foundation.background
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.fantasyanalyzer.data.slotName
import java.util.Locale

private val TABS = listOf(
    "Standings", "Matchup", "My Team", "Waivers", "Playoffs", "Power", "Luck", "Lineups", "Scoring", "Head-to-Head", "Records",
)

@Composable
fun DashboardScreen(
    state: UiState,
    settings: Settings,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onTryDemo: () -> Unit,
    onSetMyTeam: (Int) -> Unit,
    weekLineups: Map<Int, WeekLineups>,
    onRequestWeek: (Int) -> Unit,
    lineupActions: LineupActions,
    liveMode: Boolean,
    onLiveMode: (Boolean) -> Unit,
    onLiveTick: () -> Unit,
    playerWeeks: Map<Long, Map<Int, com.fantasyanalyzer.data.WeekLine>>,
    onRequestPlayer: (Long) -> Unit,
    waiverActions: WaiverActions,
) {
    val dashboard = (state as? UiState.Loaded)?.dashboard

    // Live mode: refresh the current week every minute while games are on (every 5 otherwise),
    // only while the app is on screen.
    if (liveMode && dashboard != null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val inProgress by rememberUpdatedState(dashboard.gamesInProgress)
        val tick by rememberUpdatedState(onLiveTick)
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    tick()
                    delay(if (inProgress) 60_000L else 300_000L)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(dashboard?.league?.name ?: "Fantasy Analyzer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (dashboard != null) {
                            val loadedState = state as UiState.Loaded
                            Text(
                                when {
                                    loadedState.refreshing -> "Updating…"
                                    else -> freshness(dashboard.data.fetchedAt)
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                },
                actions = {
                    if (dashboard != null && !settings.useDemo) {
                        TextButton(onClick = { onLiveMode(!liveMode) }) {
                            Text(
                                if (liveMode) "● LIVE" else "LIVE",
                                fontWeight = FontWeight.Bold,
                                color = if (liveMode) Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "League setup") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (state) {
                is UiState.Loading -> Centered {
                    CircularProgressIndicator()
                    Text(state.message, Modifier.padding(top = 16.dp))
                }
                is UiState.Error -> Centered {
                    Text("Couldn't load your league", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, Modifier.padding(16.dp), textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenSettings) { Text(if (state.authProblem) "Sign in again" else "League setup") }
                        Button(onClick = onRefresh) { Text("Try again") }
                    }
                }
                UiState.NeedsSetup -> Centered {
                    Text("🏈", style = MaterialTheme.typography.displayMedium)
                    Text("Analyze your ESPN league", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Power rankings, luck, lineup efficiency, schedule what-ifs and more.",
                        Modifier.padding(16.dp), textAlign = TextAlign.Center,
                    )
                    Button(onClick = onOpenSettings) { Text("Connect my league") }
                    OutlinedButton(onClick = onTryDemo, Modifier.padding(top = 8.dp)) { Text("Try a demo league") }
                }
                is UiState.Loaded -> Column {
                    state.refreshError?.let {
                        Text(
                            "Showing saved data: $it",
                            Modifier.fillMaxWidth().background(Bad.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.dashboard.standings.isEmpty()) {
                        Centered { Text("No completed games yet this season.") }
                    } else {
                        Tabs(state.dashboard, state.dashboard.myTeamId(settings), onSetMyTeam, weekLineups, onRequestWeek, lineupActions,
                            playerWeeks, onRequestPlayer, waiverActions)
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Tabs(
    d: Dashboard,
    myTeam: Int?,
    onSetMyTeam: (Int) -> Unit,
    weekLineups: Map<Int, WeekLineups>,
    onRequestWeek: (Int) -> Unit,
    lineupActions: LineupActions,
    playerWeeks: Map<Long, Map<Int, com.fantasyanalyzer.data.WeekLine>>,
    onRequestPlayer: (Long) -> Unit,
    waiverActions: WaiverActions,
) {
    // Opens on Matchup (tab order unchanged).
    var tab by rememberSaveable { mutableIntStateOf(TABS.indexOf("Matchup")) }
    var sheetPlayer by remember { mutableStateOf<Player?>(null) }
    sheetPlayer?.let { p ->
        LaunchedEffect(p.id) { onRequestPlayer(p.id) }
        PlayerSheet(p, d, playerWeeks[p.id]) { sheetPlayer = null }
    }
    val forms = remember(d.data.outlook?.seasons, d.league.currentScoringPeriod) {
        d.data.outlook?.seasons.orEmpty().mapNotNull { (id, w) -> SeasonForm.of(w, d.league.currentScoringPeriod)?.let { id to it } }.toMap()
    }
    var scorePlayer by remember { mutableStateOf<Player?>(null) }
    scorePlayer?.let { p -> ScoreBreakdownDialog(p, d.league.scoring) { scorePlayer = null } }
    CompositionLocalProvider(
        LocalOpenPlayer provides { p: Player -> sheetPlayer = p },
        LocalOpenScore provides { p: Player -> scorePlayer = p },
        LocalSeasonForms provides forms,
    ) {
        Column {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                when (tab) {
                    0 -> standingsTab(d)
                    1 -> item(key = "matchup") { MatchupTab(d, myTeam, weekLineups, onRequestWeek) }
                    2 -> item(key = "myteam") { MyTeamScreen(d, myTeam, onSetMyTeam, lineupActions, weekLineups, onRequestWeek) }
                    3 -> item(key = "waivers") { WaiversScreen(d, myTeam, onSetMyTeam, waiverActions) }
                    4 -> playoffsTab(d, myTeam)
                    5 -> powerTab(d)
                    6 -> luckTab(d, myTeam)
                    7 -> lineupsTab(d)
                    8 -> scoringTab(d)
                    9 -> headToHeadTab(d)
                    else -> recordsTab(d)
                }
            }
        }
    }
}

/** "Updated just now" / "Updated 12 min ago" / "Updated 3 h ago". */
private fun freshness(fetchedAt: Long): String {
    if (fetchedAt <= 0) return "Saved data"
    val mins = (System.currentTimeMillis() - fetchedAt) / 60_000
    return when {
        mins < 1 -> "Updated just now"
        mins < 60 -> "Updated $mins min ago"
        mins < 48 * 60 -> "Updated ${mins / 60} h ago"
        else -> "Updated ${mins / (24 * 60)} days ago"
    }
}

private fun signed(d: Double) = String.format(Locale.US, "%+.1f", d)

// --- Shared -----------------------------------------------------------------------

private fun Dashboard.logo(teamId: Int): String? = league.teams[teamId]?.logo?.ifBlank { null }

private fun Dashboard.logoByName(name: String): String? =
    league.teams.values.firstOrNull { it.name == name }?.logo?.ifBlank { null }

private fun Dashboard.abbrev(teamId: Int): String =
    league.teams[teamId]?.abbrev?.ifBlank { null } ?: league.teamName(teamId).take(4)

/** Team logo, name and an optional second line, the way every team row starts. */
@Composable
private fun TeamCell(name: String, logo: String?, sub: String? = null, modifier: Modifier = Modifier, bold: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(logo, name, 32.dp, Modifier.padding(end = 10.dp), CircleShape)
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Abbreviation key under a team-by-team grid. */
@Composable
private fun AbbrevKey(names: List<String>, abbrevs: List<String>) {
    Text(
        names.zip(abbrevs).joinToString(" · ") { (n, a) -> "$a = $n" },
        Modifier.padding(12.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- Standings -------------------------------------------------------------------

private fun LazyListScope.standingsTab(d: Dashboard) {
    val st = d.standings
    item {
        val power = st.minBy { it.powerRank }
        val pf = st.maxBy { it.pointsFor }
        val lucky = st.maxBy { it.luck }
        val unlucky = st.minBy { it.luck }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Power #1", power.team, "${fmt1(power.power)} power", d.logo(power.teamId), Modifier.weight(1f))
                StatCard("Most points", pf.team, "${fmt1(pf.pointsFor)} PF", d.logo(pf.teamId), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Luckiest", lucky.team, "${signed(lucky.luck)} wins", d.logo(lucky.teamId), Modifier.weight(1f), Good)
                StatCard("Unluckiest", unlucky.team, "${signed(unlucky.luck)} wins", d.logo(unlucky.teamId), Modifier.weight(1f), Bad)
            }
        }
        SectionTitle("Standings", "All-play: record if you'd played every team every week. Luck: wins above that expectation.")
        Panel {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                PanelHeading("Team", Modifier.weight(1f).padding(start = 28.dp))
                PanelHeading("Record", align = TextAlign.End)
            }
            PanelDivider()
            st.forEachIndexed { i, r ->
                if (i > 0) PanelDivider()
                // A heavier line under the last playoff spot.
                if (i == d.league.playoffTeamCount && i < st.size) {
                    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.rank}", Modifier.width(28.dp), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TeamCell(r.team, d.logo(r.teamId), r.owner, Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(r.record.toString(), fontWeight = FontWeight.Bold)
                            Text("${fmt1(r.pointsFor)} PF · ${fmt1(r.pointsAgainst)} PA", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(start = 70.dp, top = 4.dp)) {
                        Text(
                            "All-play ${r.allPlay} · xW ${fmt1(r.expectedWins)} · Luck ",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(signed(r.luck), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                            color = if (r.luck >= 0) Good else Bad)
                    }
                }
            }
        }
        if (d.league.playoffTeamCount < st.size) {
            Text("Line marks the playoff cut.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    detail: String,
    logo: String?,
    modifier: Modifier = Modifier,
    detailColor: Color? = null,
) {
    Panel(modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(logo, value, 36.dp, Modifier.padding(end = 10.dp), CircleShape)
            Column {
                PanelHeading(label)
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = detailColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- Power -------------------------------------------------------------------------

private fun LazyListScope.powerTab(d: Dashboard) {
    val rows = d.standings.sortedBy { it.powerRank }
    item {
        SectionTitle(
            "Power rankings",
            "50% all-play win rate · 30% season scoring average · 20% last $RECENT_WEEKS weeks. " +
                "Arrows compare with the standings.",
        )
        Panel(Modifier.padding(bottom = 4.dp)) {
            Spacer(Modifier.height(6.dp))
            rows.forEach { r ->
                val move = r.rank - r.powerRank
                BarRow(
                    label = r.team + when { move > 0 -> " ▲$move"; move < 0 -> " ▼${-move}"; else -> "" },
                    fraction = (r.power / 100).toFloat(),
                    valueText = fmt1(r.power),
                    leading = "#${r.powerRank}",
                    logo = d.logo(r.teamId),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        SectionTitle("Form", "Season average → last $RECENT_WEEKS weeks")
        Panel {
            rows.sortedByDescending { it.recentAvg - it.avg }.forEachIndexed { i, r ->
                if (i > 0) PanelDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamCell(r.team, d.logo(r.teamId), modifier = Modifier.weight(1f), bold = false)
                    Text("${fmt1(r.avg)} → ${fmt1(r.recentAvg)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        signed(r.recentAvg - r.avg), Modifier.width(56.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall, color = if (r.recentAvg >= r.avg) Good else Bad,
                    )
                }
            }
        }
    }
}

// --- Luck ---------------------------------------------------------------------------

private fun LazyListScope.luckTab(d: Dashboard, myTeam: Int?) {
    val rows = d.standings.sortedByDescending { it.luck }
    val maxAbs = rows.maxOf { kotlin.math.abs(it.luck) }
    item {
        SectionTitle("Points for vs. against", "Right scores more, lower gives up less. Dashed lines are league averages. Tap a team.")
        Panel { PointsChart(d, myTeam) }

        SectionTitle("Wins above expected", "Actual wins minus the wins your weekly scores would earn on average.")
        Panel {
            Spacer(Modifier.height(6.dp))
            rows.forEach { r -> DivergingBarRow(r.team, r.luck, maxAbs, signed(r.luck), d.logo(r.teamId)) }
            Spacer(Modifier.height(6.dp))
        }
        SectionTitle(
            "Schedule swap",
            "Wins each team (row) would have with another team's (column) schedule. " +
                "The diagonal is the real record. Scroll sideways.",
        )
        val m = d.scheduleSwap
        val abbrevs = m.teamIds.map { d.abbrev(it) }
        Panel {
            Box(Modifier.padding(8.dp)) {
                MatrixTable(
                    matrix = m,
                    abbrevs = abbrevs,
                    cellText = { v -> if (v % 1.0 == 0.0) v.toInt().toString() else fmt1(v) },
                    cellColor = { r, c, v ->
                        val real = m.cells[r][r]
                        when {
                            r == c -> MaterialTheme.colorScheme.surfaceVariant
                            v > real -> Good.copy(alpha = 0.18f)
                            v < real -> Bad.copy(alpha = 0.18f)
                            else -> Color.Transparent
                        }
                    },
                )
            }
            PanelDivider()
            AbbrevKey(m.names, abbrevs)
        }
    }
}

// --- Lineups ------------------------------------------------------------------------

private fun LazyListScope.lineupsTab(d: Dashboard) {
    if (d.efficiency.isEmpty()) {
        item { Text("No lineup data available.", Modifier.padding(top = 24.dp)) }
        return
    }
    item {
        SectionTitle("Manager efficiency", "Points scored as a share of the best lineup each team could have set.")
        Panel {
            d.efficiencySummary.forEachIndexed { i, s ->
                if (i > 0) PanelDivider()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TeamCell(s.team, d.logoByName(s.team), "${fmt1(s.leftOnBench)} pts left on bench", Modifier.weight(1f))
                        Text(String.format(Locale.US, "%.1f%%", s.efficiency * 100), fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { s.efficiency.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 6.dp).clip(RoundedCornerShape(4.dp)),
                        color = when { s.efficiency >= 0.9 -> Good; s.efficiency >= 0.8 -> Warn; else -> Bad },
                    )
                }
            }
        }
        SectionTitle("Record with perfect lineups", "If both teams had started their best players every week.")
        Panel {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                PanelHeading("Team", Modifier.weight(1f))
                PanelHeading("Wins", Modifier.width(80.dp), TextAlign.End)
                PanelHeading("+/-", Modifier.width(40.dp), TextAlign.End)
            }
            PanelDivider()
            d.optimalRecord.forEachIndexed { i, r ->
                if (i > 0) PanelDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamCell(r.team, d.logoByName(r.team), modifier = Modifier.weight(1f), bold = false)
                    Text("${r.actualWins} → ${r.optimalWins}", Modifier.width(80.dp), textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (r.diff > 0) "+${r.diff}" else r.diff.toString(), Modifier.width(40.dp), textAlign = TextAlign.End,
                        fontWeight = FontWeight.SemiBold,
                        color = when { r.diff > 0 -> Bad; r.diff < 0 -> Good; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    )
                }
            }
        }
        SectionTitle("Worst lineup decisions")
        Panel {
            d.efficiency.sortedByDescending { it.leftOnBench }.take(10).forEachIndexed { i, e ->
                if (i > 0) PanelDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("WK ${e.week}", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TeamCell(
                        e.team, d.logo(e.teamId),
                        "Scored ${fmt1(e.actual)} of ${fmt1(e.optimal)}" + if (e.topBenched.isNotBlank()) " · benched ${e.topBenched}" else "",
                        Modifier.weight(1f),
                    )
                    Text("−${fmt1(e.leftOnBench)}", color = Bad, fontWeight = FontWeight.Bold)
                }
            }
        }
        SectionTitle("Inspect a week")
        Panel { Column(Modifier.padding(12.dp)) { LineupInspector(d) } }
    }
}

@Composable
private fun LineupInspector(d: Dashboard) {
    val weeks = d.data.boxScores.keys.sorted()
    var weekIdx by remember { mutableIntStateOf(weeks.lastIndex.coerceAtLeast(0)) }
    var teamId by remember { mutableStateOf(d.standings.first().teamId) }
    val week = weeks.getOrNull(weekIdx) ?: return
    val rosters = d.data.boxScores.getValue(week)
    val teamIds = rosters.keys.sortedBy { d.league.teamName(it) }
    val tid = if (teamId in rosters) teamId else teamIds.first()

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("Week", weeks.map { it.toString() }, weekIdx, { weekIdx = it }, Modifier.weight(0.8f))
        Picker("Team", teamIds.map { d.league.teamName(it) }, teamIds.indexOf(tid), { teamId = teamIds[it] }, Modifier.weight(1.2f))
    }
    val roster = rosters.getValue(tid)
    val opt = remember(week, tid) { Analysis.optimalLineup(roster, d.league.lineupSlotCounts) }
    val optSlot = opt.picks.associate { (slot, p) -> p to slot }
    val actual = roster.filter { it.started }.sumOf { it.points }

    Text(
        "Scored ${fmt1(actual)} · best possible ${fmt1(maxOf(opt.total, actual))}",
        Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
    )
    Row(Modifier.padding(vertical = 4.dp)) {
        listOf("Player" to 1f, "Pos" to 0.35f, "Yours" to 0.45f, "Best" to 0.45f, "Pts" to 0.4f).forEach { (h, w) ->
            PanelHeading(h, Modifier.weight(w))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    roster.sortedByDescending { it.points }.forEach { p ->
        val best = optSlot[p] ?: "BE"
        val mine = slotName(p.slotId)
        val wrong = (best == "BE") != (mine == "BE" || mine == "IR")
        Row(Modifier.padding(vertical = 4.dp)) {
            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.position, Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(mine, Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall,
                fontWeight = if (wrong) FontWeight.Bold else FontWeight.Normal,
                color = if (wrong) Bad else MaterialTheme.colorScheme.onSurface)
            Text(best, Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall)
            Text(fmt1(p.points), Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

// --- Scoring ------------------------------------------------------------------------

private fun LazyListScope.scoringTab(d: Dashboard) {
    item {
        val byTeam = d.games.groupBy { it.team }.mapValues { (_, g) -> g.map { it.week to it.points } }
        var selected by rememberSaveable { mutableStateOf(d.standings.first().team) }
        SectionTitle("Weekly scores", "Tap a team to highlight it.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            d.standings.forEach { r ->
                FilterChip(
                    selected = selected == r.team, onClick = { selected = r.team }, label = { Text(r.team) },
                    leadingIcon = { TeamLogo(d.logo(r.teamId), r.team, 18.dp, shape = CircleShape) },
                )
            }
        }
        Panel(Modifier.padding(top = 8.dp)) { LineChart(byTeam, selected, Modifier.padding(12.dp)) }
        val games = d.games.filter { it.team == selected }.sortedBy { it.week }
        if (games.isNotEmpty()) {
            SectionTitle("$selected by week")
            Panel {
                games.forEachIndexed { i, g ->
                    if (i > 0) PanelDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("WK ${g.week}", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ResultBadge(g.result)
                        Text("${fmt1(g.points)}–${fmt1(g.oppPoints)}", Modifier.width(104.dp).padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        TeamLogo(d.logo(g.oppId), g.opponent, 20.dp, Modifier.padding(end = 6.dp), CircleShape)
                        Text(g.opponent, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        SectionTitle("Consistency", "Average score ± week-to-week standard deviation. Low spread means fewer duds.")
        val maxAvg = d.standings.maxOf { it.avg }
        Panel {
            Spacer(Modifier.height(6.dp))
            d.standings.sortedByDescending { it.avg }.forEach { r ->
                BarRow(r.team, (r.avg / maxAvg).toFloat(), "${r.avg.toInt()}±${r.stdDev.toInt()}", logo = d.logo(r.teamId))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** W / L / T in a small colored square. */
@Composable
private fun ResultBadge(result: Char) {
    val color = when (result) { 'W' -> Good; 'L' -> Bad; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Text(result.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

// --- Head to head -------------------------------------------------------------------

private fun LazyListScope.headToHeadTab(d: Dashboard) {
    item {
        SectionTitle("Head-to-head", "Row team's record against the column team this season (playoffs included). Scroll sideways.")
        val m = d.headToHead
        val abbrevs = m.teamIds.map { d.abbrev(it) }
        Panel {
            Box(Modifier.padding(8.dp)) {
                MatrixTable(
                    matrix = m,
                    abbrevs = abbrevs,
                    cellText = { r -> r?.let { if (it.w + it.l + it.t == 0) "" else it.toString() } ?: "—" },
                    cellColor = { _, _, r ->
                        when {
                            r == null -> MaterialTheme.colorScheme.surfaceVariant
                            r.w > r.l -> Good.copy(alpha = 0.18f)
                            r.l > r.w -> Bad.copy(alpha = 0.18f)
                            else -> Color.Transparent
                        }
                    },
                )
            }
            PanelDivider()
            AbbrevKey(m.names, abbrevs)
        }
    }
}

// --- Records ------------------------------------------------------------------------

private fun LazyListScope.recordsTab(d: Dashboard) {
    d.notable.forEach { (title, games) ->
        item(key = "t$title") {
            SectionTitle(title)
            val margin = "game" in title.lowercase() || "blowout" in title.lowercase() || "loss" in title.lowercase()
            Panel {
                games.forEachIndexed { i, g ->
                    if (i > 0) PanelDivider()
                    GameRow(d, g, margin)
                }
            }
        }
    }
}

@Composable
private fun GameRow(d: Dashboard, g: TeamGame, showMargin: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("WK ${g.week}", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TeamCell("${g.team} ${fmt1(g.points)}", d.logo(g.teamId), "vs ${g.opponent} ${fmt1(g.oppPoints)}", Modifier.weight(1f))
        if (showMargin) {
            Text("by ${fmt1(kotlin.math.abs(g.margin))}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        } else {
            ResultBadge(g.result)
        }
    }
}

/**
 * Every team as its logo: points scored across, points allowed up. The average
 * lines split it into good/bad (scoring) and lucky/unlucky (what opponents scored).
 */
@Composable
private fun PointsChart(d: Dashboard, myTeam: Int?) {
    val rows = d.standings
    var selected by remember(myTeam) { mutableStateOf(myTeam ?: rows.first().teamId) }
    val pfLo = rows.minOf { it.pointsFor }
    val pfHi = rows.maxOf { it.pointsFor }
    val paLo = rows.minOf { it.pointsAgainst }
    val paHi = rows.maxOf { it.pointsAgainst }
    val pfAvg = rows.map { it.pointsFor }.average()
    val paAvg = rows.map { it.pointsAgainst }.average()
    // 0..1 within the league's range, padded so logos don't sit on the edges.
    fun x(v: Double) = if (pfHi > pfLo) (0.08 + 0.84 * (v - pfLo) / (pfHi - pfLo)).toFloat() else 0.5f
    fun y(v: Double) = if (paHi > paLo) (0.92 - 0.84 * (v - paLo) / (paHi - paLo)).toFloat() else 0.5f
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outline
    val logo = 30.dp

    Column(Modifier.padding(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp)) {
            val w = maxWidth
            val h = maxHeight
            Canvas(Modifier.matchParentSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                val ax = size.width * x(pfAvg)
                val ay = size.height * y(paAvg)
                drawLine(line, Offset(ax, 0f), Offset(ax, size.height), 1.5.dp.toPx(), pathEffect = dash)
                drawLine(line, Offset(0f, ay), Offset(size.width, ay), 1.5.dp.toPx(), pathEffect = dash)
            }
            // Quadrant names in the corners.
            val label = MaterialTheme.typography.labelSmall
            Text("Bad & unlucky", Modifier.align(Alignment.TopStart), style = label, color = Bad.copy(alpha = 0.8f))
            Text("Good & unlucky", Modifier.align(Alignment.TopEnd), style = label, color = Warn)
            Text("Bad & lucky", Modifier.align(Alignment.BottomStart), style = label, color = Warn)
            Text("Good & lucky", Modifier.align(Alignment.BottomEnd), style = label, color = Good)
            rows.forEach { r ->
                val mine = r.teamId == myTeam
                val chosen = r.teamId == selected
                val dot = if (chosen) logo + 8.dp else logo
                Box(
                    Modifier
                        .offset(
                            x = (w * x(r.pointsFor) - dot / 2).coerceIn(0.dp, w - dot),
                            y = (h * y(r.pointsAgainst) - dot / 2).coerceIn(0.dp, h - dot),
                        )
                        .size(dot)
                        .clip(CircleShape)
                        .border(
                            if (chosen || mine) 2.dp else 0.dp,
                            if (mine) MaterialTheme.colorScheme.primary else if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            CircleShape,
                        )
                        .clickable { selected = r.teamId },
                ) {
                    TeamLogo(d.logo(r.teamId), d.abbrev(r.teamId), dot, shape = CircleShape)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("← fewer points scored", style = MaterialTheme.typography.labelSmall, color = muted)
            Text("more →", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        rows.firstOrNull { it.teamId == selected }?.let { r ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val good = r.pointsFor >= pfAvg
            val lucky = r.pointsAgainst <= paAvg
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamCell(r.team, d.logo(r.teamId), "${r.record} · ${if (good) "Good" else "Bad"} & ${if (lucky) "lucky" else "unlucky"}",
                    Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${fmt1(r.pointsFor)} PF", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                        color = if (good) Good else Bad)
                    Text("${fmt1(r.pointsAgainst)} PA", style = MaterialTheme.typography.bodySmall, color = if (lucky) Good else Bad)
                }
            }
            Text(
                "League average ${fmt1(pfAvg)} PF · ${fmt1(paAvg)} PA",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = muted,
            )
        }
    }
}
