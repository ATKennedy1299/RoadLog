@file:OptIn(ExperimentalMaterial3Api::class)

package com.fantasyanalyzer.ui

import androidx.compose.foundation.background
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
fun PlayerSheet(p: Player, d: Dashboard, onDismiss: () -> Unit) {
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
                    Text(p.projected?.let { fmt1(it) } ?: "–", style = MaterialTheme.typography.headlineSmall)
                }
            }
            p.statLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            val log = gameLog(d, p.id)
            if (log.isNotEmpty()) {
                SectionTitle("Game log", "Season ${fmt1(log.sumOf { it.second })} pts · avg ${fmt1(log.map { it.second }.average())}")
                Row {
                    Text("Week", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = muted)
                    Text("Points", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = muted, textAlign = TextAlign.End)
                    Text("Proj", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = muted, textAlign = TextAlign.End)
                }
                log.forEach { (week, pts, proj) ->
                    Row {
                        Text("Wk $week", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                        Text(fmt1(pts), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            color = if (proj != null && pts >= proj) Good else if (proj != null) Bad else Color.Unspecified)
                        Text(proj?.let { fmt1(it) } ?: "–", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = muted, textAlign = TextAlign.End)
                    }
                }
            } else if (p.recentPoints.isNotEmpty()) {
                SectionTitle("Recent games")
                Text(p.recentPoints.joinToString(" · ") { fmt1(it) }, style = MaterialTheme.typography.bodyMedium)
            }

            val upcoming = upcoming(d, p)
            if (upcoming.isNotEmpty()) {
                SectionTitle("Coming up", "Opponent's rank against ${p.position}s: green is a soft matchup, red a tough one.")
                upcoming.forEach { (week, text, rank) ->
                    Row {
                        Text("Wk $week", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                        Text(text, style = MaterialTheme.typography.bodySmall, color = rankColor(rank) ?: Color.Unspecified)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
    }
}

/** Points by week from the league's box scores (any team's roster that week). */
private fun gameLog(d: Dashboard, playerId: Long): List<Triple<Int, Double, Double?>> =
    d.data.boxScores.toSortedMap().mapNotNull { (week, rosters) ->
        rosters.values.asSequence().flatten().firstOrNull { it.playerId == playerId }?.let { Triple(week, it.points, it.projected) }
    }

/** The next few NFL weeks: opponent (or bye) and how that defense ranks against the position. */
private fun upcoming(d: Dashboard, p: Player): List<Triple<Int, String, Int?>> {
    val pro = d.data.proTeams
    val (_, team) = pro.entries.firstOrNull { it.value.abbrev == p.proTeam }?.toPair() ?: return emptyList()
    val posId = POSITIONS.entries.firstOrNull { it.value == p.position }?.key
    val from = d.league.currentScoringPeriod + 1
    return (from until from + 4).filter { it <= d.league.finalScoringPeriod }.map { week ->
        val g = team.games[week]
        if (g == null || team.byeWeek == week) {
            Triple(week, "BYE", null)
        } else {
            val opp = pro[g.opponentId]?.abbrev ?: "?"
            val rank = posId?.let { d.data.ratings[it]?.get(g.opponentId) }
            Triple(week, (if (g.home) opp else "@$opp") + (rank?.let { " (${ordinal(it)})" } ?: "") + " · " + kickoffText(g.kickoff), rank)
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
