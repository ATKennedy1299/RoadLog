package com.roadlog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.roadlog.RoadLogApp
import com.roadlog.service.LocationTrackingService
import com.roadlog.ui.screens.HomeScreen
import com.roadlog.ui.theme.RoadLogTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels {
        ViewModelFactory(
            (application as RoadLogApp).repository,
            (application as RoadLogApp).unitsRepository
        )
    }

    // Fine + coarse location must be granted before we ever try to start
    // the service. Background location is requested separately (Android
    // requires it as a follow-up request on API 30+).
    private val foregroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            maybeRequestBackgroundLocation()
        }
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless; background is a nice-to-have for this phase */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notification just won't show if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        setContent {
            RoadLogTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onStartTrip = ::onStartTripClicked,
                    onStopTrip = ::onStopTripClicked
                )
            }
        }
    }

    private fun onStartTripClicked() {
        if (hasFineLocationPermission()) {
            sendServiceCommand(LocationTrackingService.ACTION_START_TRIP)
        } else {
            foregroundLocationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun onStopTripClicked() {
        sendServiceCommand(LocationTrackingService.ACTION_STOP_TRIP)
    }

    private fun sendServiceCommand(action: String) {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            this.action = action
        }
        if (action == LocationTrackingService.ACTION_START_TRIP) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        // Fine location is granted at this point — allow the trip to start;
        // the user can grant "Allow all the time" from Settings for
        // uninterrupted background tracking.
        sendServiceCommand(LocationTrackingService.ACTION_START_TRIP)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
