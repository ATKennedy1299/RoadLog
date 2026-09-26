package com.roadlog.util

import com.roadlog.data.LocationPoint
import kotlin.math.abs

enum class TripEventType { HARD_ACCEL, HARD_BRAKE, SPEEDING }

data class TripEvent(
    val type: TripEventType,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMs: Long
)

/**
 * Derives hard-acceleration/braking and speeding events from a trip's
 * already-recorded points, purely by re-scanning them — nothing here is
 * persisted, so results always reflect the current thresholds rather than
 * whatever was in effect when the trip was recorded, and re-tuning a
 * threshold takes effect on every trip immediately, past and future.
 */
object TripEventAnalyzer {

    // ~0.3g — noticeably aggressive, well above ordinary traffic-flow
    // speeding up, comfortably below what GPS speed noise alone could imply.
    // Starting point only, like RideMotionClassifier's thresholds — expect
    // to tune from real rides.
    private const val HARD_ACCEL_THRESHOLD_MPS2 = 3.0f

    // Braking a little harder than accelerating already reads as "hard" to
    // most drivers/passengers, so this is set a bit more aggressively.
    private const val HARD_BRAKE_THRESHOLD_MPS2 = -3.5f

    // Once inside a hard-accel/brake streak, only re-arm for a new event
    // after acceleration settles back within this band — stops one long
    // hard stop (many consecutive fixes over threshold) from registering as
    // several consecutive dots instead of one.
    private const val REARM_THRESHOLD_MPS2 = 1.5f

    // How far over the posted limit counts as "speeding" rather than
    // ordinary GPS noise or slight speed-limit/road-matching slop.
    private const val SPEEDING_MARGIN_MPS = 2.24f // 5 mph in m/s

    // Most non-major roads (rural backroads, minor residential streets)
    // simply have no maxspeed tag in OSM at all — common statutory default
    // for an unposted road in most US states. Used only to decide whether a
    // stretch counts as speeding; never shown as an actual posted limit
    // (see ReplayFrame.speedLimitMps / SpeedLimitSign, which only ever
    // display a real matched tag) — this is an assumption for detection,
    // not a claim that a sign exists there.
    private const val DEFAULT_UNTAGGED_SPEED_LIMIT_MPS = 24.59f // 55 mph

    /**
     * [points] must already be accepted (non-filtered) and ordered by time —
     * exactly what TripRepository.getRoutePoints returns. A gap point
     * ([LocationPoint.startsNewSegment]) resets all in-progress detection
     * rather than computing an "acceleration" across a tunnel/phone-off gap,
     * the same way RouteMapView breaks the drawn line there.
     */
    fun detectEvents(points: List<LocationPoint>): List<TripEvent> {
        if (points.size < 2) return emptyList()

        val events = mutableListOf<TripEvent>()
        var armed = true
        var wasSpeeding = false

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            if (curr.startsNewSegment) {
                armed = true
                wasSpeeding = false
                continue
            }

            val prevSpeed = prev.gpsSpeedMps?.toDouble()
            val currSpeed = curr.gpsSpeedMps?.toDouble()
            val dtSeconds = (curr.timestampEpochMs - prev.timestampEpochMs) / 1000.0

            if (prevSpeed != null && currSpeed != null && dtSeconds > 0.2) {
                val accelMps2 = ((currSpeed - prevSpeed) / dtSeconds).toFloat()
                when {
                    armed && accelMps2 >= HARD_ACCEL_THRESHOLD_MPS2 -> {
                        events += TripEvent(TripEventType.HARD_ACCEL, curr.latitude, curr.longitude, curr.timestampEpochMs)
                        armed = false
                    }
                    armed && accelMps2 <= HARD_BRAKE_THRESHOLD_MPS2 -> {
                        events += TripEvent(TripEventType.HARD_BRAKE, curr.latitude, curr.longitude, curr.timestampEpochMs)
                        armed = false
                    }
                    abs(accelMps2) < REARM_THRESHOLD_MPS2 -> armed = true
                }
            }

            val effectiveLimitMps = curr.speedLimitMps ?: DEFAULT_UNTAGGED_SPEED_LIMIT_MPS
            val isSpeeding = currSpeed != null && currSpeed > effectiveLimitMps + SPEEDING_MARGIN_MPS
            if (isSpeeding && !wasSpeeding) {
                events += TripEvent(TripEventType.SPEEDING, curr.latitude, curr.longitude, curr.timestampEpochMs)
            }
            wasSpeeding = isSpeeding
        }

        return events
    }
}
