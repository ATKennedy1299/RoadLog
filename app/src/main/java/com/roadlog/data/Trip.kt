package com.roadlog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TripStatus { ACTIVE, COMPLETED }

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleProfileId: Long,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long? = null,
    val status: TripStatus = TripStatus.ACTIVE,
    // Rolling aggregates, updated incrementally as points arrive so we never
    // have to re-scan all points to render the trip list.
    val distanceMeters: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val pointCount: Int = 0,
    // True if ride detection started this trip on its own rather than the
    // user pressing "Start Trip" — surfaced in the UI so a false-positive
    // (e.g. riding as a passenger) is easy to spot and delete.
    val isAutoDetected: Boolean = false,
    // Best-guess vehicle type from how the phone moved through turns while
    // recording (see RideMotionClassifier) — null if the classifier never
    // saw enough clear turns to venture a guess, or the device lacks the
    // needed sensor. Purely a suggestion surfaced in the UI, never written
    // back into vehicleProfileId automatically.
    val detectedVehicleType: VehicleType? = null,
    // Temporary diagnostic instrumentation for RideMotionClassifier, shown on
    // Trip Detail behind a "DEBUG" label — remove once its thresholds are
    // validated against real rides and it's trusted to run silently.
    val motionDebugInfo: String? = null,
    // True once a speed-limit lookup (see SpeedLimitLookup) has been
    // attempted for this trip's points, successful or not — distinguishes
    // "no roads matched" from "never tried," so TripDetailViewModel doesn't
    // re-query Overpass on every screen open. Only flips to true on a
    // successful Overpass response; left false on network failure so the
    // next open retries.
    val speedLimitsFetched: Boolean = false
)
