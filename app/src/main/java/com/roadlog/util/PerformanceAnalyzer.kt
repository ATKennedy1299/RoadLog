package com.roadlog.util

import com.roadlog.data.LocationPoint

/**
 * A trip's best (quickest) acceleration/elapsed-time runs, in the same
 * sense a car review or drag strip reports them — 0-60 from a standing
 * start, 60-100/60-130/100-130 as rolling times (already moving at the
 * lower speed, uninterrupted to the higher one), and 1/8 & 1/4 mile as
 * standing-start elapsed time plus trap speed. Any field is null if that
 * trip never achieved it — e.g. a trip that topped out at 80 mph has no
 * 100-130 time, and a trip that never reached a genuine stop has no 0-60
 * or mile times at all.
 */
data class TripPerformanceStats(
    val zeroToSixtyMs: Long? = null,
    val sixtyToHundredMs: Long? = null,
    val sixtyToOneThirtyMs: Long? = null,
    val hundredToOneThirtyMs: Long? = null,
    val eighthMileMs: Long? = null,
    val eighthMileTrapSpeedMps: Double? = null,
    val quarterMileMs: Long? = null,
    val quarterMileTrapSpeedMps: Double? = null
)

/**
 * Derives [TripPerformanceStats] purely by re-scanning a trip's own
 * points — nothing here is persisted, so it reflects the current
 * definitions immediately for every trip, past and future, the same way
 * TripEventAnalyzer works.
 *
 * Measurement caveat: a trip records a GPS fix roughly every 1-2 seconds,
 * so a run lasting only a few seconds crosses very few fixes. Every
 * threshold crossing here is linearly interpolated between the two fixes
 * that straddle it (both in time and, for the mile splits, in distance)
 * to reduce that error, but these are still best-effort personal bests,
 * not lab-grade timing.
 */
object PerformanceAnalyzer {

    // What counts as "stopped" for a standing-start reference — high enough
    // to tolerate GPS speed jitter at a genuine stop, low enough that it's
    // never mistaken for a rolling crawl.
    private const val LAUNCH_THRESHOLD_MPS = 1.5 // ~3.4 mph

    private const val MPH_60_MPS = 26.8224
    private const val MPH_100_MPS = 44.704
    private const val MPH_130_MPS = 58.1152

    private const val EIGHTH_MILE_METERS = 201.168
    private const val QUARTER_MILE_METERS = 402.336

    fun computeBestRunTimes(points: List<LocationPoint>): TripPerformanceStats {
        if (points.size < 2) return TripPerformanceStats()

        var lastStopTimeMs: Long? = null
        var distanceSinceStopMeters = 0.0
        var zeroToSixtyRecordedThisStop = false
        var eighthMileRecordedThisStop = false
        var quarterMileRecordedThisStop = false

        // Sticky references for rolling (not-from-a-stop) splits: the
        // interpolated moment speed most recently crossed upward through
        // 60/100 mph, cleared the instant speed drops back below that mark
        // so a later crossing can't pair with a stale reference from an
        // unrelated, earlier pull.
        var sixtyCrossMs: Long? = null
        var hundredCrossMs: Long? = null

        var bestZeroToSixtyMs: Long? = null
        var bestSixtyToHundredMs: Long? = null
        var bestSixtyToOneThirtyMs: Long? = null
        var bestHundredToOneThirtyMs: Long? = null
        var bestEighthMileMs: Long? = null
        var bestEighthMileTrapSpeedMps: Double? = null
        var bestQuarterMileMs: Long? = null
        var bestQuarterMileTrapSpeedMps: Double? = null

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            if (curr.startsNewSegment) {
                // No real path/time data across a GPS gap — nothing here
                // (a stop reference, an in-progress rolling pull) survives it.
                lastStopTimeMs = null
                distanceSinceStopMeters = 0.0
                sixtyCrossMs = null
                hundredCrossMs = null
                continue
            }

            val prevSpeed = prev.gpsSpeedMps?.toDouble() ?: continue
            val currSpeed = curr.gpsSpeedMps?.toDouble() ?: continue
            val dtMs = curr.timestampEpochMs - prev.timestampEpochMs
            if (dtMs <= 0) continue

            if (currSpeed <= LAUNCH_THRESHOLD_MPS) {
                lastStopTimeMs = curr.timestampEpochMs
                distanceSinceStopMeters = 0.0
                zeroToSixtyRecordedThisStop = false
                eighthMileRecordedThisStop = false
                quarterMileRecordedThisStop = false
            } else if (lastStopTimeMs != null) {
                val stopTimeMs = lastStopTimeMs
                val segmentMeters = DistanceUtils.haversineMeters(
                    prev.latitude, prev.longitude, curr.latitude, curr.longitude
                )
                val distanceBeforeSegment = distanceSinceStopMeters
                distanceSinceStopMeters += segmentMeters

                if (!eighthMileRecordedThisStop && distanceSinceStopMeters >= EIGHTH_MILE_METERS && segmentMeters > 0) {
                    val fraction = ((EIGHTH_MILE_METERS - distanceBeforeSegment) / segmentMeters).coerceIn(0.0, 1.0)
                    val crossMs = prev.timestampEpochMs + (fraction * dtMs).toLong()
                    val elapsedMs = crossMs - stopTimeMs
                    val trapSpeed = prevSpeed + fraction * (currSpeed - prevSpeed)
                    if (bestEighthMileMs == null || elapsedMs < bestEighthMileMs) {
                        bestEighthMileMs = elapsedMs
                        bestEighthMileTrapSpeedMps = trapSpeed
                    }
                    eighthMileRecordedThisStop = true
                }
                if (!quarterMileRecordedThisStop && distanceSinceStopMeters >= QUARTER_MILE_METERS && segmentMeters > 0) {
                    val fraction = ((QUARTER_MILE_METERS - distanceBeforeSegment) / segmentMeters).coerceIn(0.0, 1.0)
                    val crossMs = prev.timestampEpochMs + (fraction * dtMs).toLong()
                    val elapsedMs = crossMs - stopTimeMs
                    val trapSpeed = prevSpeed + fraction * (currSpeed - prevSpeed)
                    if (bestQuarterMileMs == null || elapsedMs < bestQuarterMileMs) {
                        bestQuarterMileMs = elapsedMs
                        bestQuarterMileTrapSpeedMps = trapSpeed
                    }
                    quarterMileRecordedThisStop = true
                }
            }

            if (prevSpeed < MPH_60_MPS && currSpeed >= MPH_60_MPS) {
                val fraction = (MPH_60_MPS - prevSpeed) / (currSpeed - prevSpeed)
                val crossMs = prev.timestampEpochMs + (fraction * dtMs).toLong()
                sixtyCrossMs = crossMs
                val stopTime = lastStopTimeMs
                if (stopTime != null && !zeroToSixtyRecordedThisStop) {
                    val elapsedMs = crossMs - stopTime
                    if (bestZeroToSixtyMs == null || elapsedMs < bestZeroToSixtyMs) {
                        bestZeroToSixtyMs = elapsedMs
                    }
                    zeroToSixtyRecordedThisStop = true
                }
            } else if (currSpeed < MPH_60_MPS) {
                sixtyCrossMs = null
            }

            if (prevSpeed < MPH_100_MPS && currSpeed >= MPH_100_MPS) {
                val fraction = (MPH_100_MPS - prevSpeed) / (currSpeed - prevSpeed)
                val crossMs = prev.timestampEpochMs + (fraction * dtMs).toLong()
                hundredCrossMs = crossMs
                sixtyCrossMs?.let { s60 ->
                    val elapsedMs = crossMs - s60
                    if (bestSixtyToHundredMs == null || elapsedMs < bestSixtyToHundredMs) {
                        bestSixtyToHundredMs = elapsedMs
                    }
                }
            } else if (currSpeed < MPH_100_MPS) {
                hundredCrossMs = null
            }

            if (prevSpeed < MPH_130_MPS && currSpeed >= MPH_130_MPS) {
                val fraction = (MPH_130_MPS - prevSpeed) / (currSpeed - prevSpeed)
                val crossMs = prev.timestampEpochMs + (fraction * dtMs).toLong()
                sixtyCrossMs?.let { s60 ->
                    val elapsedMs = crossMs - s60
                    if (bestSixtyToOneThirtyMs == null || elapsedMs < bestSixtyToOneThirtyMs) {
                        bestSixtyToOneThirtyMs = elapsedMs
                    }
                }
                hundredCrossMs?.let { s100 ->
                    val elapsedMs = crossMs - s100
                    if (bestHundredToOneThirtyMs == null || elapsedMs < bestHundredToOneThirtyMs) {
                        bestHundredToOneThirtyMs = elapsedMs
                    }
                }
            }
        }

        return TripPerformanceStats(
            zeroToSixtyMs = bestZeroToSixtyMs,
            sixtyToHundredMs = bestSixtyToHundredMs,
            sixtyToOneThirtyMs = bestSixtyToOneThirtyMs,
            hundredToOneThirtyMs = bestHundredToOneThirtyMs,
            eighthMileMs = bestEighthMileMs,
            eighthMileTrapSpeedMps = bestEighthMileTrapSpeedMps,
            quarterMileMs = bestQuarterMileMs,
            quarterMileTrapSpeedMps = bestQuarterMileTrapSpeedMps
        )
    }
}
