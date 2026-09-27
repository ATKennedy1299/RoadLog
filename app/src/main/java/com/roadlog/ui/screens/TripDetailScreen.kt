package com.roadlog.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.DistanceUnit
import com.roadlog.data.Trip
import com.roadlog.data.TripStatus
import com.roadlog.data.VehicleProfile
import com.roadlog.ui.TripDetailViewModel
import com.roadlog.ui.theme.AccentAmber
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.AccentRed
import com.roadlog.ui.theme.DividerColor
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import com.roadlog.util.SpeedDistanceSample
import com.roadlog.util.TripEvent
import com.roadlog.util.TripEventType
import com.roadlog.util.TripPerformanceStats
import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    onBack: () -> Unit,
    onOpenReplay: () -> Unit
) {
    val trip by viewModel.trip.collectAsStateWithLifecycle()
    val vehicleProfile by viewModel.vehicleProfile.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val suggestedVehicle by viewModel.suggestedVehicle.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val routePoints by viewModel.routePoints.collectAsStateWithLifecycle()
    val routeMapPoints = remember(routePoints) {
        routePoints.map { RoutePoint(LatLng(it.latitude, it.longitude), it.gpsSpeedMps, it.startsNewSegment) }
    }
    val tripEvents by viewModel.tripEvents.collectAsStateWithLifecycle()
    val routeEventMarkers = remember(tripEvents) {
        tripEvents.map { RouteEventMarker(LatLng(it.latitude, it.longitude), it.type) }
    }
    val performanceStats by viewModel.performanceStats.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var suggestionDismissed by remember(trip?.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = "‹ BACK",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "TRIP DETAIL",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )
        Spacer(Modifier.height(24.dp))

        val currentTrip = trip
        if (currentTrip == null) {
            Text(
                text = "Trip not found.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        } else {
            RouteMapView(
                points = routeMapPoints,
                events = routeEventMarkers,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp)),
                onMapReady = { map ->
                    map.addOnMapClickListener {
                        onOpenReplay()
                        true
                    }
                }
            )
            Text(
                text = "Tap the map to replay this trip",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(20.dp))
            EventStats(tripEvents)
            Spacer(Modifier.height(20.dp))
            TripStats(
                trip = currentTrip,
                vehicleProfile = vehicleProfile,
                vehicles = vehicles,
                unit = unit,
                onChangeVehicle = viewModel::changeVehicle
            )

            if (currentTrip.status != TripStatus.ACTIVE) {
                Spacer(Modifier.height(16.dp))
                PerformanceCard(performanceStats, unit)

                if (performanceStats.quarterMileSamples.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    QuarterMileChart(performanceStats.quarterMileSamples, unit)
                }
            }

            val suggestion = suggestedVehicle
            if (suggestion != null && !suggestionDismissed) {
                Spacer(Modifier.height(16.dp))
                VehicleSuggestionBanner(
                    vehicleName = suggestion.name,
                    onAccept = {
                        viewModel.changeVehicle(suggestion.id)
                        suggestionDismissed = true
                    },
                    onDismiss = { suggestionDismissed = true }
                )
            }

            if (currentTrip.status != TripStatus.ACTIVE && currentTrip.speedLimitDebugInfo != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "DEBUG (speed limits): ${currentTrip.speedLimitDebugInfo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            if (currentTrip.status != TripStatus.ACTIVE && currentTrip.motionDebugInfo != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "DEBUG: ${currentTrip.motionDebugInfo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            if (currentTrip.status != TripStatus.ACTIVE) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentRed,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("DELETE TRIP", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showDeleteDialog) {
        DeleteTripDialog(
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteTrip(onDeleted = onBack)
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}

@Composable
private fun TripStats(
    trip: Trip,
    vehicleProfile: VehicleProfile?,
    vehicles: List<VehicleProfile>,
    unit: DistanceUnit,
    onChangeVehicle: (Long) -> Unit
) {
    val dateFormat = remember(trip.id) { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val durationSeconds = ((trip.endTimeEpochMs ?: System.currentTimeMillis()) - trip.startTimeEpochMs) / 1000
    val distance = unit.metersToDistance(trip.distanceMeters)
    val maxSpeed = unit.mpsToSpeed(trip.maxSpeedMps)
    val avgSpeedMps = if (durationSeconds > 0) trip.distanceMeters / durationSeconds else 0.0
    val avgSpeed = unit.mpsToSpeed(avgSpeedMps)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(label = "DISTANCE", value = String.format(Locale.US, "%.1f %s", distance, unit.distanceLabel))
            StatText(label = "DURATION", value = formatDuration(durationSeconds))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(label = "MAX SPEED", value = String.format(Locale.US, "%.0f %s", maxSpeed, unit.speedLabel))
            StatText(label = "AVG SPEED", value = String.format(Locale.US, "%.0f %s", avgSpeed, unit.speedLabel))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(label = "STARTED", value = dateFormat.format(Date(trip.startTimeEpochMs)))
            StatText(
                label = "ENDED",
                value = trip.endTimeEpochMs?.let { dateFormat.format(Date(it)) } ?: "In progress",
                valueColor = if (trip.endTimeEpochMs == null) AccentAmber else AccentGreen
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            VehicleStat(
                vehicleName = vehicleProfile?.name ?: "Unknown",
                vehicles = vehicles,
                // Re-categorizing an active trip isn't offered — the recording
                // notification/state don't need to react to it mid-trip, and
                // the use case is fixing a wrong guess after the fact.
                editable = trip.status != TripStatus.ACTIVE,
                onSelect = onChangeVehicle
            )
            StatText(label = "GPS POINTS", value = trip.pointCount.toString())
        }
    }
}

/**
 * Per-trip counts for the dots drawn on the map above (see
 * TripEventAnalyzer) — each label doubles as the map's legend, using the
 * same colored "●" glyph convention the app already uses instead of icons.
 */
@Composable
private fun EventStats(events: List<TripEvent>) {
    val hardAccelCount = events.count { it.type == TripEventType.HARD_ACCEL }
    val hardBrakeCount = events.count { it.type == TripEventType.HARD_BRAKE }
    val speedingCount = events.count { it.type == TripEventType.SPEEDING }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        EventStatItem(color = AccentGreen, label = "HARD ACCEL", count = hardAccelCount)
        EventStatItem(color = AccentRed, label = "HARD BRAKE", count = hardBrakeCount)
        EventStatItem(color = AccentAmber, label = "SPEEDING", count = speedingCount)
    }
}

@Composable
private fun EventStatItem(color: Color, label: String, count: Int) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "● ", color = color, style = MaterialTheme.typography.labelSmall)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Text(text = count.toString(), style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/**
 * This trip's best acceleration/elapsed-time runs (see PerformanceAnalyzer)
 * — blank ("—") for whichever this trip never achieved, e.g. a trip that
 * topped out at 80 mph shows no 100-130 time.
 */
@Composable
private fun PerformanceCard(stats: TripPerformanceStats, unit: DistanceUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("PERFORMANCE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(label = "0-60", value = formatRunTime(stats.zeroToSixtyMs))
            StatText(label = "60-100", value = formatRunTime(stats.sixtyToHundredMs))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(label = "60-130", value = formatRunTime(stats.sixtyToOneThirtyMs))
            StatText(label = "100-130", value = formatRunTime(stats.hundredToOneThirtyMs))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatText(
                label = "1/8 MILE",
                value = formatMileTime(stats.eighthMileMs, stats.eighthMileTrapSpeedMps, unit)
            )
            StatText(
                label = "1/4 MILE",
                value = formatMileTime(stats.quarterMileMs, stats.quarterMileTrapSpeedMps, unit)
            )
        }
    }
}

private fun formatRunTime(ms: Long?): String =
    ms?.let { String.format(Locale.US, "%.1fs", it / 1000.0) } ?: "—"

private fun formatMileTime(ms: Long?, trapSpeedMps: Double?, unit: DistanceUnit): String {
    if (ms == null) return "—"
    val time = String.format(Locale.US, "%.1fs", ms / 1000.0)
    val trap = trapSpeedMps?.let { String.format(Locale.US, " @ %.0f %s", unit.mpsToSpeed(it), unit.speedLabel) }.orEmpty()
    return time + trap
}

// Distance markers are fixed to the drag-strip standard (feet) regardless of
// the display unit, matching how the mile splits themselves are defined —
// same convention SpeedZone already uses for its mph-based thresholds.
private const val QUARTER_MILE_METERS = 402.336

/**
 * Speed vs. distance for the specific run that set quarterMileMs (see
 * PerformanceAnalyzer) — a single line, since there's only one series to
 * show and the card title already names it. Peak speed is called out once
 * as a direct label rather than repeating a number at every sample.
 */
@Composable
private fun QuarterMileChart(samples: List<SpeedDistanceSample>, unit: DistanceUnit) {
    val maxSpeedMps = samples.maxOf { it.speedMps }.coerceAtLeast(1.0)
    val peakLabel = String.format(Locale.US, "%.0f %s", unit.mpsToSpeed(maxSpeedMps), unit.speedLabel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1/4 MILE — SPEED", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(peakLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val baselineY = size.height
            drawLine(
                color = DividerColor,
                start = Offset(0f, baselineY),
                end = Offset(size.width, baselineY),
                strokeWidth = 1.dp.toPx()
            )

            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = (sample.distanceMeters / QUARTER_MILE_METERS).toFloat().coerceIn(0f, 1f) * size.width
                val y = size.height - (sample.speedMps / maxSpeedMps).toFloat().coerceIn(0f, 1f) * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = AccentGreen,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text("1/8 MI", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text("1/4 MI", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun VehicleSuggestionBanner(vehicleName: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Looks like this might be your $vehicleName",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Based on how the phone leaned through turns during this trip — still experimental.",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = "USE THIS",
                style = MaterialTheme.typography.labelSmall,
                color = AccentGreen,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onAccept)
            )
            Text(
                text = "DISMISS",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.clickable(onClick = onDismiss)
            )
        }
    }
}

@Composable
private fun VehicleStat(
    vehicleName: String,
    vehicles: List<VehicleProfile>,
    editable: Boolean,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Column(
            modifier = Modifier.then(
                if (editable) Modifier.clickable { expanded = true } else Modifier
            )
        ) {
            Text(text = "VEHICLE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = vehicleName, style = MaterialTheme.typography.titleMedium, color = AccentGreen)
                if (editable) {
                    Text(
                        text = " ▾",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary
                    )
                }
            }
        }
        if (editable) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                vehicles.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.name) },
                        onClick = {
                            onSelect(candidate.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
