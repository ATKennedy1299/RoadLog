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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roadlog.RoadLogApp
import com.roadlog.service.LocationTrackingService
import com.roadlog.service.RideDetection
import com.roadlog.ui.screens.HomeScreen
import com.roadlog.ui.screens.StatsScreen
import com.roadlog.ui.screens.TripDetailScreen
import com.roadlog.ui.screens.TripReplayScreen
import com.roadlog.ui.theme.RoadLogTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels {
        ViewModelFactory(
            (application as RoadLogApp).repository,
            (application as RoadLogApp).unitsRepository,
            (application as RoadLogApp).rideDetectionSettings
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

    private val activityRecognitionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableAutoDetect()
        // else: leave the setting off — the toggle just reflects that
        // RideDetectionSettings was never flipped to true.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        setContent {
            RoadLogTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            onStartTrip = ::onStartTripClicked,
                            onStopTrip = ::onStopTripClicked,
                            onTripClick = { tripId -> navController.navigate("trip/$tripId") },
                            onOpenStats = { navController.navigate("stats") },
                            onToggleAutoDetect = ::onToggleAutoDetectClicked
                        )
                    }
                    composable("stats") {
                        val app = application as RoadLogApp
                        val statsViewModel: StatsViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer { StatsViewModel(app.repository, app.unitsRepository) }
                            }
                        )
                        StatsScreen(
                            viewModel = statsViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenTrip = { tripId -> navController.navigate("trip/$tripId") }
                        )
                    }
                    composable(
                        route = "trip/{tripId}",
                        arguments = listOf(navArgument("tripId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
                        val app = application as RoadLogApp
                        val detailViewModel: TripDetailViewModel = viewModel(
                            key = "trip-detail-$tripId",
                            factory = viewModelFactory {
                                initializer { TripDetailViewModel(tripId, app.repository, app.unitsRepository) }
                            }
                        )
                        TripDetailScreen(
                            viewModel = detailViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenReplay = { navController.navigate("replay/$tripId") }
                        )
                    }
                    composable(
                        route = "replay/{tripId}",
                        arguments = listOf(navArgument("tripId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
                        val app = application as RoadLogApp
                        val replayViewModel: TripReplayViewModel = viewModel(
                            key = "trip-replay-$tripId",
                            factory = viewModelFactory {
                                initializer { TripReplayViewModel(tripId, app.repository, app.unitsRepository) }
                            }
                        )
                        TripReplayScreen(
                            viewModel = replayViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
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

    private fun onToggleAutoDetectClicked() {
        val app = application as RoadLogApp
        when {
            app.rideDetectionSettings.enabled.value -> {
                RideDetection.unregister(this)
                app.rideDetectionSettings.setEnabled(false)
            }
            RideDetection.hasPermission(this) -> enableAutoDetect()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            else -> enableAutoDetect() // pre-Q: granted at install time
        }
    }

    private fun enableAutoDetect() {
        val app = application as RoadLogApp
        RideDetection.register(this)
        app.rideDetectionSettings.setEnabled(true)
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
