@file:OptIn(ExperimentalMaterial3Api::class)

package com.fantasyanalyzer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fantasyanalyzer.analysis.MatchupSide
import com.fantasyanalyzer.analysis.Matchups
import com.fantasyanalyzer.analysis.SlotRow
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.data.Matchup
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.RosterSpot

@Composable
internal fun MatchupTab(
    d: Dashboard,
    myTeam: Int?,
    weekLineups: Map<Int, WeekLineups>,
    onRequestWeek: (Int) -> Unit,
) {
    val league = d.league
    val weeks = league.matchups.map { it.week }.distinct().sorted()
    if (weeks.isEmpty()) {
        Text("No schedule yet.", Modifier.padding(top = 24.dp))
        return
    }
    val startWeek = league.currentWeek.coerceIn(weeks.first(), weeks.last())
    var week by rememberSaveable(league.id, league.year) { mutableIntStateOf(startWeek) }
    var selected by rememberSaveable(week) { mutableIntStateOf(0) }
    var showBench by rememberSaveable { mutableStateOf(false) }
    // Keyed on the dashboard too, so a refresh (which clears loaded weeks) asks again.
    LaunchedEffect(week, d) { onRequestWeek(week) }

    val matchups = Matchups.inWeek(league, week, myTeam)
    val now = remember(d, weekLineups[week]) { System.currentTimeMillis() }

    Column(Modifier.padding(top = 8.dp)) {
        // Week switcher
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { week = weeks.lastOrNull { it < week } ?: week }, enabled = week > weeks.first()) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Week $week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(weekLabel(week, league.currentWeek, matchups), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { week = weeks.firstOrNull { it > week } ?: week }, enabled = week < weeks.last()) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week")
            }
        }
        if (week != startWeek) {
            TextButton(onClick = { week = startWeek }, Modifier.align(Alignment.CenterHorizontally)) { Text("Back to this week") }
        }

        if (matchups.isEmpty()) {
            Text("No matchups this week.", Modifier.padding(top = 16.dp))
            return@Column
        }

        val lineups = weekLineups[week]
        val rosters = (lineups as? WeekLineups.Loaded)?.rosters
        val views = matchups.map { Matchups.view(league, it, rosters, now) }
        val idx = selected.coerceIn(0, views.lastIndex)

        // Every matchup that week, like ESPN's scoreboard strip
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            views.forEachIndexed { i, v ->
                ScoreChip(d, v.matchup, v.home, v.away, i == idx) { selected = i }
            }
        }

        val v = views[idx]
        MatchupHeader(d, v.matchup, v.home, v.away, v.homeWinProbability)

        when {
            lineups == null || lineups is WeekLineups.Loading -> Row(
                Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            lineups is WeekLineups.Unavailable -> Text(lineups.message, Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.starters.all { it.home == null && it.away == null } -> Text("No lineups set for this matchup yet.",
                Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Column(Modifier.padding(top = 12.dp)) {
                    v.starters.forEach { SlotLine(it, now) }
                    TextButton(onClick = { showBench = !showBench }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (showBench) "Hide bench" else "Show bench")
                    }
                    if (showBench) v.bench.forEach { SlotLine(it, now) }
                }
            }
        }
    }
}

private fun weekLabel(week: Int, current: Int, matchups: List<Matchup>): String {
    val playoff = if (matchups.any { it.isPlayoff }) "Playoffs · " else ""
    val status = when {
        matchups.isNotEmpty() && matchups.all { it.complete } -> "Final"
        week == current -> "This week"
        week > current -> "Upcoming"
        else -> "In progress"
    }
    return playoff + status
}

private fun abbrev(d: Dashboard, teamId: Int): String =
    d.league.teams[teamId]?.abbrev?.ifBlank { null } ?: d.league.teamName(teamId).take(4)

@Composable
private fun ScoreChip(d: Dashboard, m: Matchup, home: MatchupSide, away: MatchupSide?, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ChipLine(abbrev(d, m.homeId), home.actual, m.complete && m.winner == "HOME")
            if (away != null) ChipLine(abbrev(d, away.teamId), away.actual, m.complete && m.winner == "AWAY")
            else Text("Bye", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ChipLine(team: String, points: Double, won: Boolean) {
    Row(Modifier.width(96.dp)) {
        Text(team, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            fontWeight = if (won) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        Text(fmt1(points), style = MaterialTheme.typography.labelMedium, fontWeight = if (won) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun MatchupHeader(d: Dashboard, m: Matchup, home: MatchupSide, away: MatchupSide?, homeWin: Double?) {
    val records = d.standings.associate { it.teamId to it.record.toString() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row {
                HeaderSide(d, home, records[home.teamId], Alignment.Start, Modifier.weight(1f))
                if (away != null) HeaderSide(d, away, records[away.teamId], Alignment.End, Modifier.weight(1f))
                else Text("Bye week", Modifier.weight(1f), textAlign = TextAlign.End)
            }
            if (homeWin != null) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pct(homeWin), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                    LinearProgressIndicator(progress = { homeWin.toFloat() }, modifier = Modifier.weight(1f))
                    Text(if (m.complete) "  Final  " else "  Win %  ", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Away bar fills from the right
                    LinearProgressIndicator(
                        progress = { (1 - homeWin).toFloat() },
                        modifier = Modifier.weight(1f).scale(scaleX = -1f, scaleY = 1f),
                    )
                    Text(pct(1 - homeWin), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun HeaderSide(d: Dashboard, side: MatchupSide, record: String?, align: Alignment.Horizontal, modifier: Modifier) {
    val team = d.league.teams[side.teamId]
    Column(modifier, horizontalAlignment = align) {
        TeamLogo(team?.logo, team?.abbrev ?: "", 44.dp, Modifier.padding(bottom = 4.dp))
        Text(fmt1(side.actual), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        side.projected?.let {
            Text("Proj ${fmt1(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(d.league.teamName(side.teamId), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (align == Alignment.End) TextAlign.End else TextAlign.Start)
        Text(listOfNotNull(team?.owner?.ifBlank { null }, record).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SlotLine(row: SlotRow, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerCell(row.home, now, mirrored = false, Modifier.weight(1f))
        Text(row.slot, Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlayerCell(row.away, now, mirrored = true, Modifier.weight(1f))
    }
    HorizontalDivider()
}

/** A player: name and game on the outside, points and projection toward the middle (ESPN's layout). */
@Composable
private fun PlayerCell(spot: RosterSpot?, now: Long, mirrored: Boolean, modifier: Modifier) {
    if (spot == null) {
        Text("Empty", modifier, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (mirrored) TextAlign.End else TextAlign.Start)
        return
    }
    val p = spot.player
    val open = LocalOpenPlayer.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val info: @Composable (Modifier) -> Unit = { m ->
        Column(m, horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!mirrored) PlayerPhoto(p, 28.dp, Modifier.padding(end = 6.dp))
                Text(shortName(p.name), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                StatusChip(p)
                if (mirrored) PlayerPhoto(p, 28.dp, Modifier.padding(start = 6.dp))
            }
            Text(
                buildAnnotatedString { append("${p.proTeam} "); append(gameLine(p, now, muted)) },
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (p.game?.state == NflGame.IN) Good else muted,
                textAlign = if (mirrored) TextAlign.End else TextAlign.Start,
            )
            p.statLine?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = muted)
            }
        }
    }
    val points: @Composable () -> Unit = {
        Column(horizontalAlignment = if (mirrored) Alignment.Start else Alignment.End) {
            Text(if (p.gameStarted(now)) fmt1(p.pointsSoFar(now)) else "–", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Text(p.projected?.let { fmt1(it) } ?: "–", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(modifier.clickable { open(p) }, verticalAlignment = Alignment.CenterVertically) {
        if (mirrored) {
            points()
            info(Modifier.weight(1f).padding(start = 6.dp))
        } else {
            info(Modifier.weight(1f).padding(end = 6.dp))
            points()
        }
    }
}
