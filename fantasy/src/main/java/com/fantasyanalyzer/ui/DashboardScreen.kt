@file:OptIn(ExperimentalMaterial3Api::class)

package com.fantasyanalyzer.ui

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
    CompositionLocalProvider(LocalOpenPlayer provides { p: Player -> sheetPlayer = p }) {
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
                    6 -> luckTab(d)
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
                StatCard("Power #1", power.team, "${fmt1(power.power)} power", Modifier.weight(1f))
                StatCard("Most points", pf.team, "${fmt1(pf.pointsFor)} PF", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Luckiest", lucky.team, "${signed(lucky.luck)} wins", Modifier.weight(1f), Good)
                StatCard("Unluckiest", unlucky.team, "${signed(unlucky.luck)} wins", Modifier.weight(1f), Bad)
            }
        }
        SectionTitle("Standings", "All-play: record if you'd played every team every week. Luck: wins above that expectation.")
    }
    items(st, key = { it.teamId }) { r ->
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.rank}", Modifier.width(28.dp), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f)) {
                    Text(r.team, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.owner.isNotBlank()) {
                        Text(r.owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(r.record.toString(), fontWeight = FontWeight.SemiBold)
                    Text("${fmt1(r.pointsFor)} PF · ${fmt1(r.pointsAgainst)} PA", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.padding(start = 28.dp, top = 2.dp)) {
                Text(
                    "All-play ${r.allPlay} · xW ${fmt1(r.expectedWins)} · Luck ",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(signed(r.luck), style = MaterialTheme.typography.bodySmall, color = if (r.luck >= 0) Good else Bad)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun StatCard(label: String, value: String, detail: String, modifier: Modifier = Modifier, detailColor: Color? = null) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
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
    }
    items(rows, key = { it.teamId }) { r ->
        val move = r.rank - r.powerRank
        BarRow(
            label = r.team + when { move > 0 -> " ▲$move"; move < 0 -> " ▼${-move}"; else -> "" },
            fraction = (r.power / 100).toFloat(),
            valueText = fmt1(r.power),
            leading = "#${r.powerRank}",
        )
    }
    item {
        SectionTitle("Form", "Season average vs. last $RECENT_WEEKS weeks")
    }
    items(rows.sortedByDescending { it.recentAvg - it.avg }, key = { "form${it.teamId}" }) { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(r.team, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${fmt1(r.avg)} → ${fmt1(r.recentAvg)}", style = MaterialTheme.typography.bodySmall)
            Text(
                signed(r.recentAvg - r.avg), Modifier.width(56.dp), textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall, color = if (r.recentAvg >= r.avg) Good else Bad,
            )
        }
    }
}

// --- Luck ---------------------------------------------------------------------------

private fun LazyListScope.luckTab(d: Dashboard) {
    val rows = d.standings.sortedByDescending { it.luck }
    val maxAbs = rows.maxOf { kotlin.math.abs(it.luck) }
    item {
        SectionTitle("Wins above expected", "Actual wins minus the wins your weekly scores would earn on average.")
    }
    items(rows, key = { it.teamId }) { r ->
        DivergingBarRow(r.team, r.luck, maxAbs, signed(r.luck))
    }
    item {
        SectionTitle(
            "Schedule swap",
            "Wins each team (row) would have with another team's (column) schedule. " +
                "The diagonal is the real record. Scroll sideways.",
        )
        val m = d.scheduleSwap
        val abbrevs = m.teamIds.map { d.league.teams[it]?.abbrev?.ifBlank { null } ?: d.league.teamName(it).take(4) }
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
        Spacer(Modifier.height(8.dp))
        Text(
            m.names.zip(abbrevs).joinToString(" · ") { (n, a) -> "$a = $n" },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    }
    items(d.efficiencySummary) { s ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Row {
                Text(s.team, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(String.format(Locale.US, "%.1f%%", s.efficiency * 100), fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(progress = { s.efficiency.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text("${fmt1(s.leftOnBench)} pts left on bench", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        SectionTitle("Record with perfect lineups", "If both teams had started their best players every week.")
    }
    items(d.optimalRecord) { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(r.team, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${r.actualWins} → ${r.optimalWins} W", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (r.diff > 0) "+${r.diff}" else r.diff.toString(), Modifier.width(40.dp), textAlign = TextAlign.End,
                color = when { r.diff > 0 -> Bad; r.diff < 0 -> Good; else -> MaterialTheme.colorScheme.onSurfaceVariant },
            )
        }
    }
    item { SectionTitle("Worst lineup decisions") }
    items(d.efficiency.sortedByDescending { it.leftOnBench }.take(10), key = { "w${it.week}-${it.teamId}" }) { e ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Row {
                Text("Wk ${e.week} · ${e.team}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("−${fmt1(e.leftOnBench)}", color = Bad)
            }
            Text(
                "Scored ${fmt1(e.actual)} of a possible ${fmt1(e.optimal)}" +
                    if (e.topBenched.isNotBlank()) " · benched ${e.topBenched}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    item {
        SectionTitle("Inspect a week")
        LineupInspector(d)
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
        Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.padding(vertical = 4.dp)) {
        listOf("Player" to 1f, "Pos" to 0.35f, "Yours" to 0.45f, "Best" to 0.45f, "Pts" to 0.4f).forEach { (h, w) ->
            Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    roster.sortedByDescending { it.points }.forEach { p ->
        val best = optSlot[p] ?: "BE"
        val mine = slotName(p.slotId)
        val wrong = (best == "BE") != (mine == "BE" || mine == "IR")
        Row(Modifier.padding(vertical = 3.dp)) {
            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.position, Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall)
            Text(mine, Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall,
                color = if (wrong) Bad else MaterialTheme.colorScheme.onSurface)
            Text(best, Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall)
            Text(fmt1(p.points), Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall)
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
                FilterChip(selected = selected == r.team, onClick = { selected = r.team }, label = { Text(r.team) })
            }
        }
        LineChart(byTeam, selected, Modifier.padding(top = 12.dp))
        val games = d.games.filter { it.team == selected }.sortedBy { it.week }
        Column(Modifier.padding(top = 12.dp)) {
            games.forEach { g ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("Wk ${g.week}", Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
                    Text("${g.result} ${fmt1(g.points)}–${fmt1(g.oppPoints)}", Modifier.width(120.dp),
                        style = MaterialTheme.typography.bodySmall, color = if (g.result == 'W') Good else if (g.result == 'L') Bad else Color.Unspecified)
                    Text("vs ${g.opponent}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        SectionTitle("Consistency", "Average score ± week-to-week standard deviation. Low spread means fewer duds.")
    }
    val maxAvg = d.standings.maxOf { it.avg }
    items(d.standings.sortedByDescending { it.avg }, key = { "c${it.teamId}" }) { r ->
        BarRow(r.team, (r.avg / maxAvg).toFloat(), "${r.avg.toInt()}±${r.stdDev.toInt()}")
    }
}

// --- Head to head -------------------------------------------------------------------

private fun LazyListScope.headToHeadTab(d: Dashboard) {
    item {
        SectionTitle("Head-to-head", "Row team's record against the column team this season (playoffs included). Scroll sideways.")
        val m = d.headToHead
        val abbrevs = m.teamIds.map { d.league.teams[it]?.abbrev?.ifBlank { null } ?: d.league.teamName(it).take(4) }
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
        Spacer(Modifier.height(8.dp))
        Text(
            m.names.zip(abbrevs).joinToString(" · ") { (n, a) -> "$a = $n" },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Records ------------------------------------------------------------------------

private fun LazyListScope.recordsTab(d: Dashboard) {
    d.notable.forEach { (title, games) ->
        item(key = "t$title") { SectionTitle(title) }
        items(games, key = { "$title${it.week}-${it.teamId}" }) { g -> GameRow(g, showMargin = "game" in title.lowercase() || "blowout" in title.lowercase() || "loss" in title.lowercase()) }
    }
}

@Composable
private fun GameRow(g: TeamGame, showMargin: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Wk ${g.week}", Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f)) {
            Text("${g.team} ${fmt1(g.points)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("vs ${g.opponent} ${fmt1(g.oppPoints)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (showMargin) "by ${fmt1(kotlin.math.abs(g.margin))}" else g.result.toString(),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
