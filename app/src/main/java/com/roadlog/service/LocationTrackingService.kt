package com.roadlog.service

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.roadlog.R
import com.roadlog.RoadLogApp
import com.roadlog.data.Trip
import com.roadlog.ui.MainActivity
import kotlinx.coroutines.*
import java.util.Locale

/**
 * Foreground service that owns the FusedLocationProviderClient for the
 * lifetime of a trip. Intentionally dumb: it does not decide trip state on
 * its own beyond what's in the DB. On (re)creation it checks for an ACTIVE
 * trip and resumes tracking against it — this is what makes the recording
 * survive process death, not just Activity destruction.
 */
class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START_TRIP = "com.roadlog.action.START_TRIP"
        const val ACTION_STOP_TRIP = "com.roadlog.action.STOP_TRIP"

        private const val NOTIFICATION_CHANNEL_ID = "roadlog_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 1000L

        private const val TAG = "LocationTrackingService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var activeTripId: Long? = null
    private var currentTripDistance = 0.0
    private var currentTripMaxSpeed = 0.0

    private val repository by lazy { (application as RoadLogApp).repository }
    private val unitsRepository by lazy { (application as RoadLogApp).unitsRepository }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val tripId = activeTripId ?: return
            val location = result.lastLocation ?: return
            serviceScope.launch {
                repository.ingestFix(
                    tripId = tripId,
                    timestampEpochMs = location.time,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    gpsSpeedMps = if (location.hasSpeed()) location.speed else null,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else 999f,
                    altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                    bearing = if (location.hasBearing()) location.bearing else null
                )
                // Refresh cached aggregates for the notification text.
                repository.getActiveTrip()?.let { trip ->
                    currentTripDistance = trip.distanceMeters
                    currentTripMaxSpeed = trip.maxSpeedMps
                    updateNotification(trip)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        // Recover from process death: if the DB says a trip is ACTIVE,
        // resume tracking against it immediately.
        serviceScope.launch {
            repository.getActiveTrip()?.let { trip ->
                activeTripId = trip.id
                currentTripDistance = trip.distanceMeters
                currentTripMaxSpeed = trip.maxSpeedMps
                withContext(Dispatchers.Main) {
                    startForeground(NOTIFICATION_ID, buildNotification(trip))
                    beginLocationUpdates()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRIP -> serviceScope.launch { onStartTripRequested() }
            ACTION_STOP_TRIP -> serviceScope.launch { onStopTripRequested() }
        }
        // START_STICKY: if the system kills the process to reclaim memory,
        // it restarts the service with a null Intent; onCreate's recovery
        // logic above then re-attaches to the still-ACTIVE trip in the DB.
        return START_STICKY
    }

    private suspend fun onStartTripRequested() {
        if (activeTripId != null) return // already tracking
        val trip = repository.startTrip()
        activeTripId = trip.id
        currentTripDistance = 0.0
        currentTripMaxSpeed = 0.0
        withContext(Dispatchers.Main) {
            startForeground(NOTIFICATION_ID, buildNotification(trip))
            beginLocationUpdates()
        }
    }

    private suspend fun onStopTripRequested() {
        val tripId = activeTripId ?: return
        repository.stopTrip(tripId)
        activeTripId = null
        withContext(Dispatchers.Main) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun beginLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Missing ACCESS_FINE_LOCATION at service start")
            return
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS
        )
            .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocationClient.requestLocationUpdates(
            request, locationCallback, mainLooper
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Trip Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ongoing RoadLog trip recording" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(trip: Trip): Notification = notificationBuilder(trip).build()

    private fun updateNotification(trip: Trip) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notificationBuilder(trip).build())
    }

    private fun notificationBuilder(trip: Trip): NotificationCompat.Builder {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val unit = unitsRepository.unit.value
        val distance = unit.metersToDistance(currentTripDistance)
        val maxSpeed = unit.mpsToSpeed(currentTripMaxSpeed)
        val text = String.format(
            Locale.US, "%.2f %s · max %.0f %s",
            distance, unit.distanceLabel, maxSpeed, unit.speedLabel
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RoadLog — Recording trip")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
