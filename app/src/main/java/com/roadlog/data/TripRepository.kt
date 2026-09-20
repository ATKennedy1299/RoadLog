package com.roadlog.data

import com.roadlog.service.GpsFilter
import com.roadlog.util.DistanceUtils
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth used by both the UI and the foreground service.
 * Neither talks to the other directly — both go through this class and Room,
 * which is what lets the UI recover cleanly after process death: it just
 * re-observes whatever the service already wrote.
 */
class TripRepository(private val db: AppDatabase) {

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

    suspend fun startTrip(vehicleProfileId: Long = DefaultVehicleProfile.ID): Trip {
        val trip = Trip(
            vehicleProfileId = vehicleProfileId,
            startTimeEpochMs = System.currentTimeMillis()
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
}
