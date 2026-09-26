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
    val isFiltered: Boolean = false,
    // true if GpsFilter saw too long a gap since the previous accepted fix
    // to trust a straight line between them as the real path (a tunnel, a
    // dead zone, the phone being off) — the route map and replay both treat
    // this point as the start of a new, disconnected segment rather than
    // drawing/animating a fake straight-line "trip" across the gap.
    val startsNewSegment: Boolean = false,
    // Posted speed limit at this point, in m/s, from OpenStreetMap's
    // maxspeed tag on the nearest road (see SpeedLimitLookup) — null until
    // fetched, and stays null if no tagged road was found nearby. Feeds
    // TripEventAnalyzer's speeding-event detection; nothing else assumes
    // it's populated.
    val speedLimitMps: Float? = null
)
