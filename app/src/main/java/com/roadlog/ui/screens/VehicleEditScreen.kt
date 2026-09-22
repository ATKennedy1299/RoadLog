package com.roadlog.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.VehicleType
import com.roadlog.ui.VehicleEditViewModel
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.AccentRed
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary

private val VEHICLE_COLORS = listOf("#00E5A0", "#FFB020", "#FF4D4D", "#4D9DFF", "#B24DFF", "#FFFFFF")

@Composable
fun VehicleEditScreen(
    viewModel: VehicleEditViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val vehicleCount by viewModel.vehicleCount.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::onPhotoPicked) }

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
            text = if (viewModel.isNew) "ADD VEHICLE" else "EDIT VEHICLE",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )
        Spacer(Modifier.height(20.dp))

        VehicleThumbnail(
            photoPath = form.photoPath,
            colorHex = form.colorHex,
            size = 96.dp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        TextButton(
            onClick = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (form.photoPath == null) "ADD PHOTO" else "CHANGE PHOTO", color = AccentGreen)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.name,
            onValueChange = viewModel::updateName,
            label = { Text("Nickname") },
            singleLine = true,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.year,
                onValueChange = viewModel::updateYear,
                label = { Text("Year") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors(),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = form.make,
                onValueChange = viewModel::updateMake,
                label = { Text("Make") },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.model,
            onValueChange = viewModel::updateModel,
            label = { Text("Model") },
            singleLine = true,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Text("TYPE", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VehicleType.entries.forEach { type ->
                val selected = type == form.type
                Text(
                    text = if (type == VehicleType.CAR) "CAR" else "MOTORCYCLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Color.Black else TextSecondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (selected) AccentGreen else SurfaceRaised, RoundedCornerShape(8.dp))
                        .clickable { viewModel.updateType(type) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("COLOR TAG", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VEHICLE_COLORS.forEach { hex ->
                val selected = hex.equals(form.colorHex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(hex)))
                        .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                        .clickable { viewModel.updateColor(hex) }
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { viewModel.save(onSaved) },
            enabled = form.name.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("SAVE", fontWeight = FontWeight.Bold)
        }

        if (!viewModel.isNew && vehicleCount > 1) {
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("DELETE VEHICLE", color = AccentRed)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this vehicle?") },
            text = { Text("Trips already logged against it are kept, just no longer linked to a vehicle.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onDeleted)
                }) { Text("DELETE", color = AccentRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = AccentGreen,
    unfocusedBorderColor = TextSecondary,
    focusedLabelColor = AccentGreen,
    unfocusedLabelColor = TextSecondary,
    cursorColor = AccentGreen
)
