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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roadlog.RoadLogApp
import com.roadlog.data.DefaultVehicleProfile
import com.roadlog.service.LocationTrackingService
import com.roadlog.service.RideDetection
import com.roadlog.ui.screens.BottomNavDestination
import com.roadlog.ui.screens.GarageScreen
import com.roadlog.ui.screens.HomeScreen
import com.roadlog.ui.screens.RoadLogBottomNav
import com.roadlog.ui.screens.StatsScreen
import com.roadlog.ui.screens.TripDetailScreen
import com.roadlog.ui.screens.TripReplayScreen
import com.roadlog.ui.screens.VehicleEditScreen
import com.roadlog.ui.screens.VehicleDetailScreen
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

    // Auto-detect's own permission chain: fine location (it's meaningless
    // without any location access) -> background location (required for a
    // background-started foreground service to actually get GPS fixes once
    // the app has no visible Activity — "while using the app" alone isn't
    // enough) -> activity recognition (the STILL-transition trigger).
    // Deliberately separate from foregroundLocationPermissionLauncher below,
    // which always ends by starting a manual trip — reusing it here would
    // start a trip just from toggling this setting on.
    private val autoDetectFineLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestBackgroundLocationForAutoDetect()
        }
        // else: leave the setting off.
    }

    private val autoDetectBackgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Proceed regardless of grant — without it, detection still works
        // while the app happens to be in the foreground, just not while
        // fully closed. Better than blocking the feature entirely.
        requestActivityRecognitionForAutoDetect()
    }

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
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                val showBottomNav = BottomNavDestination.entries.any { it.route == currentRoute }

                Scaffold(
                    bottomBar = {
                        if (showBottomNav) {
                            RoadLogBottomNav(currentRoute = currentRoute) { route ->
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            onStartTrip = ::onStartTripClicked,
                            onStopTrip = ::onStopTripClicked,
                            onTripClick = { tripId -> navController.navigate("trip/$tripId") },
                            onToggleAutoDetect = ::onToggleAutoDetectClicked
                        )
                    }
                    composable("garage") {
                        val app = application as RoadLogApp
                        val garageViewModel: GarageViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer { GarageViewModel(app.repository) }
                            }
                        )
                        GarageScreen(
                            viewModel = garageViewModel,
                            onOpenVehicle = { vehicleId -> navController.navigate("vehicle/$vehicleId") },
                            onAddVehicle = { navController.navigate("vehicle-edit/0") }
                        )
                    }
                    composable(
                        route = "vehicle/{vehicleId}",
                        arguments = listOf(navArgument("vehicleId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val vehicleId = backStackEntry.arguments?.getLong("vehicleId") ?: return@composable
                        val app = application as RoadLogApp
                        val vehicleDetailViewModel: VehicleDetailViewModel = viewModel(
                            key = "vehicle-detail-$vehicleId",
                            factory = viewModelFactory {
                                initializer { VehicleDetailViewModel(vehicleId, app.repository, app.unitsRepository) }
                            }
                        )
                        VehicleDetailScreen(
                            viewModel = vehicleDetailViewModel,
                            onBack = { navController.popBackStack() },
                            onEdit = { navController.navigate("vehicle-edit/$vehicleId") }
                        )
                    }
                    composable(
                        route = "vehicle-edit/{vehicleId}",
                        arguments = listOf(navArgument("vehicleId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val vehicleId = backStackEntry.arguments?.getLong("vehicleId") ?: 0L
                        val app = application as RoadLogApp
                        val editViewModel: VehicleEditViewModel = viewModel(
                            key = "vehicle-edit-$vehicleId",
                            factory = viewModelFactory {
                                initializer {
                                    VehicleEditViewModel(vehicleId, app.repository, app.vehiclePhotoStore)
                                }
                            }
                        )
                        VehicleEditScreen(
                            viewModel = editViewModel,
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() },
                            onDeleted = {
                                navController.popBackStack("garage", inclusive = false)
                            }
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
    }

    // Captured at the moment "Start Trip" is tapped so it survives the
    // async permission-request chain below and still reaches the service
    // intent whichever branch (immediate start vs. permission grant
    // callback) actually sends it.
    private var pendingStartVehicleProfileId: Long = DefaultVehicleProfile.ID

    private fun onStartTripClicked() {
        pendingStartVehicleProfileId = viewModel.selectedVehicleId.value
        if (hasFineLocationPermission()) {
            sendStartTripCommand()
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

    private fun sendStartTripCommand() {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START_TRIP
            putExtra(LocationTrackingService.EXTRA_VEHICLE_PROFILE_ID, pendingStartVehicleProfileId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendServiceCommand(action: String) {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun onToggleAutoDetectClicked() {
        val app = application as RoadLogApp
        if (app.rideDetectionSettings.enabled.value) {
            RideDetection.unregister(this)
            app.rideDetectionSettings.setEnabled(false)
            return
        }
        if (!hasFineLocationPermission()) {
            autoDetectFineLocationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        } else {
            requestBackgroundLocationForAutoDetect()
        }
    }

    private fun requestBackgroundLocationForAutoDetect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            autoDetectBackgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            requestActivityRecognitionForAutoDetect()
        }
    }

    private fun requestActivityRecognitionForAutoDetect() {
        when {
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
        sendStartTripCommand()
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
