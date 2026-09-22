package com.roadlog.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.VehicleProfile
import com.roadlog.data.VehicleType
import com.roadlog.ui.GarageViewModel
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary

@Composable
fun GarageScreen(
    viewModel: GarageViewModel,
    onBack: () -> Unit,
    onOpenVehicle: (Long) -> Unit,
    onAddVehicle: () -> Unit
) {
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()

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
            text = "GARAGE",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onAddVehicle,
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("+ ADD VEHICLE", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))

        if (vehicles.isEmpty()) {
            Text(
                text = "No vehicles yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vehicles, key = { it.id }) { vehicle ->
                    VehicleRow(vehicle = vehicle, onClick = { onOpenVehicle(vehicle.id) })
                }
            }
        }
    }
}

@Composable
private fun VehicleRow(vehicle: VehicleProfile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        VehicleThumbnail(photoPath = vehicle.photoPath, colorHex = vehicle.colorHex, size = 52.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(vehicle.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
            val subtitle = listOfNotNull(vehicle.year?.toString(), vehicle.make, vehicle.model)
                .joinToString(" ")
                .ifBlank { if (vehicle.type == VehicleType.MOTORCYCLE) "Motorcycle" else "Car" }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Text(
            text = if (vehicle.type == VehicleType.MOTORCYCLE) "MOTO" else "CAR",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

/** Shows the vehicle's photo if one's set, otherwise a solid circle in its color tag. */
@Composable
fun VehicleThumbnail(photoPath: String?, colorHex: String, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(photoPath) {
        photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape)
        )
    } else {
        val color = remember(colorHex) {
            runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(AccentGreen)
        }
        Box(modifier = modifier.size(size).clip(CircleShape).background(color))
    }
}
