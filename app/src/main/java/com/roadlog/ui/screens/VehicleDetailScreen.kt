package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.CumulativeStats
import com.roadlog.data.DistanceUnit
import com.roadlog.data.MonthlyDistance
import com.roadlog.data.Trip
import com.roadlog.data.TripStatus
import com.roadlog.data.VehicleProfile
import com.roadlog.data.VehicleType
import com.roadlog.ui.VehicleDetailViewModel
import com.roadlog.ui.theme.AccentAmber
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class VehicleTab(val label: String) {
    OVERVIEW("OVERVIEW"), TRIPS("TRIPS"), STATS("STATS")
}

@Composable
fun VehicleDetailScreen(
    viewModel: VehicleDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTrip: (Long) -> Unit
) {
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val vehicleTrips by viewModel.vehicleTrips.collectAsStateWithLifecycle()
    val monthlyDistances by viewModel.monthlyDistances.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(VehicleTab.OVERVIEW) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Text(
            text = "‹ BACK",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Spacer(Modifier.height(16.dp))

        val currentVehicle = vehicle
        if (currentVehicle == null) {
            Text("Vehicle not found.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } else {
            VehicleHeaderRow(currentVehicle, onClick = onEdit)
            Spacer(Modifier.height(20.dp))
            VehicleTabRow(selected = selectedTab, onSelect = { selectedTab = it })
            Spacer(Modifier.height(20.dp))

            when (selectedTab) {
                VehicleTab.OVERVIEW -> VehicleOverviewTab(
                    stats = stats,
                    monthlyDistances = monthlyDistances,
                    unit = unit,
                    recentTrip = vehicleTrips.firstOrNull { it.status == TripStatus.COMPLETED },
                    onOpenTrip = onOpenTrip
                )
                VehicleTab.TRIPS -> VehicleTripsTab(trips = vehicleTrips, unit = unit, onOpenTrip = onOpenTrip)
                VehicleTab.STATS -> VehicleStatsTab(stats = stats, unit = unit)
            }
        }
    }
}

@Composable
private fun VehicleHeaderRow(vehicle: VehicleProfile, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        VehicleThumbnail(photoPath = vehicle.photoPath, colorHex = vehicle.colorHex, size = 64.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = vehicle.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            val subtitle = listOfNotNull(
                vehicle.year?.toString(),
                vehicle.make,
                if (vehicle.type == VehicleType.MOTORCYCLE) "Motorcycle" else "Car"
            ).joinToString(" · ")
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
    }
}

@Composable
private fun VehicleTabRow(selected: VehicleTab, onSelect: (VehicleTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(4.dp)
    ) {
        VehicleTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.Black else TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) AccentGreen else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun VehicleOverviewTab(
    stats: CumulativeStats,
    monthlyDistances: List<MonthlyDistance>,
    unit: DistanceUnit,
    recentTrip: Trip?,
    onOpenTrip: (Long) -> Unit
) {
    if (stats.totalTrips == 0) {
        Text(
            text = "No completed trips logged on this vehicle yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatText(
                    label = "TOTAL DISTANCE",
                    value = String.format(
                        Locale.US, "%.0f %s", unit.metersToDistance(stats.totalDistanceMeters), unit.distanceLabel
                    )
                )
                StatText(label = "TOTAL TIME", value = formatDuration(stats.totalDurationMs / 1000))
                StatText(
                    label = "TOP SPEED",
                    value = String.format(
                        Locale.US, "%.0f %s", unit.mpsToSpeed(stats.highestSpeedMps), unit.speedLabel
                    )
                )
            }
        }
        if (monthlyDistances.any { it.distanceMeters > 0 }) {
            item { MonthlyDistanceChart(monthlyDistances) }
        }
        if (recentTrip != null) {
            item { RecentTripCard(recentTrip, unit, onClick = { onOpenTrip(recentTrip.id) }) }
        }
    }
}

@Composable
private fun MonthlyDistanceChart(months: List<MonthlyDistance>) {
    val maxValue = months.maxOfOrNull { it.distanceMeters }?.coerceAtLeast(1.0) ?: 1.0
    Row(
        modifier = Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        months.forEach { month ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val fraction = (month.distanceMeters / maxValue).toFloat().coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height((100.dp * fraction).coerceAtLeast(4.dp))
                        .background(AccentGreen, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                )
                Spacer(Modifier.height(6.dp))
                Text(month.monthLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun RecentTripCard(trip: Trip, unit: DistanceUnit, onClick: () -> Unit) {
    val dateFormat = remember(trip.id) { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val durationSeconds = ((trip.endTimeEpochMs ?: trip.startTimeEpochMs) - trip.startTimeEpochMs) / 1000

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("RECENT TRIP", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceRaised, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = dateFormat.format(Date(trip.startTimeEpochMs)),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = String.format(
                        Locale.US, "%.1f %s · %s",
                        unit.metersToDistance(trip.distanceMeters), unit.distanceLabel, formatDuration(durationSeconds)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
        }
    }
}

@Composable
private fun VehicleTripsTab(trips: List<Trip>, unit: DistanceUnit, onOpenTrip: (Long) -> Unit) {
    if (trips.isEmpty()) {
        Text(
            text = "No trips logged on this vehicle yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(trips, key = { it.id }) { trip ->
            VehicleTripRow(trip, unit, onClick = { onOpenTrip(trip.id) })
        }
    }
}

@Composable
private fun VehicleTripRow(trip: Trip, unit: DistanceUnit, onClick: () -> Unit) {
    val dateFormat = remember(trip.id) { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val durationSeconds = ((trip.endTimeEpochMs ?: System.currentTimeMillis()) - trip.startTimeEpochMs) / 1000

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = dateFormat.format(Date(trip.startTimeEpochMs)),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            if (trip.status == TripStatus.ACTIVE) {
                Text("● LIVE", color = AccentAmber, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(
                label = "DISTANCE",
                value = String.format(
                    Locale.US, "%.1f %s", unit.metersToDistance(trip.distanceMeters), unit.distanceLabel
                )
            )
            StatText(label = "DURATION", value = formatDuration(durationSeconds))
            StatText(
                label = "MAX SPEED",
                value = String.format(Locale.US, "%.0f %s", unit.mpsToSpeed(trip.maxSpeedMps), unit.speedLabel)
            )
        }
    }
}

@Composable
private fun VehicleStatsTab(stats: CumulativeStats, unit: DistanceUnit) {
    if (stats.totalTrips == 0) {
        Text(
            text = "No completed trips logged on this vehicle yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { VehicleOverviewCard(stats, unit) }
        item { PeriodCard("THIS WEEK", stats.thisWeek, unit) }
        item { PeriodCard("THIS MONTH", stats.thisMonth, unit) }
        item { PeriodCard("THIS YEAR", stats.thisYear, unit) }
        item { PeriodCard("ALL TIME", stats.allTime, unit) }
    }
}

@Composable
private fun VehicleOverviewCard(stats: CumulativeStats, unit: DistanceUnit) {
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
                label = "TOP SPEED",
                value = String.format(Locale.US, "%.0f %s", unit.mpsToSpeed(stats.highestSpeedMps), unit.speedLabel)
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
