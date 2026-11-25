package com.fantasyanalyzer.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.data.Leader
import com.fantasyanalyzer.data.LeadersState

/** What the Leaders tab shows and how it asks for data. */
data class LeadersActions(val state: LeadersState, val onRequest: () -> Unit)

/** One sortable stat column. [value] null sorts last and shows a dash. */
private class StatColumn(val label: String, val decimals: Boolean = false, val value: (Leader) -> Double?)

private fun stat(label: String, id: Int) = StatColumn(label) { it.stats[id] ?: 0.0 }

private val COMMON = listOf(
    StatColumn("FPTS", decimals = true) { it.points },
    StatColumn("AVG", decimals = true) { it.average },
    StatColumn("GP") { it.games?.toDouble() },
)

// ESPN stat ids: 3 pass yds, 4 pass TD, 20 INT, 23 carries, 24 rush yds, 25 rush TD, 58 targets,
// 53 receptions, 42 rec yds, 43 rec TD, 83/84 FG made/attempted, 74 FG 50+, 86 PAT made,
// 99 sacks, 95 INT, 96 fumbles recovered, 105 D/ST TDs, 120 points allowed.
private val POSITION_COLUMNS = mapOf(
    "QB" to listOf(stat("PYDS", 3), stat("PTD", 4), stat("INT", 20), stat("RYDS", 24), stat("RTD", 25)),
    "RB" to listOf(stat("CAR", 23), stat("RYDS", 24), stat("RTD", 25), stat("REC", 53), stat("RECYDS", 42), stat("RECTD", 43)),
    "WR" to listOf(stat("TGT", 58), stat("REC", 53), stat("YDS", 42), stat("TD", 43), stat("RYDS", 24)),
    "TE" to listOf(stat("TGT", 58), stat("REC", 53), stat("YDS", 42), stat("TD", 43)),
    "K" to listOf(stat("FGM", 83), stat("FGA", 84), stat("50+", 74), stat("XPM", 86)),
    "D/ST" to listOf(stat("SACK", 99), stat("INT", 95), stat("FR", 96), stat("TD", 105), stat("PA", 120)),
)

/**
 * Each player's share of his NFL team's targets and carries, in percent, among
 * the RBs, WRs and TEs loaded here (the top 50 at each, which covers nearly all
 * of a team's touches).
 */
private class Shares(val targets: Map<Long, Double> = emptyMap(), val carries: Map<Long, Double> = emptyMap()) {
    companion object {
        fun of(leaders: List<Leader>): Shares {
            val skill = leaders.filter { it.player.position in setOf("RB", "WR", "TE") }.distinctBy { it.player.id }
            fun share(stat: Int): Map<Long, Double> {
                val team = skill.groupBy { it.player.proTeam }.mapValues { (_, l) -> l.sumOf { it.stats[stat] ?: 0.0 } }
                return skill.mapNotNull { l ->
                    val total = team[l.player.proTeam] ?: 0.0
                    if (total <= 0.0) null else l.player.id to 100.0 * (l.stats[stat] ?: 0.0) / total
                }.toMap()
            }
            return Shares(share(58), share(23))
        }
    }
}

private const val ALL = "All"
private val CELL = 56.dp

@Composable
internal fun LeadersScreen(d: Dashboard, myTeam: Int?, actions: LeadersActions) {
    LaunchedEffect(Unit) { actions.onRequest() }
    val state = actions.state
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var position by rememberSaveable { mutableStateOf(ALL) }
    var sortBy by rememberSaveable { mutableStateOf("FPTS") }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    // -1 everyone, 0 free agents, otherwise a league team id.
    var owner by rememberSaveable { mutableIntStateOf(-1) }
    val open = LocalOpenPlayer.current
    val ownerIds = remember(d, myTeam) {
        listOf(-1, 0) + listOfNotNull(myTeam) + d.league.teams.keys.filter { it != myTeam }.sortedBy { d.league.teamName(it) }
    }
    val ownerNames = ownerIds.map { id ->
        when (id) {
            -1 -> "Everyone"
            0 -> "Free agents"
            myTeam -> "My team"
            else -> d.league.teamName(id)
        }
    }

    Column(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (listOf(ALL) + Leader.POSITION_SLOTS.keys).forEach { p ->
                FilterChip(
                    selected = position == p,
                    onClick = {
                        position = p
                        // Keep the sort if the new position has that column; otherwise back to points.
                        if (columnsFor(p, Shares()).none { it.label == sortBy }) { sortBy = "FPTS"; ascending = false }
                    },
                    label = { Text(p) },
                )
            }
        }

        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !recentOnly, onClick = { recentOnly = false }, label = { Text("Season") })
            FilterChip(selected = recentOnly, onClick = { recentOnly = true }, label = { Text("Last ${Leader.RECENT_WEEKS}") })
            Picker("Owner", ownerNames, ownerIds.indexOf(owner).coerceAtLeast(0), { owner = ownerIds[it] }, Modifier.weight(1f))
        }

        when {
            state.loading || (state.byPosition.isEmpty() && state.error == null) -> Row(
                Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            state.error != null -> Text(state.error, Modifier.padding(vertical = 16.dp), color = muted)
            else -> {
                val shares = remember(state, recentOnly) {
                    Shares.of(state.byPosition.values.flatten().mapNotNull { if (recentOnly) it.recentOnly() else it })
                }
                val columns = columnsFor(position, shares)
                val sortColumn = columns.firstOrNull { it.label == sortBy } ?: columns.first()
                val rows = remember(state, position, sortBy, ascending, recentOnly, owner) {
                    val pool = (if (position == ALL) state.byPosition.values.flatten() else state.byPosition[position].orEmpty())
                        .mapNotNull { if (recentOnly) it.recentOnly() else it }
                        .filter {
                            when (owner) {
                                -1 -> true
                                0 -> it.fantasyTeamId == null
                                else -> it.fantasyTeamId == owner
                            }
                        }
                    val (known, unknown) = pool.partition { sortColumn.value(it) != null }
                    val sorted = known.sortedBy { sortColumn.value(it)!! }
                    (if (ascending) sorted else sorted.reversed()) + unknown
                }
                LeadersTable(d, rows, columns, sortColumn.label, ascending, showPosition = position == ALL, myTeam = myTeam,
                    onSort = { label ->
                        if (label == sortBy) ascending = !ascending else { sortBy = label; ascending = false }
                    },
                    onOpen = { open(it.player) },
                )
                if (rows.isEmpty()) {
                    Text("Nobody here matches.", Modifier.padding(vertical = 12.dp), color = muted)
                }
                Text(
                    (if (recentOnly) "Last ${Leader.RECENT_WEEKS} finished weeks" else "Season totals") +
                        " from ESPN, among the season's top 50 at each position. TGT% / CAR%: share of his NFL team's" +
                        " targets / carries among those players. Tap a column to sort; tap again to flip.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = muted,
                )
            }
        }
    }
}

private fun columnsFor(position: String, shares: Shares): List<StatColumn> {
    val tgt = StatColumn("TGT%") { shares.targets[it.player.id] }
    val car = StatColumn("CAR%") { shares.carries[it.player.id] }
    // Right after the basics, so they're on screen without scrolling sideways.
    val shares = when (position) {
        "RB", ALL -> listOf(tgt, car)
        "WR", "TE" -> listOf(tgt)
        else -> emptyList()
    }
    return COMMON + shares + POSITION_COLUMNS[position].orEmpty()
}

@Composable
private fun LeadersTable(
    d: Dashboard,
    rows: List<Leader>,
    columns: List<StatColumn>,
    sortedBy: String,
    ascending: Boolean,
    showPosition: Boolean,
    myTeam: Int?,
    onSort: (String) -> Unit,
    onOpen: (Leader) -> Unit,
) {
    // One scroll state for the header and every row, so the stat columns stay lined up.
    val scroll = rememberScrollState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Panel {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelHeading("Player", Modifier.width(PLAYER_WIDTH).padding(start = 12.dp))
            StatRow(scroll) {
                columns.forEach { c ->
                    val active = c.label == sortedBy
                    Text(
                        c.label + if (active) (if (ascending) " ▲" else " ▼") else "",
                        Modifier.width(CELL).clickable { onSort(c.label) }.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.primary else muted,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            }
        }
        PanelDivider()
        rows.forEachIndexed { i, l ->
            if (i > 0) PanelDivider()
            Row(Modifier.fillMaxWidth().clickable { onOpen(l) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(PLAYER_WIDTH).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(24.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                    PlayerPhoto(l.player, 32.dp, Modifier.padding(end = 8.dp))
                    Column {
                        Text(shortName(l.player.name), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val owner = l.fantasyTeamId?.let { id ->
                            d.league.teams[id]?.abbrev?.ifBlank { null } ?: d.league.teamName(id).take(4)
                        } ?: "FA"
                        Text(
                            listOfNotNull(l.player.proTeam, l.player.position.takeIf { showPosition }, owner).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (l.fantasyTeamId != null && l.fantasyTeamId == myTeam) MaterialTheme.colorScheme.primary
                            else if (l.fantasyTeamId == null) Good else muted,
                            maxLines = 1,
                        )
                    }
                }
                StatRow(scroll) {
                    columns.forEach { c ->
                        val v = c.value(l)
                        Text(
                            when {
                                v == null -> "–"
                                c.decimals -> fmt1(v)
                                else -> v.toInt().toString()
                            },
                            Modifier.width(CELL),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (c.label == sortedBy) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

private val PLAYER_WIDTH = 168.dp

@Composable
private fun RowScope.StatRow(scroll: ScrollState, content: @Composable () -> Unit) {
    Box(Modifier.weight(1f).horizontalScroll(scroll).padding(end = 12.dp)) {
        Row { content() }
    }
}
