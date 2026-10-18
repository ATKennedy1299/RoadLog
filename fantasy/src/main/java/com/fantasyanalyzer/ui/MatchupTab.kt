@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)

package com.fantasyanalyzer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fantasyanalyzer.analysis.MatchupSide
import com.fantasyanalyzer.analysis.MatchupView
import com.fantasyanalyzer.analysis.Matchups
import com.fantasyanalyzer.analysis.SlotRow
import com.fantasyanalyzer.analysis.fmt1
import com.fantasyanalyzer.analysis.orientedFor
import com.fantasyanalyzer.data.Matchup
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.RosterSpot
import kotlinx.coroutines.launch

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
    // Keyed on the dashboard too, so a refresh (which clears loaded weeks) asks again.
    LaunchedEffect(week, d) { onRequestWeek(week) }

    // The user's team always on the left.
    val matchups = Matchups.inWeek(league, week, myTeam).map { it.orientedFor(myTeam) }

    Column(Modifier.padding(top = 4.dp)) {
        WeekBar(week, weeks, weekBadge(week, league.currentWeek, matchups)) { week = it }
        if (week != startWeek) {
            TextButton(onClick = { week = startWeek }, Modifier.align(Alignment.CenterHorizontally)) { Text("Back to this week") }
        }
        if (matchups.isEmpty()) {
            Text("No matchups this week.", Modifier.padding(top = 16.dp))
            return@Column
        }
        // A fresh pager per week, so swiping always drives the week on screen.
        key(week) {
            WeekMatchups(d, matchups, weekLineups[week])
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekMatchups(d: Dashboard, matchups: List<Matchup>, lineups: WeekLineups?) {
    val league = d.league
    val now = remember(d, lineups) { System.currentTimeMillis() }
    val rosters = (lineups as? WeekLineups.Loaded)?.rosters
    // Computed once per week and lineup load, not on every frame of a swipe.
    val views = remember(d, matchups, rosters, now) { matchups.map { Matchups.view(league, it, rosters, now) } }
    val n = views.size
    // Looping: lots of virtual pages cycling through the matchups, starting in the middle,
    // so swiping past either end wraps around.
    val loops = if (n > 1) LOOPS else 1
    val pager = rememberPagerState(initialPage = if (n > 1) (loops / 2) * n else 0) { n * loops }
    val scope = rememberCoroutineScope()
    val tiles = rememberLazyListState()
    // Dots and tile highlight follow the finger; the lineup table and tile scroll wait for the card to
    // settle, so nothing heavy rebuilds mid-swipe.
    val idx = pager.currentPage % n
    val settled = pager.settledPage % n

    LaunchedEffect(settled) { tiles.animateScrollToItem((settled - 1).coerceAtLeast(0)) }

    // Every matchup this week; tap one, or swipe the card below.
    LazyRow(
        state = tiles,
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(views.size) { i ->
            MatchupTile(d, views[i], i == idx) {
                // Nearest virtual page showing matchup i, so a tap never spins through the loop.
                val base = pager.currentPage - idx
                val target = listOf(base + i - n, base + i, base + i + n).minBy { kotlin.math.abs(it - pager.currentPage) }
                scope.launch { pager.animateScrollToPage(target, animationSpec = SMOOTH) }
            }
        }
    }

    // The card follows the finger; let go past halfway (or flick) and it settles on the next matchup.
    HorizontalPager(
        state = pager,
        pageSpacing = 12.dp,
        beyondBoundsPageCount = 1, // neighbours are ready before they slide in
        flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = SMOOTH),
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        ScoreCard(d, views[page % n])
    }
    if (n > 1) PageDots(n, idx)

    val v = views[settled]
    val away = v.away
    if (!v.matchup.complete && rosters != null && away != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(leftToPlay(rosters[v.home.teamId], now), Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(leftToPlay(rosters[away.teamId], now), Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End)
        }
    }

    when {
        lineups == null || lineups is WeekLineups.Loading -> Row(
            Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
        lineups is WeekLineups.Unavailable -> Text(lineups.message, Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        v.starters.all { it.home == null && it.away == null } -> Text("No lineups set for this matchup yet.",
            Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("STARTERS", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.gamesInProgress) FieldLegend()
            }
            LineupPanel(v.starters, now)
            // The bench follows straight on; the gap and BE in the middle mark where it starts.
            if (v.bench.isNotEmpty()) {
                LineupPanel(v.bench, now, Modifier.padding(top = 14.dp))
            }
        }
    }
}

/** Virtual page sets for looping; plenty for any amount of swiping. */
private const val LOOPS = 1000

/** A soft, no-bounce settle for the score card. */
private val SMOOTH = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

// --- Week bar -------------------------------------------------------------------------------

private enum class WeekBadge(val label: String) { LIVE("LIVE"), FINAL("FINAL"), UPCOMING("UPCOMING"), PAST("") }

private fun weekBadge(week: Int, current: Int, matchups: List<Matchup>): WeekBadge = when {
    matchups.isNotEmpty() && matchups.all { it.complete } -> WeekBadge.FINAL
    week == current -> WeekBadge.LIVE
    week > current -> WeekBadge.UPCOMING
    else -> WeekBadge.PAST
}

@Composable
private fun WeekBar(week: Int, weeks: List<Int>, badge: WeekBadge, onWeek: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onWeek(weeks.lastOrNull { it < week } ?: week) }, enabled = week > weeks.first()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week", tint = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Week $week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (badge.label.isNotEmpty()) {
                val live = badge == WeekBadge.LIVE
                Text(
                    badge.label,
                    Modifier.padding(start = 8.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (live) Good.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = if (live) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onWeek(weeks.firstOrNull { it > week } ?: week) }, enabled = week < weeks.last()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

// --- Tiles and dots -------------------------------------------------------------------------

private fun abbrev(d: Dashboard, teamId: Int): String =
    d.league.teams[teamId]?.abbrev?.ifBlank { null } ?: d.league.teamName(teamId).take(4)

@Composable
private fun MatchupTile(d: Dashboard, v: MatchupView, selected: Boolean, onClick: () -> Unit) {
    val m = v.matchup
    val away = v.away
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.width(124.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TileLine(d, v.home.teamId, v.home.actual, m.complete && m.winner == "HOME")
            if (away != null) TileLine(d, away.teamId, away.actual, m.complete && m.winner == "AWAY")
            else Text("Bye", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TileLine(d: Dashboard, teamId: Int, points: Double, won: Boolean) {
    val team = d.league.teams[teamId]
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team?.logo, abbrev(d, teamId), 20.dp, shape = CircleShape)
        Text(abbrev(d, teamId), Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium,
            fontWeight = if (won) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(fmt1(points), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (i == current) 18.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (i == current) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

// --- Score card -----------------------------------------------------------------------------

/** "W2" / "L1": the team's current streak of results. */
private fun streak(d: Dashboard, teamId: Int): String? {
    val results = d.games.filter { it.teamId == teamId }.sortedBy { it.week }.map { it.result }
    val last = results.lastOrNull() ?: return null
    return "$last${results.takeLastWhile { it == last }.size}"
}

@Composable
private fun ScoreCard(d: Dashboard, v: MatchupView) {
    val away = v.away
    val homeWin = v.homeWinProbability
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamColumn(d, v.home, homeWin, v.matchup.complete, mirrored = false, modifier = Modifier.weight(1f))
            Text("vs", Modifier.width(32.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (away != null) {
                TeamColumn(d, away, homeWin?.let { 1 - it }, v.matchup.complete, mirrored = true, modifier = Modifier.weight(1f))
            } else {
                Text("Bye", Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * One side of the score card. Under the score, the team's own win chance
 * with a bar (filling from the middle outward), or WIN/LOSS once final.
 */
@Composable
private fun TeamColumn(d: Dashboard, side: MatchupSide, win: Double?, complete: Boolean, mirrored: Boolean, modifier: Modifier) {
    val team = d.league.teams[side.teamId]
    val record = d.standings.firstOrNull { it.teamId == side.teamId }?.record?.toString()
    val streak = streak(d, side.teamId)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TeamLogo(team?.logo, abbrev(d, side.teamId), 52.dp, shape = CircleShape)
        Text(d.league.teamName(side.teamId), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row {
            if (record != null) Text(record, style = MaterialTheme.typography.labelSmall, color = muted)
            if (streak != null) {
                Text(" · $streak", style = MaterialTheme.typography.labelSmall,
                    color = if (streak.startsWith("W")) Good else if (streak.startsWith("L")) Bad else muted)
            }
        }
        Text(fmt1(side.actual), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        side.projected?.let { Text("Proj ${fmt1(it)}", style = MaterialTheme.typography.labelMedium, color = muted) }
        when {
            win == null -> {}
            complete -> Text(
                if (win >= 1.0) "WIN" else if (win <= 0.0) "LOSS" else "TIE",
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                color = if (win >= 1.0) Good else if (win <= 0.0) Bad else muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            else -> {
                val label = pct(win).replace(".0%", "%")
                val favored = win >= 0.5
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val percent: @Composable () -> Unit = {
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = if (favored) Good else muted)
                    }
                    if (!mirrored) {
                        percent()
                        Spacer(Modifier.width(6.dp))
                    }
                    LinearProgressIndicator(
                        progress = { win.toFloat() },
                        modifier = Modifier.weight(1f).height(6.dp)
                            .then(if (mirrored) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier),
                        color = if (favored) Good else muted,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                    if (mirrored) {
                        Spacer(Modifier.width(6.dp))
                        percent()
                    }
                }
            }
        }
    }
}

/** "3 left to play": starters whose game hasn't finished (and who will play). */
private fun leftToPlay(roster: List<RosterSpot>?, now: Long): String {
    val n = roster.orEmpty().count { it.started && !it.player.isOut && it.player.remainingShare(now) > 0 }
    return if (n == 0) "All done" else "$n left to play"
}

// --- Lineup rows ----------------------------------------------------------------------------

/** A rounded panel of side-by-side rows. */
@Composable
private fun LineupPanel(rows: List<SlotRow>, now: Long, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            SlotLine(row, now)
        }
    }
}

@Composable
private fun SlotLine(row: SlotRow, now: Long) {
    // Tint the score of whoever's ahead at this slot, once anyone has played.
    val hp = row.home?.player?.takeIf { it.gameStarted(now) }?.pointsSoFar(now)
    val ap = row.away?.player?.takeIf { it.gameStarted(now) }?.pointsSoFar(now)
    val anyPlayed = hp != null || ap != null
    val homeAhead = anyPlayed && (hp ?: 0.0) > (ap ?: 0.0)
    val awayAhead = anyPlayed && (ap ?: 0.0) > (hp ?: 0.0)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        PlayerCell(row.home, now, mirrored = false, ahead = homeAhead, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(38.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentAlignment = Alignment.Center,
        ) {
            Text(row.slot.replace("/", "/\n"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 12.sp)
        }
        PlayerCell(row.away, now, mirrored = true, ahead = awayAhead, modifier = Modifier.weight(1f))
    }
}

/** Name and game toward the outside, points over projection in a chip toward the middle. */
@Composable
private fun PlayerCell(spot: RosterSpot?, now: Long, mirrored: Boolean, ahead: Boolean, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (spot == null) {
        Box(modifier.padding(12.dp), contentAlignment = if (mirrored) Alignment.CenterEnd else Alignment.CenterStart) {
            Text("Empty", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        return
    }
    val p = spot.player
    val open = LocalOpenPlayer.current
    // Short lines stacked, so names get the full width: name / team + opponent / game / stats.
    val align = if (mirrored) TextAlign.End else TextAlign.Start
    val info: @Composable (Modifier) -> Unit = { m ->
        Column(m, horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shortName(p.name), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                StatusChip(p)
            }
            Text(matchupLine(p, muted), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = align)
            gameStateText(p, now)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = align, color = if (p.game?.state == NflGame.IN) Good else muted)
            }
            p.statLine?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = align, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    val chip: @Composable () -> Unit = {
        Column(
            Modifier.widthIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                .background(if (ahead) Good.copy(alpha = 0.16f) else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (p.gameStarted(now)) fmt1(p.pointsSoFar(now)) else "–", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(p.liveProjection(now)?.let { fmt1(it) } ?: "–", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
    // ESPN's live outline: red in the red zone, orange on the field.
    val outline = fieldColor(p.fieldStatus)
    Row(
        modifier.padding(2.dp)
            .then(if (outline != null) Modifier.border(2.dp, outline, RoundedCornerShape(10.dp)) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .clickable { open(p) }.padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mirrored) {
            chip()
            info(Modifier.weight(1f).padding(start = 4.dp))
        } else {
            info(Modifier.weight(1f).padding(end = 4.dp))
            chip()
        }
    }
}
