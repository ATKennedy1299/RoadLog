@file:OptIn(ExperimentalMaterial3Api::class)

package com.fantasyanalyzer.ui

import androidx.compose.foundation.background
import com.fantasyanalyzer.data.ScoringRule
import com.fantasyanalyzer.data.ScoreBreakdown
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.remember
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.POSITIONS
import com.fantasyanalyzer.data.Player
import com.fantasyanalyzer.data.ScoringStyle
import com.fantasyanalyzer.data.SeasonForm
import com.fantasyanalyzer.data.nflLogoUrl
import com.fantasyanalyzer.data.photoUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Opens the player sheet; provided by the dashboard so any list can make players tappable. */
val LocalOpenPlayer = staticCompositionLocalOf<(Player) -> Unit> { {} }

private val KICKOFF = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

fun kickoffText(epochMs: Long): String = KICKOFF.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** "Jaxon Smith-Njigba" -> "J. Smith-Njigba"; D/ST names stay whole. */
fun shortName(name: String): String {
    if (name.contains("D/ST")) return name
    val parts = name.split(" ")
    return if (parts.size >= 2) "${parts[0].first()}. ${parts.drop(1).joinToString(" ")}" else name
}

fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

/** ESPN's coloring: green for a soft matchup (opponent allows lots to the position), red for a tough one. */
fun rankColor(rank: Int?): Color? = when {
    rank == null -> null
    rank >= 25 -> Good
    rank <= 8 -> Bad
    else -> null
}

/**
 * The game line under a player, ESPN style: "Sun 8:20 PM DAL (28th)" before
 * kickoff, "14-17 Final/OT @DEN (4th)" during/after, "BYE" on a bye.
 */
fun gameLine(p: Player, now: Long, muted: Color): AnnotatedString = buildAnnotatedString {
    if (p.onBye) {
        append("BYE")
        return@buildAnnotatedString
    }
    val g = p.game
    when {
        g != null && g.state != NflGame.PRE -> append("${g.scoreFor(p.proTeam)} ${g.detail} ")
        g != null -> append(preGameText(p, g) + " ")
        p.gameStart != null && !p.gameStarted(now) -> append(kickoffText(p.gameStart) + " ")
        p.gameStart != null -> append(if (p.remainingShare(now) > 0) "Live " else "Final ")
    }
    val opp = p.opponent ?: return@buildAnnotatedString
    val color = rankColor(p.opponentRank) ?: muted
    withStyle(SpanStyle(color = color)) {
        append(opp)
        p.opponentRank?.let { append(" (${ordinal(it)})") }
    }
}

/** Before kickoff: "Sun 1:00 PM" from the schedule, else ESPN's text without the date clutter and time zone. */
private fun preGameText(p: Player, g: NflGame): String =
    p.gameStart?.let { kickoffText(it) } ?: g.detail.replace(Regex("""\s+[A-Z]{2,4}$"""), "")

/** Team and opponent with the opponent's rank against the position: "JAX @DEN (4th)". */
fun matchupLine(p: Player, muted: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = muted)) { append(p.proTeam) }
    if (p.onBye) {
        withStyle(SpanStyle(color = muted)) { append(" · BYE") }
        return@buildAnnotatedString
    }
    val opp = p.opponent ?: return@buildAnnotatedString
    withStyle(SpanStyle(color = rankColor(p.opponentRank) ?: muted)) {
        append(" $opp")
        p.opponentRank?.let { append(" (${ordinal(it)})") }
    }
}

/** Where their game is: "Sun 1:00 PM", "14-17 Q3 4:12", "14-17 Final/OT"; null on a bye or with no schedule. */
fun gameStateText(p: Player, now: Long): String? {
    if (p.onBye) return null
    val g = p.game
    return when {
        g != null && g.state != NflGame.PRE -> "${g.scoreFor(p.proTeam)} ${g.detail}"
        g != null -> preGameText(p, g)
        p.gameStart == null -> null
        !p.gameStarted(now) -> kickoffText(p.gameStart)
        p.remainingShare(now) > 0 -> "Live"
        else -> "Final"
    }
}

@Composable
fun PlayerPhoto(p: Player, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // Initials show through until (or unless) the photo loads.
        Text(
            p.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AsyncImage(
            model = p.photoUrl(),
            contentDescription = p.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
fun TeamLogo(
    url: String?,
    fallback: String,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(fallback.take(4), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        if (url != null) {
            AsyncImage(model = url, contentDescription = fallback, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        }
    }
}

/** Everything we know about a player: this week, recent games and what's coming. */
@Composable
fun PlayerSheet(p: Player, d: Dashboard, weeks: Map<Int, com.fantasyanalyzer.data.WeekLine>?, onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val now = System.currentTimeMillis()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerPhoto(p, 72.dp)
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        StatusChip(p)
                    }
                    Text("${p.position} · ${p.proTeam}", color = muted)
                    if (p.percentOwned > 0) {
                        Text("${p.percentOwned.toInt()}% rostered · ${p.percentStarted.toInt()}% started",
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                TeamLogo(nflLogoUrl(p.proTeam), p.proTeam, 40.dp)
            }
            if (p.injuryStatus !in setOf("ACTIVE", "NORMAL")) {
                Text("Status: ${p.injuryStatus.lowercase().replace('_', ' ')}", color = if (p.isOut) Bad else Color(0xFFEF6C00))
            }

            SectionTitle("This week")
            Text(gameLine(p, now, muted), style = MaterialTheme.typography.bodyMedium)
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Points", style = MaterialTheme.typography.labelMedium, color = muted)
                    Text(if (p.gameStarted(now)) fmt1(p.pointsSoFar(now)) else "–", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Projected", style = MaterialTheme.typography.labelMedium, color = muted)
                    Text(p.liveProjection(now)?.let { fmt1(it) } ?: "–", style = MaterialTheme.typography.headlineSmall)
                }
            }
            p.statLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            val seasonWeeks = weeks ?: d.data.outlook?.seasons?.get(p.id)
            seasonWeeks?.let { SeasonForm.of(it, d.league.currentScoringPeriod) }?.let { SeasonFormSection(it) }

            // The whole season, ESPN game-log style, with how tough each opponent is for this position.
            val rows = seasonSchedule(d, p, weeks.orEmpty())
            val played = rows.mapNotNull { it.points }
            if (rows.isNotEmpty()) {
                SectionTitle(
                    "Schedule",
                    (if (played.isNotEmpty()) "Season ${fmt1(played.sum())} pts · avg ${fmt1(played.average())}. " else "") +
                        "Opponent's rank vs ${p.position}s: green is a soft matchup, red a tough one.",
                )
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("WK", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = muted, fontWeight = FontWeight.Bold)
                    Text("OPP", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = muted, fontWeight = FontWeight.Bold)
                    Text("PTS", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, color = muted,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    Text("PROJ", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, color = muted,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                }
                rows.forEach { r ->
                    val current = r.week == d.league.currentScoringPeriod
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (current) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${r.week}", Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.opponent, style = MaterialTheme.typography.bodySmall)
                                r.rank?.let { rank ->
                                    Text(
                                        ordinal(rank),
                                        Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp))
                                            .background((rankColor(rank) ?: muted).copy(alpha = 0.16f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = rankColor(rank) ?: muted,
                                    )
                                }
                            }
                            r.statLine?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
                        }
                        Text(r.points?.let { fmt1(it) } ?: "–", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                            color = when {
                                r.points == null || r.projected == null -> Color.Unspecified
                                r.points >= r.projected -> Good
                                else -> Bad
                            })
                        Text(r.projected?.let { fmt1(it) } ?: "–", Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall,
                            color = muted, textAlign = TextAlign.End)
                    }
                }
            } else if (p.recentPoints.isNotEmpty()) {
                SectionTitle("Recent games")
                Text(p.recentPoints.joinToString(" · ") { fmt1(it) }, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }
}

private class ScheduleRow(val week: Int, val opponent: String, val rank: Int?, val points: Double?, val projected: Double?, val statLine: String? = null)

/**
 * Every NFL week of the season for [p]: opponent (or BYE), that defense's
 * season rank against the position, and points/projection from the league's
 * box scores for weeks already played.
 */
private fun seasonSchedule(d: Dashboard, p: Player, card: Map<Int, com.fantasyanalyzer.data.WeekLine>): List<ScheduleRow> {
    val league = d.league
    // NFL week -> this player's box score line, from whichever fantasy roster had them.
    val byWeek = d.data.boxScores.flatMap { (matchupWeek, rosters) ->
        val nflWeek = league.matchupPeriods[matchupWeek]?.singleOrNull() ?: matchupWeek
        rosters.values.flatten().filter { it.playerId == p.id }.map { nflWeek to it }
    }.toMap()
    val pro = d.data.proTeams
    val team = pro.values.firstOrNull { it.abbrev == p.proTeam }
    val posId = POSITIONS.entries.firstOrNull { it.value == p.position }?.key
    val lastWeek = maxOf(league.finalScoringPeriod, byWeek.keys.maxOrNull() ?: 0)
    if (team == null) {
        val weeks = (byWeek.keys + card.keys).toSortedSet()
        return weeks.map { w -> ScheduleRow(w, "–", null, card[w]?.actual ?: byWeek[w]?.points, card[w]?.projected ?: byWeek[w]?.projected, card[w]?.statLine) }
    }
    return (1..lastWeek).map { week ->
        val g = team.games[week]
        val entry = byWeek[week]
        val c = card[week]
        // The player's own card first (covers free agents), then the league's box scores, then this week's live data.
        val points = c?.actual ?: entry?.points
            ?: if (week == league.currentScoringPeriod && p.gameStarted(System.currentTimeMillis())) p.actual else null
        val projected = c?.projected ?: entry?.projected ?: if (week == league.currentScoringPeriod) p.projected else null
        if (g == null || team.byeWeek == week) {
            ScheduleRow(week, "BYE", null, points, projected)
        } else {
            val opp = pro[g.opponentId]?.abbrev ?: "?"
            ScheduleRow(
                week,
                if (g.home) opp else "@$opp",
                posId?.let { d.data.ratings[it]?.get(g.opponentId) },
                points,
                projected,
                c?.statLine,
            )
        }
    }
}

val RedZoneColor = Color(0xFFF25C5C)
val OnFieldColor = Color(0xFFFFB648)

/** ESPN's outline colors: red in the red zone, orange on the field. */
fun fieldColor(status: com.fantasyanalyzer.data.FieldStatus?): Color? = when (status) {
    com.fantasyanalyzer.data.FieldStatus.RED_ZONE -> RedZoneColor
    com.fantasyanalyzer.data.FieldStatus.ON_FIELD -> OnFieldColor
    null -> null
}

/** "■ In Red Zone  ■ On Field", shown while games are on. */
@Composable
fun FieldLegend(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        listOf("In Red Zone" to RedZoneColor, "On Field" to OnFieldColor).forEach { (label, color) ->
            Box(Modifier.padding(start = 10.dp, end = 4.dp).size(10.dp).background(color, RoundedCornerShape(2.dp)))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/** Opens a player's score breakdown (tapping their points, as on ESPN). */
val LocalOpenScore = staticCompositionLocalOf<(Player) -> Unit> { {} }

/** Whether there's a breakdown to show for this player's points. */
fun hasBreakdown(p: Player): Boolean = p.appliedStats.isNotEmpty() || p.weekStats.isNotEmpty()

/** ESPN's scoring breakdown card: each category, points per, the count and points earned, and the total. */
@Composable
fun ScoreBreakdownDialog(p: Player, scoring: Map<Int, ScoringRule>, onDismiss: () -> Unit) {
    val lines = remember(p, scoring) { ScoreBreakdown.lines(p, scoring) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.padding(top = 44.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center)
                        p.opponent?.let { opp ->
                            Text(if (opp.startsWith("@")) "@ ${opp.drop(1)}" else "vs. $opp",
                                style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    }
                }
                HorizontalDivider()
                BreakdownRow("SCORING CATEGORY", "PTS PER", "#", "SCORE", header = true)
                HorizontalDivider()
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (lines.isEmpty()) {
                        Text("No scoring plays yet.", Modifier.padding(16.dp), color = muted)
                    }
                    lines.forEach { l ->
                        BreakdownRow(l.label, l.perUnit?.let { trim(it) } ?: "–", trim(l.count), fmt1(l.points))
                        HorizontalDivider()
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text("TOTAL", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(fmt1(p.actual ?: lines.sumOf { it.points }), fontWeight = FontWeight.Bold)
                }
            }
            PlayerPhoto(p, 88.dp, Modifier.border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }
}

@Composable
private fun BreakdownRow(label: String, per: String, count: String, score: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (header) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = style, fontWeight = weight)
        Text(per, Modifier.width(64.dp), style = style, fontWeight = weight, textAlign = TextAlign.Center)
        Text(count, Modifier.width(48.dp), style = style, fontWeight = weight, textAlign = TextAlign.Center)
        Text(score, Modifier.width(56.dp), style = style, fontWeight = if (header) weight else FontWeight.Bold,
            textAlign = TextAlign.End)
    }
}

/** 0.1, 1, 34, -2: no trailing ".0" on whole numbers. */
private fun trim(v: Double): String =
    if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/** Player id -> season form, for the rows that show it. */
val LocalSeasonForms = staticCompositionLocalOf<Map<Long, SeasonForm>> { emptyMap() }

/** "Beat proj 5/6 · Steady" under a player's name; nothing until he has a finished week. */
@Composable
fun FormTag(p: Player) {
    val form = LocalSeasonForms.current[p.id] ?: return
    val share = form.beat.toDouble() / form.games
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = when { share >= 0.6 -> Good; share <= 0.4 -> Bad; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                fontWeight = FontWeight.SemiBold)) { append("Beat proj ${form.beat}/${form.games}") }
            form.style?.let { st ->
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" · ") }
                withStyle(SpanStyle(color = if (st == ScoringStyle.STEADY) Good else Warn, fontWeight = FontWeight.SemiBold)) {
                    append(st.label)
                }
            }
        },
        style = MaterialTheme.typography.labelSmall, maxLines = 1,
    )
}

/** Player sheet section: a hit/miss dot per week, the beat record, and floor / typical / ceiling. */
@Composable
private fun SeasonFormSection(form: SeasonForm) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionTitle("Season form", "Weeks he beat ESPN's projection, and his range once he has ${SeasonForm.MIN_GAMES}+ games.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Beat projection ${form.beat} of ${form.games}", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        form.results.forEach { hit ->
            Box(Modifier.padding(start = 3.dp).size(10.dp).clip(CircleShape).background(if (hit) Good else Bad.copy(alpha = 0.7f)))
        }
    }
    form.range?.let { r ->
        Row(Modifier.padding(top = 6.dp)) {
            listOf("Floor" to r.floor, "Typical" to r.typical, "Ceiling" to r.ceiling).forEach { (label, v) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
                    Text(fmt1(v), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            form.style?.let { st ->
                Column(Modifier.weight(1f)) {
                    Text("Style", style = MaterialTheme.typography.labelMedium, color = muted)
                    Text(st.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (st == ScoringStyle.STEADY) Good else Warn)
                }
            }
        }
    } ?: Text("Range shows after ${SeasonForm.MIN_GAMES} games.", style = MaterialTheme.typography.bodySmall, color = muted)
}
