@file:OptIn(ExperimentalMaterial3Api::class)

package com.fantasyanalyzer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fantasyanalyzer.analysis.Matchups
import com.fantasyanalyzer.analysis.Outlook
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.data.BENCH
import com.fantasyanalyzer.data.IR
import com.fantasyanalyzer.data.LineupEditor
import com.fantasyanalyzer.data.LineupMove
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.slotName

/** What the My Team tab needs to send lineup changes. */
class LineupActions(
    val saveState: SaveState,
    val onSubmit: (teamId: Int, week: Int, scoringPeriod: Int, moves: List<LineupMove>) -> Unit,
    /** Clears a saved/failed message once the user starts a new edit. */
    val onDismiss: () -> Unit,
)

/** ESPN-style roster for any week, with lineup editing for weeks that haven't locked. */
@Composable
internal fun MyTeamScreen(
    d: Dashboard,
    myTeam: Int?,
    onPick: (Int) -> Unit,
    lineup: LineupActions,
    weekLineups: Map<Int, WeekLineups>,
    onRequestWeek: (Int) -> Unit,
) {
    val league = d.league
    TeamPicker(d, myTeam, onPick)
    if (myTeam == null) return

    val weeks = league.matchups.map { it.week }.distinct().sorted().ifEmpty { listOf(league.currentWeek) }
    val startWeek = league.currentWeek.coerceIn(weeks.first(), weeks.last())
    var week by rememberSaveable(league.id, league.year) { mutableIntStateOf(startWeek) }
    LaunchedEffect(week, d) { onRequestWeek(week) }

    // The current week comes straight from the dashboard so live refreshes show up here.
    val outlook = d.data.outlook
    val rosters: Map<Int, List<RosterSpot>>? =
        if (week == league.currentWeek && outlook != null) outlook.rosters
        else (weekLineups[week] as? WeekLineups.Loaded)?.rosters
    val roster = rosters?.get(myTeam)
    val now = remember(d, rosters) { System.currentTimeMillis() }
    val scoringPeriod = league.matchupPeriods[week]?.firstOrNull() ?: week

    val myMatchup = league.matchups.firstOrNull { it.week == week && (it.homeId == myTeam || it.awayId == myTeam) }
    val view = myMatchup?.let { Matchups.view(league, it, rosters, now) }
    val mine = view?.let { if (it.home.teamId == myTeam) it.home else it.away }
    val theirs = view?.let { if (it.home.teamId == myTeam) it.away else it.home }
    val myWin = view?.let { v -> v.homeWinProbability?.let { if (v.home.teamId == myTeam) it else 1 - it } }

    // Header: logo, name, record, score
    val team = league.teams[myTeam]
    val record = d.standings.firstOrNull { it.teamId == myTeam }?.record?.toString()
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team?.logo, team?.abbrev ?: "", 52.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(league.teamName(myTeam), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(team?.owner?.ifBlank { null }, record).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (mine != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt1(mine.actual), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                mine.projected?.let {
                    Text("Proj ${fmt1(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (theirs != null) {
        Text(
            "vs ${league.teamName(theirs.teamId)} ${fmt1(theirs.actual)}" +
                (theirs.projected?.let { " (proj ${fmt1(it)})" } ?: "") +
                (myWin?.let { " · ${if (myMatchup?.complete == true) (if (it >= 1.0) "Won" else if (it <= 0.0) "Lost" else "Tied") else "Win ${pct(it)}"}" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // Week switcher
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { week = weeks.lastOrNull { it < week } ?: week }, enabled = week > weeks.first()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Week $week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            if (week != startWeek) {
                Text("Back to this week", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { week = startWeek })
            }
        }
        IconButton(onClick = { week = weeks.firstOrNull { it > week } ?: week }, enabled = week < weeks.last()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week")
        }
    }
    HorizontalDivider()

    if (roster == null) {
        val state = weekLineups[week]
        when {
            state is WeekLineups.Unavailable -> Text(state.message, Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            rosters != null -> Text("No roster found for this team in week $week.", Modifier.padding(top = 16.dp))
            else -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    val counts = league.lineupSlotCounts
    val advice = remember(d, myTeam, week, roster) { Outlook.startSit(roster, counts, now) }

    // --- Editing: a target lineup (player id -> slot) that starts as ESPN's ---
    var target by remember(d, myTeam, week, roster) { mutableStateOf(LineupEditor.current(roster)) }
    var picking by remember(d, myTeam, week) { mutableStateOf<RosterSpot?>(null) }
    var confirming by remember(d, myTeam, week) { mutableStateOf(false) }
    val pending = LineupEditor.moves(roster, target)
    val problem = LineupEditor.problem(roster, target, counts, now)
    val suggested = remember(d, myTeam, week, roster) {
        LineupEditor.lineupFor(roster, advice.bestLineup.map { it.second.id }.toSet(), counts, now)
    }
    val editable = roster.any { !it.isLocked(now) }

    if (editable) {
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { suggested?.let { target = it }; lineup.onDismiss() },
                enabled = suggested != null && suggested != target,
                modifier = Modifier.weight(1f),
            ) { Text("Quick Lineup") }
            OutlinedButton(
                onClick = { target = LineupEditor.current(roster); lineup.onDismiss() },
                enabled = pending.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Reset") }
        }
        if (advice.gain > 0.05 && pending.isEmpty()) {
            Text("Quick Lineup adds +${fmt1(advice.gain)} projected points.", color = Good,
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (advice.alerts.isNotEmpty() || advice.emptySlots > 0) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (advice.emptySlots > 0) {
                Text("⚠ ${advice.emptySlots} empty starting slot${if (advice.emptySlots > 1) "s" else ""}", color = Bad,
                    style = MaterialTheme.typography.bodySmall)
            }
            advice.alerts.forEach { a ->
                Text("${if (a.severe) "⚠" else "•"} ${a.player.name} ${a.message}",
                    color = if (a.severe) Bad else Color(0xFFEF6C00), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    when (val st = lineup.saveState) {
        is SaveState.Saved -> Text("✓ ${st.message}", color = Good, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 8.dp))
        is SaveState.Failed -> Text(st.message, color = Bad, modifier = Modifier.padding(vertical = 8.dp))
        else -> {}
    }

    if (pending.isNotEmpty()) {
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${pending.size} change${if (pending.size > 1) "s" else ""} · not on ESPN until you save",
                    fontWeight = FontWeight.SemiBold)
                pending.forEach { m ->
                    Text("${m.name}: ${slotName(m.from)} → ${slotName(m.to)}", style = MaterialTheme.typography.bodyMedium)
                }
                problem?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = { confirming = true },
                    enabled = problem == null && lineup.saveState != SaveState.Saving,
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(if (lineup.saveState == SaveState.Saving) "Saving…" else "Save to ESPN") }
            }
        }
    }

    // Rows show the lineup as edited, so moved players jump between Starters and Bench.
    val shown = roster.map { it.copy(slotId = target[it.player.id] ?: it.slotId) }
    val original = roster.associate { it.player.id to it.slotId }
    val starters = shown.filter { it.started }
        .sortedWith(compareBy<RosterSpot> { Matchups.slotRank(it.slotId) }.thenByDescending { it.player.expected })
    val bench = shown.filter { !it.started }
        .sortedWith(compareBy<RosterSpot> { it.slotId == IR }.thenByDescending { it.player.expected })
    val edit = { s: RosterSpot -> picking = roster.first { it.player.id == s.player.id } }

    SectionHeader("STARTERS", "SCORE")
    starters.forEach { RosterLine(it, original.getValue(it.player.id), now, edit) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Total", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt1(starters.sumOf { it.player.pointsSoFar(now) }), fontWeight = FontWeight.Bold)
            Text(fmt1(starters.sumOf { it.player.expectedFinal(now) }), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SectionHeader("BENCH", "")
    bench.forEach { RosterLine(it, original.getValue(it.player.id), now, edit) }

    picking?.let { spot ->
        val here = target[spot.player.id] ?: spot.slotId
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("Move ${spot.player.name}") },
            text = {
                Column {
                    LineupEditor.allowedSlots(spot.player, counts).sortedBy { Matchups.slotRank(it) }.forEach { slot ->
                        val occupants = shown.filter { it.slotId == slot && it.player.id != spot.player.id }
                        val full = slot != BENCH && occupants.size >= (counts[slot] ?: 0)
                        val note = when {
                            slot == here -> "current"
                            full -> "swap with " + occupants.joinToString(" or ") { it.player.name }
                            else -> "open"
                        }
                        TextButton(
                            onClick = {
                                target = LineupEditor.move(target, roster, spot.player.id, slot, counts, now)
                                lineup.onDismiss()
                                picking = null
                            },
                            enabled = slot != here,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(slotName(slot), Modifier.width(56.dp), fontWeight = FontWeight.SemiBold)
                            Text(note, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Save to ESPN?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("These changes will be made to ${league.teamName(myTeam)} for week $week:")
                    pending.forEach { m -> Text("• ${m.name}: ${slotName(m.from)} → ${slotName(m.to)}") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    lineup.onSubmit(myTeam, week, scoringPeriod, pending)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(left, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(right, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider()
}

/** One ESPN-style roster line: slot pill, photo, name, ownership, game, points over projection. */
@Composable
private fun RosterLine(spot: RosterSpot, originalSlot: Int, now: Long, onSlotTap: (RosterSpot) -> Unit) {
    val p = spot.player
    val open = LocalOpenPlayer.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val locked = spot.isLocked(now)
    val moved = spot.slotId != originalSlot
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Slot pill: tap to move (greyed out once their game has started)
        Surface(
            onClick = { onSlotTap(spot) },
            enabled = !locked,
            shape = RoundedCornerShape(50),
            border = BorderStroke(
                if (moved) 2.dp else 1.dp,
                when {
                    locked -> MaterialTheme.colorScheme.outlineVariant
                    moved -> Good
                    else -> MaterialTheme.colorScheme.primary
                },
            ),
            color = if (moved) Good.copy(alpha = 0.12f) else Color.Transparent,
            modifier = Modifier.width(64.dp).height(32.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    slotName(spot.slotId),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (locked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(Modifier.weight(1f).clickable { open(p) }, verticalAlignment = Alignment.CenterVertically) {
            PlayerPhoto(p, 44.dp, Modifier.padding(horizontal = 10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (locked) "🔒 " else "") + shortName(p.name), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Text(" ${p.proTeam} ${p.position}", style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
                    StatusChip(p)
                }
                if (p.percentOwned > 0) {
                    Text("${p.percentOwned.toInt()}% Rost | ${p.percentStarted.toInt()}% Start",
                        style = MaterialTheme.typography.labelSmall, color = muted)
                }
                Text(gameLine(p, now, muted), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (p.game?.state == NflGame.IN) Good else muted)
                p.statLine?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text(if (p.gameStarted(now)) fmt1(p.pointsSoFar(now)) else "–", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(p.projected?.let { fmt1(it) } ?: "–", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
    HorizontalDivider()
}
