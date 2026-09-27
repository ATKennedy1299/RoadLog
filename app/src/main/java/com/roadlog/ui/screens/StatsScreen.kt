package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.CumulativeStats
import com.roadlog.data.DistanceUnit
import com.roadlog.data.PeriodStats
import com.roadlog.data.Trip
import com.roadlog.ui.StatsViewModel
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onOpenTrip: (Long) -> Unit
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            Text(
                text = "STATS",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White
            )
        }

        if (stats.totalTrips == 0) {
            Text(
                text = "No completed trips yet. Finish a trip to see your stats here.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { HeroDistanceCard(stats, unit) }
                item { OverviewCard(stats, unit) }
                item {
                    LongestTripCard(
                        trip = stats.longestTrip,
                        unit = unit,
                        onClick = { stats.longestTrip?.let { onOpenTrip(it.id) } }
                    )
                }
                item {
                    FastestTripCard(
                        trip = stats.fastestTrip,
                        unit = unit,
                        onClick = { stats.fastestTrip?.let { onOpenTrip(it.id) } }
                    )
                }
                item {
                    Text(
                        text = "BY PERIOD",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item { PeriodCard("THIS WEEK", stats.thisWeek, unit) }
                item { PeriodCard("THIS MONTH", stats.thisMonth, unit) }
                item { PeriodCard("THIS YEAR", stats.thisYear, unit) }
                item { PeriodCard("ALL TIME", stats.allTime, unit) }
            }
        }
    }
}

@Composable
private fun HeroDistanceCard(stats: CumulativeStats, unit: DistanceUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Text(text = "TOTAL DISTANCE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = String.format(
                Locale.US, "%.1f %s", unit.metersToDistance(stats.totalDistanceMeters), unit.distanceLabel
            ),
            style = MaterialTheme.typography.headlineLarge,
            color = AccentGreen
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "across ${stats.totalTrips} trip${if (stats.totalTrips == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun OverviewCard(stats: CumulativeStats, unit: DistanceUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(label = "TOTAL TIME", value = formatDuration(stats.totalDurationMs / 1000))
            StatText(
                label = "HIGHEST SPEED",
                value = String.format(
                    Locale.US, "%.0f %s", unit.mpsToSpeed(stats.highestSpeedMps), unit.speedLabel
                )
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(
                label = "AVG DISTANCE",
                value = String.format(
                    Locale.US, "%.1f %s", unit.metersToDistance(stats.averageTripDistanceMeters), unit.distanceLabel
                )
            )
            StatText(label = "AVG DURATION", value = formatDuration(stats.averageTripDurationMs / 1000))
        }
    }
}

@Composable
private fun LongestTripCard(trip: Trip?, unit: DistanceUnit, onClick: () -> Unit) {
    if (trip == null) return
    val dateFormat = remember(trip.id) { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val durationSeconds = ((trip.endTimeEpochMs ?: trip.startTimeEpochMs) - trip.startTimeEpochMs) / 1000

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LONGEST TRIP", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(
                dateFormat.format(Date(trip.startTimeEpochMs)),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(
                label = "DISTANCE",
                value = String.format(
                    Locale.US, "%.1f %s", unit.metersToDistance(trip.distanceMeters), unit.distanceLabel
                )
            )
            StatText(label = "DURATION", value = formatDuration(durationSeconds))
        }
    }
}

@Composable
private fun FastestTripCard(trip: Trip?, unit: DistanceUnit, onClick: () -> Unit) {
    if (trip == null) return
    val dateFormat = remember(trip.id) { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("FASTEST TRIP", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(
                dateFormat.format(Date(trip.startTimeEpochMs)),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        StatText(
            label = "MAX SPEED",
            value = String.format(Locale.US, "%.0f %s", unit.mpsToSpeed(trip.maxSpeedMps), unit.speedLabel)
        )
    }
}

@Composable
private fun PeriodCard(label: String, period: PeriodStats, unit: DistanceUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(label = "TRIPS", value = period.tripCount.toString())
            StatText(
                label = "DISTANCE",
                value = String.format(
                    Locale.US, "%.1f %s", unit.metersToDistance(period.totalDistanceMeters), unit.distanceLabel
                )
            )
            StatText(label = "TIME", value = formatDuration(period.totalDurationMs / 1000))
        }
    }
}
