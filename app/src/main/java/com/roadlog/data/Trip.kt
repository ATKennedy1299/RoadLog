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
    val detectedVehicleType: VehicleType? = null
)
