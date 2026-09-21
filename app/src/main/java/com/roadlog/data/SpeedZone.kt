package com.roadlog.data

/**
 * Speed-based route-coloring zone. Thresholds are fixed (in mph) for now
 * and centralized in this companion object — a future Settings screen can
 * back these with user-configurable values without touching anything that
 * consumes [SpeedZone] (map rendering just asks "what zone is this speed
 * in" and never sees the raw threshold numbers).
 */
enum class SpeedZone {
    NORMAL,
    YELLOW,
    ORANGE,
    RED;

    companion object {
        private const val MPS_TO_MPH = 2.236936
        private const val YELLOW_MIN_MPH = 70.0
        private const val ORANGE_MIN_MPH = 90.0
        private const val RED_MIN_MPH = 100.0

        fun fromMph(speedMph: Double): SpeedZone = when {
            speedMph >= RED_MIN_MPH -> RED
            speedMph >= ORANGE_MIN_MPH -> ORANGE
            speedMph >= YELLOW_MIN_MPH -> YELLOW
            else -> NORMAL
        }

        /** Never throws: missing, NaN, or negative readings fall back to [NORMAL]. */
        fun fromMps(speedMps: Float?): SpeedZone {
            if (speedMps == null || speedMps.isNaN() || speedMps < 0f) return NORMAL
            return fromMph(speedMps * MPS_TO_MPH)
        }
    }
}
