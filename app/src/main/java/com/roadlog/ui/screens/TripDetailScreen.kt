package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    onBack: () -> Unit
) {
    val trip by viewModel.trip.collectAsStateWithLifecycle()
    val vehicleProfile by viewModel.vehicleProfile.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

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
            TripStats(trip = currentTrip, vehicleProfile = vehicleProfile, unit = unit)

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
private fun TripStats(trip: Trip, vehicleProfile: VehicleProfile?, unit: DistanceUnit) {
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
            StatText(label = "VEHICLE", value = vehicleProfile?.name ?: "Unknown")
            StatText(label = "GPS POINTS", value = trip.pointCount.toString())
        }
    }
}
