package com.fantasyanalyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fantasyanalyzer.analysis.Outlook
import com.fantasyanalyzer.analysis.PlayoffOddsRow
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.data.INACTIVE_SLOTS
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.slotName
import java.util.Locale

private val Warn = Color(0xFFEF6C00)

/** Percent for odds: never claims 100% or 0% unless every simulation agreed. */
private fun pct(p: Double): String = when {
    p >= 1.0 -> "100%"
    p <= 0.0 -> "0%"
    p > 0.999 -> ">99.9%"
    p < 0.001 -> "<0.1%"
    else -> String.format(Locale.US, "%.1f%%", p * 100)
}

private fun Player.tag(): String = "${position} · ${proTeam}"

private fun Player.statusText(): String? = when {
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
private fun StatusChip(p: Player) {
    val text = p.statusText() ?: return
    Text(
        " $text",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (p.isOut || p.injuryStatus == "DOUBTFUL") Bad else Warn,
    )
}

@Composable
private fun TeamPicker(d: Dashboard, myTeam: Int?, onPick: (Int) -> Unit) {
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
private fun NoOutlook(d: Dashboard) {
    Text(
        if (d.league.seasonOver) "The season's over, so there's no lineup to set."
        else "ESPN didn't return this week's projections. Tap refresh to try again.",
        Modifier.padding(top = 24.dp),
    )
}

// --- My Team ----------------------------------------------------------------------

internal fun LazyListScope.myTeamTab(d: Dashboard, myTeam: Int?, onPick: (Int) -> Unit) {
    item { TeamPicker(d, myTeam, onPick) }
    val outlook = d.data.outlook
    if (myTeam == null) return
    if (outlook == null) {
        item { NoOutlook(d) }
        return
    }
    val roster = outlook.rosters[myTeam]
    if (roster == null) {
        item { Text("No roster found for this team in week ${outlook.week}.", Modifier.padding(top = 24.dp)) }
        return
    }
    item {
        val advice = remember(d, myTeam) { Outlook.startSit(roster, d.league.lineupSlotCounts) }
        val matchup = remember(d, myTeam) { Outlook.matchup(d.league, outlook, myTeam) }

        if (matchup != null) {
            SectionTitle("Week ${outlook.week} matchup", "From ESPN's projections before kickoff.")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("You", style = MaterialTheme.typography.labelMedium)
                            Text(fmt1(matchup.myProjection), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text("vs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(d.league.teamName(matchup.opponentId), style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(fmt1(matchup.opponentProjection), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { matchup.winProbability.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Text("Win chance ${pct(matchup.winProbability)}", fontWeight = FontWeight.SemiBold)
                    if (matchup.winProbabilityBest - matchup.winProbability > 0.005) {
                        Text(
                            "${pct(matchup.winProbabilityBest)} with the best lineup (${fmt1(matchup.myBestProjection)} projected)",
                            style = MaterialTheme.typography.bodySmall, color = Good,
                        )
                    }
                }
            }
        }

        if (advice.alerts.isNotEmpty() || advice.emptySlots > 0) {
            SectionTitle("Heads up")
            if (advice.emptySlots > 0) {
                Text("⚠ ${advice.emptySlots} empty starting slot${if (advice.emptySlots > 1) "s" else ""}", color = Bad,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
            advice.alerts.forEach { a ->
                Text(
                    "${if (a.severe) "⚠" else "•"} ${a.player.name} ${a.message}",
                    color = if (a.severe) Bad else Warn,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        SectionTitle("Start / sit")
        if (advice.start.isEmpty() && advice.sit.isEmpty()) {
            Text("✓ Your lineup is already the best by ESPN's projections.", color = Good)
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Good.copy(alpha = 0.10f)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("+${fmt1(advice.gain)} projected points available", fontWeight = FontWeight.SemiBold, color = Good)
                    advice.start.forEach { p ->
                        Text("Start ${p.name} (${p.tag()}) · ${fmt1(p.expected)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    advice.sit.forEach { p ->
                        Text("Bench ${p.name} (${p.tag()}) · ${fmt1(p.expected)}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Make the changes in the ESPN app.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SectionTitle("Your roster", "🔒 = game already started")
        val bestSlot = advice.bestLineup.associate { (slot, p) -> p.id to slot }
        Row(Modifier.padding(vertical = 4.dp)) {
            listOf("Player" to 1f, "Now" to 0.4f, "Best" to 0.4f, "Proj" to 0.35f).forEach { (h, w) ->
                Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        val ordered = roster.sortedWith(
            compareBy<com.fantasyanalyzer.data.RosterSpot> { it.slotId in INACTIVE_SLOTS }.thenByDescending { it.player.expected }
        )
        ordered.forEach { spot ->
            val p = spot.player
            val now = slotName(spot.slotId)
            val best = bestSlot[p.id] ?: "BE"
            val changed = (now == "BE" || now == "IR") != (best == "BE")
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text((if (spot.locked) "🔒 " else "") + p.name, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        StatusChip(p)
                    }
                    Text(p.tag(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(now, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall)
                Text(best, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall,
                    color = if (changed) Good else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal)
                Text(p.projected?.let { fmt1(it) } ?: "—", Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
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
        Column(Modifier.padding(vertical = 6.dp)) {
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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
