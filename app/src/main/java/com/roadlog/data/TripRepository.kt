package com.roadlog.data

import com.roadlog.service.GpsFilter
import com.roadlog.util.DistanceUtils
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Single source of truth used by both the UI and the foreground service.
 * Neither talks to the other directly — both go through this class and Room,
 * which is what lets the UI recover cleanly after process death: it just
 * re-observes whatever the service already wrote.
 */
class TripRepository(private val db: AppDatabase) {

    companion object {
        /**
         * How long a trip can go without an accepted GPS fix before we treat
         * it as abandoned rather than genuinely in-progress. Long enough that
         * a real drive with a rough GPS gap (tunnel, parking garage, dead
         * zone) won't get cut off; short enough to catch a trip that was
         * never properly stopped (e.g. the app/service was killed and never
         * restarted) before a later, unrelated drive can resume it.
         */
        private val STALE_TRIP_THRESHOLD_MS = TimeUnit.HOURS.toMillis(4)
    }

    fun observeAllTrips(): Flow<List<Trip>> = db.tripDao().observeAllTrips()
    fun observeActiveTrip(): Flow<Trip?> = db.tripDao().observeActiveTrip()
    fun observeTrip(tripId: Long): Flow<Trip?> = db.tripDao().observeById(tripId)
    fun observePointsForTrip(tripId: Long): Flow<List<LocationPoint>> =
        db.locationPointDao().observePointsForTrip(tripId)

    suspend fun getActiveTrip(): Trip? = db.tripDao().getActiveTrip()
    suspend fun getVehicleProfile(vehicleProfileId: Long): VehicleProfile? =
        db.vehicleProfileDao().getById(vehicleProfileId)
    suspend fun getRoutePoints(tripId: Long): List<LocationPoint> =
        db.locationPointDao().getAcceptedPointsForTrip(tripId)

    /** Cascade-deletes the trip's location_points via Room's ForeignKey.CASCADE. */
    suspend fun deleteTrip(trip: Trip) = db.tripDao().delete(trip)

    suspend fun startTrip(
        vehicleProfileId: Long = DefaultVehicleProfile.ID,
        isAutoDetected: Boolean = false
    ): Trip {
        val trip = Trip(
            vehicleProfileId = vehicleProfileId,
            startTimeEpochMs = System.currentTimeMillis(),
            isAutoDetected = isAutoDetected
        )
        val id = db.tripDao().insert(trip)
        return trip.copy(id = id)
    }

    suspend fun stopTrip(tripId: Long) {
        db.tripDao().completeTrip(tripId, System.currentTimeMillis())
    }

    /**
     * Runs an incoming raw GPS fix through GpsFilter, persists it either way
     * (flagged if rejected), and if accepted, incrementally updates the
     * trip's rolling distance/max-speed/point-count aggregates.
     *
     * This is called once per fix from the service — never batched — so a
     * process crash loses at most the single in-flight fix.
     */
    suspend fun ingestFix(
        tripId: Long,
        timestampEpochMs: Long,
        latitude: Double,
        longitude: Double,
        gpsSpeedMps: Float?,
        accuracyMeters: Float,
        altitudeMeters: Double?,
        bearing: Float?
    ) {
        val lastAccepted = db.locationPointDao().getLastAcceptedPoint(tripId)
        val result = GpsFilter.evaluate(
            GpsFilter.RawFix(timestampEpochMs, latitude, longitude, accuracyMeters),
            lastAccepted
        )

        val point = LocationPoint(
            tripId = tripId,
            timestampEpochMs = timestampEpochMs,
            latitude = latitude,
            longitude = longitude,
            gpsSpeedMps = gpsSpeedMps,
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            bearing = bearing,
            isFiltered = !result.accepted
        )
        db.locationPointDao().insert(point)

        if (result.accepted) {
            val trip = db.tripDao().getById(tripId) ?: return
            val addedDistance = if (lastAccepted != null) {
                DistanceUtils.haversineMeters(
                    lastAccepted.latitude, lastAccepted.longitude, latitude, longitude
                )
            } else 0.0

            // Prefer the device's reported GPS speed when present; fall back
            // to the implied speed derived from the filter's distance/time
            // calculation so max speed still populates on devices/fixes that
            // don't report speed.
            val candidateSpeed = (gpsSpeedMps?.toDouble()) ?: result.impliedSpeedMps ?: 0.0

            db.tripDao().updateAggregates(
                tripId = tripId,
                distanceMeters = trip.distanceMeters + addedDistance,
                maxSpeedMps = maxOf(trip.maxSpeedMps, candidateSpeed),
                pointCount = trip.pointCount + 1
            )
        }
    }

    suspend fun ensureDefaultVehicleProfile() {
        db.vehicleProfileDao().insert(DefaultVehicleProfile.profile)
    }

    /**
     * Called on service (re)start instead of a plain getActiveTrip(): an
     * ACTIVE trip whose last accepted GPS fix is older than
     * STALE_TRIP_THRESHOLD_MS is treated as abandoned (e.g. the process was
     * killed and never cleanly stopped) rather than resumable, so a later,
     * unrelated drive doesn't get appended to it. The trip is finalized using
     * its own last-known-activity time (or its start time, if it never got a
     * single accepted fix) as the end time, not "now" — so its recorded
     * duration reflects real activity rather than the dead gap since. No GPS
     * data is discarded either way: a finalized trip's points remain exactly
     * as recorded.
     */
    suspend fun recoverActiveTrip(now: Long = System.currentTimeMillis()): Trip? {
        val trip = db.tripDao().getActiveTrip() ?: return null
        val lastActivityTimestamp =
            db.locationPointDao().getLastAcceptedPoint(trip.id)?.timestampEpochMs
                ?: trip.startTimeEpochMs
        val ageMs = now - lastActivityTimestamp
        if (ageMs > STALE_TRIP_THRESHOLD_MS) {
            db.tripDao().completeTrip(trip.id, lastActivityTimestamp)
            return null
        }
        return trip
    }
}
