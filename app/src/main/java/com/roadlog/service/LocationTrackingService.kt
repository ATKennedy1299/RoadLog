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
import com.roadlog.data.DefaultVehicleProfile
import com.roadlog.data.Trip
import com.roadlog.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns the FusedLocationProviderClient for the
 * lifetime of a trip. Intentionally dumb: it does not decide trip state on
 * its own beyond what's in the DB. On (re)creation it checks for an ACTIVE
 * trip and resumes tracking against it — this is what makes the recording
 * survive process death, not just Activity destruction. If that ACTIVE trip
 * hasn't seen a GPS fix in a long time, it's treated as abandoned and
 * finalized instead of resumed (see TripRepository.recoverActiveTrip).
 *
 * Also handles automatic ride detection: RideDetectionReceiver forwards
 * Activity Recognition's STILL enter/exit transitions here as
 * ACTION_BEGIN_RIDE_CANDIDATE / ACTION_MOTION_STOPPED. Those transitions are
 * only ever used as a low-power trigger for *when* to check — the actual
 * decision to start or stop a trip is always driven by real GPS speed
 * (a "candidate" confirmation window), never by which activity type Play
 * Services reports, since that classifier isn't reliable across vehicle
 * types (car vs. motorcycle vs. bicycle).
 */
class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START_TRIP = "com.roadlog.action.START_TRIP"
        const val ACTION_STOP_TRIP = "com.roadlog.action.STOP_TRIP"
        const val EXTRA_VEHICLE_PROFILE_ID = "com.roadlog.extra.VEHICLE_PROFILE_ID"
        const val ACTION_BEGIN_RIDE_CANDIDATE = "com.roadlog.action.BEGIN_RIDE_CANDIDATE"
        const val ACTION_MOTION_STOPPED = "com.roadlog.action.MOTION_STOPPED"

        private const val NOTIFICATION_CHANNEL_ID = "roadlog_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 1000L

        // Candidate-checking runs at lower frequency/power than an actual
        // trip, since most STILL-exit events won't turn into a real ride.
        private const val CANDIDATE_LOCATION_INTERVAL_MS = 5000L
        private const val CANDIDATE_MIN_UPDATE_INTERVAL_MS = 3000L
        // ~12 mph — clearly above walking/idling speed, comfortably below
        // any real drive, and vehicle-type-agnostic on purpose.
        private const val CANDIDATE_SPEED_THRESHOLD_MPS = 5.5f
        // Speed must stay above the threshold continuously for this long
        // before promoting to a real trip — avoids a brief coast or a firm
        // parking-lot roll starting a trip by itself.
        private val CANDIDATE_CONFIRM_WINDOW_MS = TimeUnit.SECONDS.toMillis(45)
        // Give up checking (and tear the foreground service back down) if
        // speed never confirms within this long, so e.g. just walking
        // around after a STILL-exit doesn't pin a permanent notification.
        private val CANDIDATE_MAX_WAIT_MS = TimeUnit.MINUTES.toMillis(3)
        // How long to wait after motion stops before finalizing an
        // auto-detected trip, so a red light or a quick stop for gas
        // doesn't end it. Manual trips are never subject to this — only the
        // user's own Stop button ends those.
        private val AUTO_STOP_GRACE_MS = TimeUnit.MINUTES.toMillis(5)

        private const val TAG = "LocationTrackingService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    @Volatile private var activeTripId: Long? = null
    @Volatile private var currentTripDistance = 0.0
    @Volatile private var currentTripMaxSpeed = 0.0
    @Volatile private var isCurrentTripAutoDetected = false

    // Guards ingestFix() calls plus all ride-candidate state below, so a
    // batch of fixes is always processed in order and candidate state is
    // never touched by two overlapping onLocationResult() coroutines at once.
    private val ingestionMutex = Mutex()
    private var candidateActive = false
    private var candidateFirstQualifyingFixMs: Long? = null
    private val candidateBuffer = mutableListOf<Location>()
    private var candidateTimeoutJob: Job? = null

    // Guards activeTripId and the ACTIVE-trip row against concurrent
    // start/stop/recovery/promotion/auto-stop, all of which can fire close
    // together (a START_STICKY restart racing a user tap, or a
    // ride-detection broadcast racing either).
    private val tripStateMutex = Mutex()
    private var stopGraceJob: Job? = null

    private val repository by lazy { (application as RoadLogApp).repository }
    private val unitsRepository by lazy { (application as RoadLogApp).unitsRepository }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (result.locations.isEmpty()) return
            val tripId = activeTripId
            serviceScope.launch {
                ingestionMutex.withLock {
                    if (tripId != null) {
                        ingestBatchLocked(tripId, result.locations)
                    } else if (candidateActive) {
                        evaluateCandidateLocked(result.locations)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        // Recover from process death: if the DB says a trip is ACTIVE and it
        // isn't stale (see recoverActiveTrip), resume tracking against it
        // immediately. A stale ACTIVE trip is finalized instead, so it can't
        // absorb points from whatever new drive triggers the next start.
        serviceScope.launch {
            tripStateMutex.withLock {
                repository.recoverActiveTrip()?.let { trip ->
                    activeTripId = trip.id
                    isCurrentTripAutoDetected = trip.isAutoDetected
                    currentTripDistance = trip.distanceMeters
                    currentTripMaxSpeed = trip.maxSpeedMps
                    withContext(Dispatchers.Main) {
                        startForeground(NOTIFICATION_ID, buildNotification(trip))
                        beginLocationUpdates(highAccuracy = true)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val startsFreshForegroundState = activeTripId == null &&
            (action == ACTION_START_TRIP || action == ACTION_BEGIN_RIDE_CANDIDATE || action == ACTION_MOTION_STOPPED)
        if (startsFreshForegroundState) {
            // These three actions are always dispatched via
            // ContextCompat.startForegroundService(), which requires a
            // freshly created service instance to call startForeground()
            // promptly or the OS kills the process. For
            // ACTION_BEGIN_RIDE_CANDIDATE this "checking" notification IS
            // the right content for that state, not just a placeholder;
            // ACTION_START_TRIP replaces it moments later with the real
            // recording notification; ACTION_MOTION_STOPPED (nothing to do)
            // tears it back down below. Skipped when a trip is already
            // being recorded so it can't clobber that trip's notification.
            startForeground(NOTIFICATION_ID, checkingNotificationBuilder().build())
        }
        when (action) {
            ACTION_START_TRIP -> {
                val vehicleProfileId = intent?.getLongExtra(EXTRA_VEHICLE_PROFILE_ID, DefaultVehicleProfile.ID)
                    ?: DefaultVehicleProfile.ID
                serviceScope.launch { onStartTripRequested(vehicleProfileId) }
            }
            ACTION_STOP_TRIP -> serviceScope.launch { onStopTripRequested() }
            ACTION_BEGIN_RIDE_CANDIDATE -> serviceScope.launch { onBeginRideCandidateRequested() }
            ACTION_MOTION_STOPPED -> serviceScope.launch { onMotionStoppedRequested() }
        }
        // START_STICKY: if the system kills the process to reclaim memory,
        // it restarts the service with a null Intent; onCreate's recovery
        // logic above then re-attaches to the still-ACTIVE trip in the DB.
        return START_STICKY
    }

    private suspend fun onStartTripRequested(
        vehicleProfileId: Long = DefaultVehicleProfile.ID
    ) = ingestionMutex.withLock {
        // A manual start always wins over any in-progress ride-detection
        // check. Without this, a stale candidate (with its 3-minute
        // timeout job still scheduled) would later fire mid-recording and
        // call stopSelf(), silently killing this trip. Acquiring
        // ingestionMutex before tripStateMutex here matches the ordering
        // used everywhere candidate state and trip state are both touched,
        // so this can't deadlock against promoteCandidateLocked().
        candidateActive = false
        candidateFirstQualifyingFixMs = null
        candidateBuffer.clear()
        candidateTimeoutJob?.cancel()
        candidateTimeoutJob = null

        tripStateMutex.withLock {
            if (activeTripId != null) return@withLock // already tracking
            val trip = repository.startTrip(vehicleProfileId = vehicleProfileId)
            activeTripId = trip.id
            isCurrentTripAutoDetected = false
            currentTripDistance = 0.0
            currentTripMaxSpeed = 0.0
            withContext(Dispatchers.Main) {
                startForeground(NOTIFICATION_ID, buildNotification(trip))
                beginLocationUpdates(highAccuracy = true)
            }
        }
    }

    private suspend fun onStopTripRequested() = tripStateMutex.withLock {
        if (activeTripId == null) return@withLock
        stopGraceJob?.cancel()
        stopGraceJob = null
        finalizeTrip()
    }

    /** Must be called while holding tripStateMutex. */
    private suspend fun finalizeTrip() {
        val tripId = activeTripId ?: return
        repository.stopTrip(tripId)
        activeTripId = null
        isCurrentTripAutoDetected = false
        withContext(Dispatchers.Main) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun onBeginRideCandidateRequested() {
        if (activeTripId != null) {
            // Already recording — motion resuming just means don't auto-stop.
            tripStateMutex.withLock {
                stopGraceJob?.cancel()
                stopGraceJob = null
            }
            return
        }
        ingestionMutex.withLock {
            if (candidateActive) return@withLock // already checking
            candidateActive = true
            candidateFirstQualifyingFixMs = null
            candidateBuffer.clear()
            candidateTimeoutJob?.cancel()
            candidateTimeoutJob = serviceScope.launch {
                delay(CANDIDATE_MAX_WAIT_MS)
                ingestionMutex.withLock {
                    if (candidateActive) stopCandidateDetection()
                }
            }
            withContext(Dispatchers.Main) {
                beginLocationUpdates(highAccuracy = false)
            }
        }
    }

    private suspend fun onMotionStoppedRequested() {
        val wasCandidateActive = ingestionMutex.withLock {
            if (candidateActive) {
                stopCandidateDetection()
                true
            } else {
                false
            }
        }
        if (wasCandidateActive) return

        if (activeTripId != null && isCurrentTripAutoDetected) {
            tripStateMutex.withLock {
                stopGraceJob?.cancel()
                stopGraceJob = serviceScope.launch {
                    delay(AUTO_STOP_GRACE_MS)
                    tripStateMutex.withLock {
                        // Re-check: motion may have resumed and cancelled
                        // this job right as it was about to run, or the
                        // trip may have already ended some other way.
                        if (activeTripId != null && isCurrentTripAutoDetected) {
                            stopGraceJob = null
                            finalizeTrip()
                        }
                    }
                }
            }
            return
        }

        if (activeTripId == null) {
            // Nothing to do — no candidate check was running, and no
            // auto-detected trip is active (a manual trip, if any, is left
            // untouched above). onStartCommand had to call startForeground()
            // before we got here, so tear it back down rather than leaving
            // an orphaned "checking" notification with nothing behind it.
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Must be called while holding ingestionMutex. */
    private suspend fun ingestBatchLocked(tripId: Long, locations: List<Location>) {
        ingestLocations(tripId, locations)
        repository.getActiveTrip()?.let { trip ->
            currentTripDistance = trip.distanceMeters
            currentTripMaxSpeed = trip.maxSpeedMps
            updateNotification(trip)
        }
        // Real GPS speed proves motion is ongoing regardless of whether
        // Activity Recognition's STILL-exit fires again — cancel any
        // pending auto-stop so a missed/delayed transition can't end a
        // trip mid-ride.
        if (isCurrentTripAutoDetected &&
            locations.any { it.hasSpeed() && it.speed >= CANDIDATE_SPEED_THRESHOLD_MPS }
        ) {
            tripStateMutex.withLock {
                stopGraceJob?.cancel()
                stopGraceJob = null
            }
        }
    }

    /** Must be called while holding ingestionMutex. */
    private suspend fun evaluateCandidateLocked(locations: List<Location>) {
        for (location in locations) {
            candidateBuffer += location
            val qualifies = location.hasSpeed() && location.speed >= CANDIDATE_SPEED_THRESHOLD_MPS
            if (!qualifies) {
                candidateFirstQualifyingFixMs = null
                continue
            }
            val windowStart = candidateFirstQualifyingFixMs
                ?: location.time.also { candidateFirstQualifyingFixMs = it }
            if (location.time - windowStart >= CANDIDATE_CONFIRM_WINDOW_MS) {
                promoteCandidateLocked()
                return
            }
        }
    }

    /** Must be called while holding ingestionMutex. */
    private suspend fun promoteCandidateLocked() {
        val bufferedLocations = candidateBuffer.toList()
        candidateActive = false
        candidateFirstQualifyingFixMs = null
        candidateBuffer.clear()
        candidateTimeoutJob?.cancel()
        candidateTimeoutJob = null

        val tripId = tripStateMutex.withLock {
            val trip = repository.startTrip(isAutoDetected = true)
            activeTripId = trip.id
            isCurrentTripAutoDetected = true
            currentTripDistance = 0.0
            currentTripMaxSpeed = 0.0
            trip.id
        }

        // Back-fill the fixes gathered while still just "checking," so the
        // recorded route covers the ride's actual start rather than only
        // the moment confirmation crossed the speed threshold.
        ingestLocations(tripId, bufferedLocations)
        repository.getActiveTrip()?.let { trip ->
            currentTripDistance = trip.distanceMeters
            currentTripMaxSpeed = trip.maxSpeedMps
            updateNotification(trip)
        }

        withContext(Dispatchers.Main) {
            beginLocationUpdates(highAccuracy = true)
        }
    }

    /** Must be called while holding ingestionMutex. */
    private suspend fun stopCandidateDetection() {
        candidateActive = false
        candidateFirstQualifyingFixMs = null
        candidateBuffer.clear()
        candidateTimeoutJob?.cancel()
        candidateTimeoutJob = null
        withContext(Dispatchers.Main) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun ingestLocations(tripId: Long, locations: List<Location>) {
        for (location in locations) {
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
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun beginLocationUpdates(highAccuracy: Boolean) {
        if (!hasFineLocationPermission()) {
            Log.w(TAG, "Missing ACCESS_FINE_LOCATION at service start")
            return
        }
        val request = if (highAccuracy) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CANDIDATE_LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(CANDIDATE_MIN_UPDATE_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build()
        }
        // Re-registering with the same callback replaces the previous
        // request rather than stacking a second one — this is how
        // promoting from candidate-mode to full tracking swaps the request
        // without an explicit remove first.
        fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
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
        val unit = unitsRepository.unit.value
        val distance = unit.metersToDistance(currentTripDistance)
        val maxSpeed = unit.mpsToSpeed(currentTripMaxSpeed)
        val text = String.format(
            Locale.US, "%.2f %s · max %.0f %s",
            distance, unit.distanceLabel, maxSpeed, unit.speedLabel
        )
        return baseNotificationBuilder()
            .setContentTitle("RoadLog — Recording trip")
            .setContentText(text)
    }

    private fun checkingNotificationBuilder(): NotificationCompat.Builder =
        baseNotificationBuilder()
            .setContentTitle("RoadLog — Checking for a ride")
            .setContentText("Watching your speed before starting a trip")

    private fun baseNotificationBuilder(): NotificationCompat.Builder {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
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
