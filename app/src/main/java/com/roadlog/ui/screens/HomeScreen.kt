package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.Trip
import com.roadlog.ui.HomeViewModel
import com.roadlog.ui.theme.AccentAmber
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.AccentRed
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit
) {
    val activeTrip by viewModel.activeTrip.collectAsStateWithLifecycle()
    val history by viewModel.tripHistory.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Text(
            text = "ROADLOG",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (activeTrip != null) "RECORDING" else "READY",
            style = MaterialTheme.typography.headlineLarge,
            color = if (activeTrip != null) AccentGreen else Color.White
        )

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
                    TripRow(trip)
                }
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
private fun TripRow(trip: Trip) {
    val dateFormat = remember(trip.id) { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val durationSeconds = ((trip.endTimeEpochMs ?: System.currentTimeMillis()) - trip.startTimeEpochMs) / 1000
    val distanceKm = trip.distanceMeters / 1000.0
    val maxSpeedKmh = trip.maxSpeedMps * 3.6

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(12.dp))
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
            if (trip.status.name == "ACTIVE") {
                Text("● LIVE", color = AccentAmber, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Stat(label = "DISTANCE", value = String.format(Locale.US, "%.1f km", distanceKm))
            Stat(label = "DURATION", value = formatDuration(durationSeconds))
            Stat(label = "MAX SPEED", value = String.format(Locale.US, "%.0f km/h", maxSpeedKmh))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = AccentGreen)
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
