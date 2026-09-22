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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.CumulativeStats
import com.roadlog.data.DistanceUnit
import com.roadlog.data.VehicleType
import com.roadlog.ui.VehicleDetailViewModel
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun VehicleDetailScreen(
    viewModel: VehicleDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit
) {
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()

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
                text = "‹ BACK",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.clickable(onClick = onBack)
            )
            Text(
                text = "EDIT",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.clickable(onClick = onEdit)
            )
        }
        Spacer(Modifier.height(12.dp))

        val currentVehicle = vehicle
        if (currentVehicle == null) {
            Text("Vehicle not found.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } else {
            VehicleCoverPhoto(photoPath = currentVehicle.photoPath, colorHex = currentVehicle.colorHex)
            Spacer(Modifier.height(16.dp))
            Text(currentVehicle.name, style = MaterialTheme.typography.headlineLarge, color = Color.White)
            val subtitle = listOfNotNull(
                currentVehicle.year?.toString(), currentVehicle.make, currentVehicle.model
            ).joinToString(" ").ifBlank {
                if (currentVehicle.type == VehicleType.MOTORCYCLE) "Motorcycle" else "Car"
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Spacer(Modifier.height(24.dp))

            if (stats.totalTrips == 0) {
                Text(
                    text = "No completed trips logged on this vehicle yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { VehicleHeroCard(stats, unit) }
                    item { VehicleOverviewCard(stats, unit) }
                }
            }
        }
    }
}

@Composable
private fun VehicleHeroCard(stats: CumulativeStats, unit: DistanceUnit) {
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
