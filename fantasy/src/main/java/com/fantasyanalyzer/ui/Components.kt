package com.fantasyanalyzer.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fantasyanalyzer.analysis.Matrix

val Good = Color(0xFF2E7D32)
val Bad = Color(0xFFC62828)

/** Charcoal greys and ESPN-style blue: background darkest, rows a step lighter, cards lighter still. */
private val EspnDark = darkColorScheme(
    primary = Color(0xFF6EA8FE),
    onPrimary = Color(0xFF0B1E3A),
    primaryContainer = Color(0xFF24476F),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFFFB648),
    onSecondary = Color(0xFF2B1D00),
    background = Color(0xFF1E2023),
    onBackground = Color(0xFFF1F3F5),
    surface = Color(0xFF1E2023),
    onSurface = Color(0xFFF1F3F5),
    surfaceVariant = Color(0xFF26292D),
    onSurfaceVariant = Color(0xFFA3A9B0),
    surfaceContainerLowest = Color(0xFF191B1E),
    surfaceContainerLow = Color(0xFF222428),
    surfaceContainer = Color(0xFF2A2D31),
    surfaceContainerHigh = Color(0xFF2F3237),
    surfaceContainerHighest = Color(0xFF34383D),
    outline = Color(0xFF6B727B),
    outlineVariant = Color(0xFF3A3E44),
)

@Composable
fun FantasyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        // Dark: a fixed charcoal palette like ESPN's app rather than the system's near-black.
        dark -> EspnDark
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        else -> lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFFEF6C00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SectionTitle(text: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Horizontal bar scaled to [fraction] of the available width. */
@Composable
fun BarRow(label: String, fraction: Float, valueText: String, color: Color = MaterialTheme.colorScheme.primary, leading: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            Text(leading, Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label, Modifier.width(128.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(18.dp)) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp)).background(color)
            )
        }
        Text(valueText, Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
    }
}

/** Bar that grows right (green) for positive values and left (red) for negative ones. */
@Composable
fun DivergingBarRow(label: String, value: Double, maxAbs: Double, valueText: String) {
    val frac = if (maxAbs > 0) (kotlin.math.abs(value) / maxAbs).toFloat().coerceIn(0.02f, 1f) else 0.02f
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(128.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Row(Modifier.weight(1f).height(18.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                if (value < 0) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Bad))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (value >= 0) Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Good))
            }
        }
        Text(valueText, Modifier.width(52.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End,
            color = if (value >= 0) Good else Bad)
    }
}

/**
 * Weekly score lines: every team faint, the highlighted team bold, and the
 * league average dashed.
 */
@Composable
fun LineChart(series: Map<String, List<Pair<Int, Double>>>, highlight: String?, modifier: Modifier = Modifier) {
    val all = series.values.flatten()
    if (all.isEmpty()) return
    val minX = all.minOf { it.first }
    val maxX = all.maxOf { it.first }
    val minY = all.minOf { it.second }
    val maxY = all.maxOf { it.second }
    val average = all.groupBy { it.first }.mapValues { (_, v) -> v.map { it.second }.average() }.toSortedMap().toList()

    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val strong = MaterialTheme.colorScheme.primary
    val avgColor = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant

    Column(modifier) {
        Row {
            Column(Modifier.width(40.dp).height(200.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(maxY.toInt().toString(), style = MaterialTheme.typography.labelSmall)
                Text(((minY + maxY) / 2).toInt().toString(), style = MaterialTheme.typography.labelSmall)
                Text(minY.toInt().toString(), style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.weight(1f).height(200.dp)) {
                val xSpan = (maxX - minX).coerceAtLeast(1)
                val ySpan = (maxY - minY).takeIf { it > 0 } ?: 1.0
                fun pt(p: Pair<Int, Double>) = Offset(
                    size.width * (p.first - minX) / xSpan,
                    size.height * (1 - ((p.second - minY) / ySpan).toFloat()),
                )
                fun line(points: List<Pair<Int, Double>>, color: Color, width: Dp, dashed: Boolean = false) {
                    if (points.isEmpty()) return
                    val path = Path()
                    points.forEachIndexed { i, p -> pt(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
                    drawPath(path, color, style = Stroke(width.toPx(),
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null))
                }
                for (f in listOf(0f, 0.5f, 1f)) {
                    drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1.dp.toPx())
                }
                series.filterKeys { it != highlight }.values.forEach { line(it.sortedBy { p -> p.first }, faint, 1.5.dp) }
                line(average, avgColor, 2.dp, dashed = true)
                series[highlight]?.sortedBy { it.first }?.let { pts ->
                    line(pts, strong, 3.dp)
                    pts.forEach { drawCircle(strong, 4.dp.toPx(), pt(it)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Wk $minX", style = MaterialTheme.typography.labelSmall)
            Text("Wk $maxX", style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.padding(start = 40.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(strong))
            Text(" ${highlight ?: ""}   ", style = MaterialTheme.typography.labelSmall)
            Box(Modifier.size(10.dp).background(avgColor))
            Text(" League average", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Team-by-team grid, horizontally scrollable, labelled with team abbreviations. */
@Composable
fun <T> MatrixTable(
    matrix: Matrix<T>,
    abbrevs: List<String>,
    cellText: (T) -> String,
    cellColor: @Composable (row: Int, col: Int, value: T) -> Color,
) {
    val cellW = 54.dp
    val border = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            Row {
                Spacer(Modifier.width(cellW))
                abbrevs.forEach {
                    Text(it, Modifier.width(cellW).padding(4.dp), style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            matrix.cells.forEachIndexed { r, row ->
                Row {
                    Text(abbrevs[r], Modifier.width(cellW).padding(vertical = 8.dp, horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    row.forEachIndexed { c, v ->
                        Box(
                            Modifier.width(cellW).height(34.dp).border(0.5.dp, border).background(cellColor(r, c, v)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(cellText(v), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Picker(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
            Text("$label: ${options.getOrElse(selected) { "" }}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false })
            }
        }
    }
}
