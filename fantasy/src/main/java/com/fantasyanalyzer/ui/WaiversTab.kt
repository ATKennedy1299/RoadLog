@file:OptIn(ExperimentalMaterial3Api::class)

package com.fantasyanalyzer.ui

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.fantasyanalyzer.data.Opportunities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fantasyanalyzer.analysis.Outlook
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.data.AddDrop
import com.fantasyanalyzer.data.IR
import com.fantasyanalyzer.data.PendingClaim
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.RosterMoves
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.nflLogoUrl
import java.util.Locale

/** What the Waivers tab needs to make moves. */
class WaiverActions(
    val txState: SaveState,
    val pending: List<PendingClaim>?,
    val onSubmit: (teamId: Int, move: AddDrop) -> Unit,
    val onDismiss: () -> Unit,
    val onRequestPending: (teamId: Int) -> Unit,
)

private val FILTERS = listOf("All", "QB", "RB", "WR", "TE", "FLEX", "D/ST", "K")
private enum class Sort(val label: String) { PROJ("Proj"), ROST("% Rost"), TREND("Trending") }
private val AddGreen = Color(0xFF3DBA4E)

private fun Player.matches(filter: String) = when (filter) {
    "All" -> true
    "FLEX" -> position in setOf("RB", "WR", "TE")
    else -> position == filter
}

private fun change(p: Player): String = String.format(Locale.US, "%+.1f", p.percentChange)

/** ESPN-style waiver wire: search, filters, trending/for-you, and the available list with add/claim. */
@Composable
internal fun WaiversScreen(d: Dashboard, myTeam: Int?, onPick: (Int) -> Unit, actions: WaiverActions) {
    if (myTeam == null) {
        TeamPicker(d, myTeam, onPick)
        return
    }
    val outlook = d.data.outlook
    if (outlook == null) {
        NoOutlook(d)
        return
    }
    LaunchedEffect(myTeam, d) { actions.onRequestPending(myTeam) }
    LaunchedEffect(myTeam) { actions.onDismiss() }

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var sort by rememberSaveable { mutableStateOf(Sort.PROJ) }
    var showForYou by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf<Pair<Player, Player?>?>(null) }
    val open = LocalOpenPlayer.current
    val now = remember(d) { System.currentTimeMillis() }
    val roster = outlook.rosters[myTeam].orEmpty()
    val ownTeam = d.signedInTeamId == null || d.signedInTeamId == myTeam
    val pool = outlook.freeAgents
    val report = remember(d, myTeam) { Outlook.waivers(outlook, myTeam) }
    val opportunities = remember(d) {
        Opportunities.find(outlook.rosters.values.flatten().map { it.player }, pool)
    }
    val nextUpFor = opportunities.associate { it.nextUp.id to it.out }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.padding(top = 12.dp)) {
        // Search and filters
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search available players") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FILTERS.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
        }

        when (val st = actions.txState) {
            is SaveState.Saved -> Text("✓ ${st.message}", color = Good, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 4.dp))
            is SaveState.Failed -> Text(st.message, color = Bad, modifier = Modifier.padding(vertical = 4.dp))
            SaveState.Saving -> Text("Sending to ESPN…", color = muted, modifier = Modifier.padding(vertical = 4.dp))
            SaveState.Idle -> {}
        }
        if (!ownTeam) {
            Text("You can only make moves for your own team.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        d.league.faabBudget?.let { budget ->
            val left = budget - (d.league.teams[myTeam]?.faabSpent ?: 0)
            Text("Waiver budget: $$left of $$budget left", style = MaterialTheme.typography.bodySmall, color = muted)
        }

        // Pending claims
        val pending = actions.pending.orEmpty()
        if (pending.isNotEmpty()) {
            PanelLabel("PENDING CLAIMS")
            Panel {
                pending.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val add = pool.firstOrNull { it.id == c.addPlayerId }?.name ?: "Player ${c.addPlayerId}"
                    val drop = c.dropPlayerId?.let { id -> roster.firstOrNull { it.player.id == id }?.player?.name ?: "Player $id" }
                    Text(
                        "Claim $add" + (drop?.let { " · drop $it" } ?: "") + (c.bid?.let { " · $$it" } ?: ""),
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Opportunities: a regular starter is out and his likely fill-in is available.
        if (opportunities.isNotEmpty()) {
            PanelLabel("OPPORTUNITIES", "Starters ruled out this week, and the available teammate ESPN projects highest.")
            Panel {
                opportunities.forEachIndexed { i, o ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().clickable { open(o.nextUp) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerPhoto(o.nextUp, 40.dp, Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${o.nextUp.name} · ${o.nextUp.proTeam} ${o.nextUp.position}", fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                buildAnnotatedString {
                                    append("Next up for ${o.out.name} ")
                                    withStyle(SpanStyle(color = Bad, fontWeight = FontWeight.Bold)) { append(o.out.statusText() ?: "OUT") }
                                },
                                style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(o.nextUp.projected?.let { fmt1(it) } ?: "–", fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp))
                        AddButton(enabled = ownTeam) { adding = o.nextUp to null }
                    }
                }
            }
        }

        // Trending / For you
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !showForYou, onClick = { showForYou = false }, label = { Text("Trending") })
            FilterChip(selected = showForYou, onClick = { showForYou = true }, label = { Text("For you") })
        }
        if (!showForYou) {
            val trending = pool.filter { it.percentChange > 0 && it.matches(filter) }.sortedByDescending { it.percentChange }.take(10)
            if (trending.isEmpty()) {
                Text("No trending pickups right now.", Modifier.padding(vertical = 12.dp), color = muted)
            } else {
                LazyRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(trending.size) { i ->
                        val p = trending[i]
                        TrendingCard(p, enabled = ownTeam, onOpen = { open(p) }) { adding = p to null }
                    }
                }
            }
        } else {
            if (report.fillIns.isNotEmpty()) {
                PanelLabel("FILL-INS FOR WEEK ${outlook.week}")
                Panel {
                    report.fillIns.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(12.dp)) {
                            Text("${f.out.name} (${f.out.statusText() ?: "out"})", fontWeight = FontWeight.SemiBold, color = Bad)
                            if (f.options.isEmpty()) Text("No healthy free agents with a projection.", color = muted,
                                style = MaterialTheme.typography.bodySmall)
                            f.options.forEach { fa ->
                                Text("• ${fa.name} · proj ${fmt1(fa.expected)}", Modifier.clickable { open(fa) },
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            PanelLabel("SUGGESTED PICKUPS", "Better than your weakest player at the position: 60% projection, 40% last 3 weeks.")
            if (report.upgrades.isEmpty()) {
                Text("Nobody available is clearly better than what you have. ✓", color = Good)
            } else {
                Panel {
                    report.upgrades.forEachIndexed { i, idea ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().clickable { open(idea.add) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlayerPhoto(idea.add, 40.dp, Modifier.padding(end = 10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Add ${idea.add.name}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                idea.drop?.let {
                                    Text("Drop ${it.name}", style = MaterialTheme.typography.bodySmall, color = muted,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text("+${fmt1(idea.gain)}", color = Good, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                            AddButton(enabled = ownTeam) { adding = idea.add to idea.drop }
                        }
                    }
                }
            }
        }

        // Available
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AVAILABLE", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Sort.entries.forEach { s ->
                TextButton(onClick = { sort = s }) {
                    Text(s.label, fontWeight = if (sort == s) FontWeight.Bold else FontWeight.Normal,
                        color = if (sort == s) MaterialTheme.colorScheme.primary else muted)
                }
            }
        }
        val list = pool.asSequence()
            .filter { it.matches(filter) }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedByDescending {
                when (sort) {
                    Sort.PROJ -> it.projected ?: -1.0
                    Sort.ROST -> it.percentOwned
                    Sort.TREND -> it.percentChange
                }
            }
            .take(60).toList()
        Panel {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Text("PLAYERS", Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = muted)
                Text("PROJ", Modifier.width(56.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = muted)
                Text("SCORE", Modifier.width(56.dp).padding(vertical = 12.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = muted)
            }
            if (list.isEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("No available players match.", Modifier.padding(16.dp), color = muted)
            }
            list.forEach { p ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AvailableRow(p, now, ownTeam, nextUpFor[p.id], onOpen = { open(p) }) { adding = p to null }
            }
        }
        Text(
            "ESPN lists the most-rostered free agents at each position; search covers those.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = muted,
        )
    }

    adding?.let { (player, suggestedDrop) ->
        AddDropSheet(
            d = d,
            teamId = myTeam,
            player = player,
            suggestedDrop = suggestedDrop,
            roster = roster,
            now = now,
            onDismiss = { adding = null },
        ) { move ->
            adding = null
            actions.onSubmit(myTeam, move)
        }
    }
}

@Composable
private fun PanelLabel(text: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 16.dp, bottom = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddButton(enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = AddGreen, contentColor = Color.White),
        modifier = Modifier.size(36.dp),
    ) { Icon(Icons.Default.Add, contentDescription = "Add") }
}

@Composable
private fun TrendingCard(p: Player, enabled: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.width(150.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Photo over a faint NFL logo, like ESPN's cards.
        Box(
            Modifier.fillMaxWidth().height(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(model = nflLogoUrl(p.proTeam), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.size(90.dp).alpha(0.25f))
            PlayerPhoto(p, 72.dp)
        }
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shortName(p.name), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Text(" ${p.position}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                Text("Rost ${p.percentOwned.toInt()}% ", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${change(p)} ↑", style = MaterialTheme.typography.labelSmall, color = Good)
            }
            Button(
                onClick = onAdd,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = AddGreen, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (p.onWaivers) "+ Claim" else "+ Add") }
        }
    }
}

@Composable
private fun AvailableRow(p: Player, now: Long, enabled: Boolean, nextUpFor: Player?, onOpen: () -> Unit, onAdd: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen).padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AddButton(enabled = enabled, onClick = onAdd)
            PlayerPhoto(p, 44.dp, Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shortName(p.name), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Text(" ${p.position}", style = MaterialTheme.typography.labelSmall, color = muted)
                    StatusChip(p)
                    if (p.onWaivers) {
                        Text(" W", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }
                Row {
                    Text("${p.percentOwned.toInt()}% Rost | ", style = MaterialTheme.typography.labelSmall, color = muted)
                    Text(change(p) + if (p.percentChange >= 0) " ↑" else " ↓", style = MaterialTheme.typography.labelSmall,
                        color = if (p.percentChange >= 0) Good else Bad)
                }
                Text(gameLine(p, now, muted), style = MaterialTheme.typography.labelSmall, color = muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                nextUpFor?.let {
                    Text("NEXT UP for ${shortName(it.name)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                        color = Good, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FormTag(p)
            }
        }
        Box(Modifier.width(56.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            Text(p.liveProjection(now)?.let { fmt1(it) } ?: "–", style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(if (p.gameStarted(now)) fmt1(p.pointsSoFar(now)) else "–", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold)
        }
    }
}

/** Pick who to drop (and a bid, for waiver claims), check it, confirm, send. */
@Composable
private fun AddDropSheet(
    d: Dashboard,
    teamId: Int,
    player: Player,
    suggestedDrop: Player?,
    roster: List<RosterSpot>,
    now: Long,
    onDismiss: () -> Unit,
    onSubmit: (AddDrop) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var dropId by remember { mutableStateOf(suggestedDrop?.id) }
    var bidText by remember { mutableStateOf("0") }
    var confirming by remember { mutableStateOf(false) }
    val claim = player.onWaivers
    val budget = d.league.faabBudget
    val budgetLeft = budget?.let { it - (d.league.teams[teamId]?.faabSpent ?: 0) }
    val drop = roster.firstOrNull { it.player.id == dropId }?.player
    val bid = if (claim && budget != null) bidText.toIntOrNull() else null
    val move = AddDrop(player, drop, claim, bid)
    val problem = RosterMoves.problem(move, roster, d.league.rosterLimit, budgetLeft, now)
        ?: if (claim && budget != null && bid == null) "Enter a bid (0 or more)." else null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerPhoto(player, 56.dp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text((if (claim) "Claim " else "Add ") + player.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(
                        "${player.position} · ${player.proTeam} · " +
                            if (claim) "on waivers: the claim processes when waivers clear" else "free agent: added right away",
                        style = MaterialTheme.typography.bodySmall, color = muted,
                    )
                }
            }
            if (claim && budget != null) {
                OutlinedTextField(
                    value = bidText,
                    onValueChange = { v -> bidText = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Bid" + (budgetLeft?.let { " ($$it left)" } ?: "")) },
                    prefix = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Drop", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            val full = roster.count { it.slotId != IR } >= d.league.rosterLimit && d.league.rosterLimit > 0
            if (!full) {
                DropOption("No one (you have room)", null, dropId == null, enabled = true) { dropId = null }
            }
            roster.sortedBy { it.player.expected }.forEach { s ->
                val locked = s.started && s.isLocked(now)
                DropOption(
                    "${s.player.name} · ${s.player.position}" + (if (locked) " · game started" else "") +
                        (s.player.projected?.let { " · proj ${fmt1(it)}" } ?: ""),
                    s.player, dropId == s.player.id, enabled = !locked,
                ) { dropId = s.player.id }
            }
            problem?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { confirming = true },
                enabled = problem == null,
                colors = ButtonDefaults.buttonColors(containerColor = AddGreen, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(if (claim) "Place claim" else "Add player") }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (claim) "Place this claim on ESPN?" else "Make this move on ESPN?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("• ${if (claim) "Claim" else "Add"} ${player.name}")
                    drop?.let { Text("• Drop ${it.name}") }
                    bid?.let { Text("• Bid $$it") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    onSubmit(move)
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DropOption(label: String, player: Player?, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        if (player != null) PlayerPhoto(player, 32.dp, Modifier.padding(end = 8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
