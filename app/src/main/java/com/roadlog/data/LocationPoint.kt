package com.roadlog.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where this speed value came from. Only GPS_RAW exists in Phase 1 — this
 * enum is the seam for a future OBD_VERIFIED source without a schema
 * migration that changes meaning of existing rows.
 */
enum class SpeedSource { GPS_RAW }

@Entity(
    tableName = "location_points",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tripId")]
)
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampEpochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val gpsSpeedMps: Float?,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val bearing: Float?,
    val speedSource: SpeedSource = SpeedSource.GPS_RAW,
    // true if GpsFilter flagged this as an implausible jump. Kept (not
    // dropped) so replay/debugging can show what was excluded and why.
    val isFiltered: Boolean = false
)
