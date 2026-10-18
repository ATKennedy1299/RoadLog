package com.fantasyanalyzer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.fantasyanalyzer.analysis.PlayoffOddsRow
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.data.BENCH
import com.fantasyanalyzer.data.IR
import com.fantasyanalyzer.data.LineupEditor
import com.fantasyanalyzer.data.LineupMove
import com.fantasyanalyzer.data.RosterSpot
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.slotName
import java.util.Locale

private val Warn = Color(0xFFEF6C00)

/** Percent for odds: never claims 100% or 0% unless every simulation agreed. */
internal fun pct(p: Double): String = when {
    p >= 1.0 -> "100%"
    p <= 0.0 -> "0%"
    p > 0.999 -> ">99.9%"
    p < 0.001 -> "<0.1%"
    else -> String.format(Locale.US, "%.1f%%", p * 100)
}

internal fun Player.tag(): String = "${position} · ${proTeam}"

internal fun Player.statusText(): String? = when {
    onBye -> "BYE"
    injuryStatus == "ACTIVE" || injuryStatus == "NORMAL" -> null
    injuryStatus == "INJURY_RESERVE" -> "IR"
    injuryStatus == "QUESTIONABLE" -> "Q"
    injuryStatus == "DOUBTFUL" -> "D"
    injuryStatus == "OUT" -> "O"
    injuryStatus == "SUSPENSION" -> "SSPD"
    else -> injuryStatus.take(4)
}

@Composable
internal fun StatusChip(p: Player) {
    val text = p.statusText() ?: return
    Text(
        " $text",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (p.isOut || p.injuryStatus == "DOUBTFUL") Bad else Warn,
    )
}

@Composable
internal fun TeamPicker(d: Dashboard, myTeam: Int?, onPick: (Int) -> Unit) {
    val ids = d.league.teams.keys.sortedBy { d.league.teamName(it) }
    Column(Modifier.padding(top = 12.dp)) {
        Picker(
            "Your team",
            ids.map { d.league.teamName(it) },
            myTeam?.let { ids.indexOf(it) } ?: -1,
            { onPick(ids[it]) },
            Modifier.fillMaxWidth(),
        )
        if (myTeam == null) {
            Text(
                "Pick your team to see advice for it.",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun NoOutlook(d: Dashboard) {
    Text(
        if (d.league.seasonOver) "The season's over, so there's no lineup to set."
        else "ESPN didn't return this week's projections. Tap refresh to try again.",
        Modifier.padding(top = 24.dp),
    )
}

// --- Waivers -----------------------------------------------------------------------

internal fun LazyListScope.waiversTab(d: Dashboard, myTeam: Int?, onPick: (Int) -> Unit) {
    item { TeamPicker(d, myTeam, onPick) }
    val outlook = d.data.outlook
    if (myTeam == null) return
    if (outlook == null) {
        item { NoOutlook(d) }
        return
    }
    if (outlook.freeAgents.isEmpty()) {
        item { Text("ESPN didn't return any free agents.", Modifier.padding(top = 24.dp)) }
        return
    }
    val report = Outlook.waivers(outlook, myTeam)

    if (report.fillIns.isNotEmpty()) {
        item { SectionTitle("Fill-ins for week ${outlook.week}", "Starters who won't play, and who's available to cover.") }
        items(report.fillIns, key = { "fill${it.out.id}" }) { f ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("${f.out.name} (${f.out.statusText() ?: "out"})", fontWeight = FontWeight.SemiBold, color = Bad)
                if (f.options.isEmpty()) {
                    Text("No healthy free agents at ${f.out.position} with a projection.", style = MaterialTheme.typography.bodySmall)
                }
                f.options.forEach { fa -> FreeAgentLine(fa, "proj ${fmt1(fa.expected)}") }
            }
        }
    }

    item {
        SectionTitle(
            "Suggested pickups",
            "Free agents worth more than your weakest player at their position. " +
                "Value = 60% ESPN projection + 40% average of the last 3 weeks.",
        )
        if (report.upgrades.isEmpty()) {
            Text("Nobody available is clearly better than what you have. ✓", color = Good)
        }
    }
    items(report.upgrades, key = { "up${it.add.id}" }) { idea ->
        val open = LocalOpenPlayer.current
        Column(Modifier.fillMaxWidth().clickable { open(idea.add) }.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add ${idea.add.name}", Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusChip(idea.add)
                Text("  +${fmt1(idea.gain)}", color = Good, fontWeight = FontWeight.SemiBold)
            }
            Text(playerLine(idea.add), style = MaterialTheme.typography.bodySmall)
            idea.drop?.let { drop ->
                Text("Drop ${drop.name} · ${playerLine(drop)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
    }

    if (report.trending.isNotEmpty()) {
        item { SectionTitle("Trending adds", "Biggest jumps in % rostered across ESPN leagues.") }
        items(report.trending, key = { "tr${it.id}" }) { fa ->
            FreeAgentLine(fa, String.format(Locale.US, "%+.1f%%", fa.percentChange))
        }
    }

    report.bestByPosition.forEach { (pos, players) ->
        item(key = "pos$pos") { SectionTitle("Best available: $pos") }
        items(players, key = { "p$pos${it.id}" }) { fa -> FreeAgentLine(fa, "value ${fmt1(Outlook.waiverValue(fa))}") }
    }
}

private fun playerLine(p: Player): String = buildString {
    append(p.tag())
    append(" · proj ")
    append(p.projected?.let { fmt1(it) } ?: "—")
    p.recentAvg?.let { append(" · last ${p.recentPoints.size} avg ${fmt1(it)}") }
    if (p.percentOwned > 0) append(" · ${p.percentOwned.toInt()}% rostered")
    if (p.onWaivers) append(" · on waivers")
}

@Composable
private fun FreeAgentLine(p: Player, right: String) {
    val open = LocalOpenPlayer.current
    Row(Modifier.fillMaxWidth().clickable { open(p) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerPhoto(p, 36.dp, Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                StatusChip(p)
            }
            Text(playerLine(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(right, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
    }
}

// --- Playoffs ----------------------------------------------------------------------

internal fun LazyListScope.playoffsTab(d: Dashboard, myTeam: Int?) {
    val odds = d.playoffOdds
    item {
        SectionTitle(
            "Playoff odds",
            "${odds.playoffSpots} playoff spots" +
                (if (odds.byes > 0) ", top ${odds.byes} get byes" else "") +
                " · ${odds.gamesLeft} regular-season games left · ${"%,d".format(odds.simulations)} simulated seasons",
        )
        odds.rows.firstOrNull { it.teamId == myTeam }?.let { mine -> MyOddsCard(mine, odds.byes > 0) }
    }
    item {
        Row(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
            Text("Team", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            listOfNotNull("Proj W", "Playoffs", if (odds.byes > 0) "Bye" else null, "Title").forEach {
                Text(it, Modifier.width(62.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End)
            }
        }
        HorizontalDivider()
    }
    items(odds.rows, key = { "odds${it.teamId}" }) { r ->
        val mine = r.teamId == myTeam
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.team, fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.record.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OddsCell(fmt1(r.projectedWins))
                OddsCell(pct(r.playoffPct), oddsColor(r.playoffPct))
                if (odds.byes > 0) OddsCell(pct(r.byePct))
                OddsCell(r.champPct?.let { pct(it) } ?: "—")
            }
            LinearProgressIndicator(
                progress = { r.playoffPct.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = oddsColor(r.playoffPct),
            )
        }
    }
    scheduleSection(d, myTeam)
    item {
        Text(
            "How it works: each remaining game is simulated from both teams' average score (pulled slightly toward " +
                "the league average) with the league's week-to-week swings. Seeding uses record, then points for" +
                (if (odds.usesDivisions) ", with division winners seeded first" else "") +
                "; ESPN's exact tiebreakers may differ in close races." +
                (if (odds.rows.any { it.champPct == null }) " Title odds are hidden once the playoffs start." else ""),
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.scheduleSection(d: Dashboard, myTeam: Int?) {
    val rows = d.schedule.filter { it.remainingOppAvg != null }
    if (rows.isEmpty()) return
    val lo = rows.minOf { it.remainingOppAvg!! }
    val hi = rows.maxOf { it.remainingOppAvg!! }
    item {
        SectionTitle(
            "Remaining schedule",
            "Hardest first: opponents' average expected score, and how many of these games an average team would win.",
        )
        rows.firstOrNull { it.teamId == myTeam }?.let { mine ->
            Text(
                "Your #${mine.rank} of ${rows.size} schedule: " +
                    mine.opponents.joinToString(", ") { d.league.teams[it]?.abbrev?.ifBlank { null } ?: d.league.teamName(it) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
    items(rows, key = { "sos${it.teamId}" }) { r ->
        // Bar spans the league's range so differences are visible; the hardest schedule is full width.
        val frac = if (hi > lo) (0.15 + 0.85 * (r.remainingOppAvg!! - lo) / (hi - lo)).toFloat() else 0.5f
        BarRow(
            label = r.team,
            fraction = frac,
            valueText = "${fmt1(r.averageTeamWins)}/${r.opponents.size}",
            color = if (r.teamId == myTeam) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            leading = "#${r.rank}",
        )
    }
}

@Composable
private fun oddsColor(p: Double): Color = when {
    p >= 0.75 -> Good
    p <= 0.25 -> Bad
    else -> Warn
}

@Composable
private fun OddsCell(text: String, color: Color = Color.Unspecified) {
    Text(text, Modifier.width(62.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = color)
}

@Composable
private fun MyOddsCard(r: PlayoffOddsRow, hasByes: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.team, style = MaterialTheme.typography.labelMedium)
            Text("${pct(r.playoffPct)} to make the playoffs", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, color = oddsColor(r.playoffPct))
            val extras = listOfNotNull(
                if (hasByes) "${pct(r.byePct)} bye" else null,
                r.champPct?.let { "${pct(it)} title" },
                if (!r.avgSeed.isNaN()) "avg seed ${fmt1(r.avgSeed)}" else null,
                "${fmt1(r.projectedWins)} projected wins",
            )
            Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (r.ifWinNext != null && r.ifLoseNext != null) {
                Text(
                    "Next game: win → ${pct(r.ifWinNext)}, lose → ${pct(r.ifLoseNext)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
