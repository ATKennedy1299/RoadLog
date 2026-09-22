package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.DistanceUnit
import com.roadlog.data.Trip
import com.roadlog.data.TripStatus
import com.roadlog.data.VehicleProfile
import com.roadlog.ui.HomeViewModel
import com.roadlog.ui.theme.AccentAmber
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.AccentRed
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onTripClick: (Long) -> Unit,
    onOpenStats: () -> Unit,
    onOpenGarage: () -> Unit,
    onToggleAutoDetect: () -> Unit
) {
    val activeTrip by viewModel.activeTrip.collectAsStateWithLifecycle()
    val history by viewModel.tripHistory.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val autoDetectEnabled by viewModel.autoDetectEnabled.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val selectedVehicleId by viewModel.selectedVehicleId.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Trip?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ROADLOG",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "STATS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable(onClick = onOpenStats)
                )
                Text(
                    text = "GARAGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.clickable(onClick = onOpenGarage)
                )
                AutoDetectToggle(enabled = autoDetectEnabled, onToggle = onToggleAutoDetect)
                UnitToggle(unit = unit, onToggle = viewModel::toggleUnit)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (activeTrip != null) "RECORDING" else "READY",
            style = MaterialTheme.typography.headlineLarge,
            color = if (activeTrip != null) AccentGreen else Color.White
        )

        if (activeTrip == null) {
            Spacer(Modifier.height(12.dp))
            VehicleSelector(
                vehicles = vehicles,
                selectedVehicleId = selectedVehicleId,
                onSelect = viewModel::selectVehicle
            )
        }

        Spacer(Modifier.height(24.dp))
        StartStopButton(
            isRecording = activeTrip != null,
            onStart = onStartTrip,
            onStop = onStopTrip
        )

        Spacer(Modifier.height(32.dp))
        Text(
            text = "TRIP HISTORY",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(8.dp))

        if (history.isEmpty()) {
            Text(
                text = "No trips recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history, key = { it.id }) { trip ->
                    if (trip.status == TripStatus.ACTIVE) {
                        // Can't delete the trip currently being recorded.
                        TripRow(trip, unit, onClick = { onTripClick(trip.id) })
                    } else {
                        DismissibleTripRow(
                            trip = trip,
                            unit = unit,
                            onClick = { onTripClick(trip.id) },
                            onSwipeToDelete = { pendingDelete = trip }
                        )
                    }
                }
            }
        }
    }

    val tripPendingDelete = pendingDelete
    if (tripPendingDelete != null) {
        DeleteTripDialog(
            onConfirm = {
                viewModel.deleteTrip(tripPendingDelete)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
fun DeleteTripDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this trip?") },
        text = { Text("This permanently deletes the trip and its recorded GPS points.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("DELETE", color = AccentRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissibleTripRow(
    trip: Trip,
    unit: DistanceUnit,
    onClick: () -> Unit,
    onSwipeToDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onSwipeToDelete()
            }
            false // never auto-commit the swipe; the confirmation dialog decides
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AccentRed, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text("DELETE", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    ) {
        TripRow(trip, unit, onClick)
    }
}

@Composable
private fun UnitToggle(unit: DistanceUnit, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .background(SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        DistanceUnit.entries.forEach { option ->
            val selected = option == unit
            Text(
                text = if (option == DistanceUnit.METRIC) "KM" else "MI",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) AccentGreen else TextSecondary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .then(
                        if (!selected) Modifier.clickable { onToggle() } else Modifier
                    )
            )
        }
    }
}

@Composable
private fun AutoDetectToggle(enabled: Boolean, onToggle: () -> Unit) {
    Text(
        text = if (enabled) "AUTO: ON" else "AUTO: OFF",
        style = MaterialTheme.typography.labelSmall,
        color = if (enabled) AccentGreen else TextSecondary,
        fontWeight = if (enabled) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(SurfaceRaised, RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun VehicleSelector(
    vehicles: List<VehicleProfile>,
    selectedVehicleId: Long,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = vehicles.find { it.id == selectedVehicleId }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceRaised, RoundedCornerShape(12.dp))
                .clickable(enabled = vehicles.size > 1) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VEHICLE: ${selected?.name ?: "None"}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            if (vehicles.size > 1) {
                Text("▾", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            vehicles.forEach { vehicle ->
                DropdownMenuItem(
                    text = { Text(vehicle.name) },
                    onClick = {
                        onSelect(vehicle.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun StartStopButton(isRecording: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = { if (isRecording) onStop() else onStart() },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRecording) AccentRed else AccentGreen,
            contentColor = Color.Black
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Text(
            text = if (isRecording) "STOP TRIP" else "START TRIP",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun TripRow(trip: Trip, unit: DistanceUnit, onClick: () -> Unit) {
    val dateFormat = remember(trip.id) { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val durationSeconds = ((trip.endTimeEpochMs ?: System.currentTimeMillis()) - trip.startTimeEpochMs) / 1000
    val distance = unit.metersToDistance(trip.distanceMeters)
    val maxSpeed = unit.mpsToSpeed(trip.maxSpeedMps)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = dateFormat.format(Date(trip.startTimeEpochMs)),
                style = MaterialTheme.typography.titleMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (trip.isAutoDetected) {
                    Text("AUTO", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
                if (trip.status.name == "ACTIVE") {
                    Text("● LIVE", color = AccentAmber, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatText(label = "DISTANCE", value = String.format(Locale.US, "%.1f %s", distance, unit.distanceLabel))
            StatText(label = "DURATION", value = formatDuration(durationSeconds))
            StatText(label = "MAX SPEED", value = String.format(Locale.US, "%.0f %s", maxSpeed, unit.speedLabel))
        }
    }
}
