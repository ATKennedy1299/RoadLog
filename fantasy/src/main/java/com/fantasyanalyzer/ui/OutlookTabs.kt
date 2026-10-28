package com.fantasyanalyzer.ui

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
        odds.rows.firstOrNull { it.teamId == myTeam }?.let { mine -> MyOddsCard(d, mine, odds.byes > 0) }
        Panel(Modifier.padding(top = 12.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                PanelHeading("Team", Modifier.weight(1f))
                listOfNotNull("Proj W", "Playoffs", if (odds.byes > 0) "Bye" else null, "Title").forEach {
                    PanelHeading(it, Modifier.width(58.dp), TextAlign.End)
                }
            }
            PanelDivider()
            odds.rows.forEachIndexed { i, r ->
                if (i > 0) PanelDivider()
                val mine = r.teamId == myTeam
                Column(
                    Modifier.background(if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            TeamLogo(d.league.teams[r.teamId]?.logo?.ifBlank { null }, r.team, 28.dp, Modifier.padding(end = 8.dp), CircleShape)
                            Column {
                                Text(r.team, fontWeight = if (mine) FontWeight.Bold else FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.record.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OddsCell(fmt1(r.projectedWins))
                        OddsCell(pct(r.playoffPct), oddsColor(r.playoffPct), bold = true)
                        if (odds.byes > 0) OddsCell(pct(r.byePct))
                        OddsCell(r.champPct?.let { pct(it) } ?: "—")
                    }
                    LinearProgressIndicator(
                        progress = { r.playoffPct.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 6.dp).clip(RoundedCornerShape(4.dp)),
                        color = oddsColor(r.playoffPct),
                    )
                }
            }
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
            style = MaterialTheme.typography.labelSmall,
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
        Panel {
            Spacer(Modifier.height(6.dp))
            rows.forEach { r ->
                // Bar spans the league's range so differences are visible; the hardest schedule is full width.
                val frac = if (hi > lo) (0.15 + 0.85 * (r.remainingOppAvg!! - lo) / (hi - lo)).toFloat() else 0.5f
                BarRow(
                    label = r.team,
                    fraction = frac,
                    valueText = "${fmt1(r.averageTeamWins)}/${r.opponents.size}",
                    color = if (r.teamId == myTeam) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    leading = "#${r.rank}",
                    logo = d.league.teams[r.teamId]?.logo?.ifBlank { null },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun oddsColor(p: Double): Color = when {
    p >= 0.75 -> Good
    p <= 0.25 -> Bad
    else -> Warn
}

@Composable
private fun OddsCell(text: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Text(text, Modifier.width(58.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

@Composable
private fun MyOddsCard(d: Dashboard, r: PlayoffOddsRow, hasByes: Boolean) {
    Panel {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(d.league.teams[r.teamId]?.logo?.ifBlank { null }, r.team, 48.dp, Modifier.padding(end = 14.dp), CircleShape)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PanelHeading(r.team)
                Text("${pct(r.playoffPct)} to make the playoffs", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = oddsColor(r.playoffPct))
                val extras = listOfNotNull(
                    if (hasByes) "${pct(r.byePct)} bye" else null,
                    r.champPct?.let { "${pct(it)} title" },
                    if (!r.avgSeed.isNaN()) "avg seed ${fmt1(r.avgSeed)}" else null,
                    "${fmt1(r.projectedWins)} projected wins",
                )
                Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.ifWinNext != null && r.ifLoseNext != null) {
                    Text(
                        buildAnnotatedString {
                            append("Next game: win → ")
                            withStyle(SpanStyle(color = Good, fontWeight = FontWeight.Bold)) { append(pct(r.ifWinNext)) }
                            append(", lose → ")
                            withStyle(SpanStyle(color = Bad, fontWeight = FontWeight.Bold)) { append(pct(r.ifLoseNext)) }
                        },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
