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
    val pointCount: Int = 0
)
