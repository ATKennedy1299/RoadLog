package com.roadlog.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
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
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import com.roadlog.util.TripEvent
import com.roadlog.util.TripEventType
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
    var showDeleteDialog by remember { mutableStateOf(false) }
    var suggestionDismissed by remember(trip?.id) { mutableStateOf(false) }

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
