package com.roadlog.service

import com.roadlog.data.LocationPoint
import com.roadlog.util.DistanceUtils

/**
 * Decides whether an incoming raw fix is plausible given the last *accepted*
 * point for the trip. Two independent checks:
 *
 *  1. Accuracy gate — a fix with a huge accuracy radius (e.g. cold-start GPS,
 *     indoor multipath) is rejected outright regardless of implied speed.
 *  2. Implied-speed gate — distance/time between this fix and the last
 *     accepted fix must not exceed a plausible max speed for any road
 *     vehicle. This catches classic "GPS teleport" glitches.
 *
 * Rejected points are still persisted (isFiltered = true) rather than
 * dropped, so trip aggregates stay correct and replay/debugging can show
 * what was excluded.
 */
object GpsFilter {

    private const val MAX_ACCEPTABLE_ACCURACY_METERS = 50f
    private const val MAX_PLAUSIBLE_SPEED_MPS = 130.0 // ~468 km/h, generous ceiling
    private const val MIN_INTERVAL_SECONDS = 0.5 // guards against divide-by-near-zero

    data class FilterResult(val accepted: Boolean, val impliedSpeedMps: Double?)

    fun evaluate(
        candidate: RawFix,
        lastAccepted: LocationPoint?
    ): FilterResult {
        if (candidate.accuracyMeters > MAX_ACCEPTABLE_ACCURACY_METERS) {
            return FilterResult(accepted = false, impliedSpeedMps = null)
        }

        if (lastAccepted == null) {
            // First point of the trip: nothing to compare against.
            return FilterResult(accepted = true, impliedSpeedMps = null)
        }

        val dtSeconds = (candidate.timestampEpochMs - lastAccepted.timestampEpochMs) / 1000.0
        if (dtSeconds < MIN_INTERVAL_SECONDS) {
            // Too close in time to trust the implied-speed math; accept but
            // don't compute a misleading speed.
            return FilterResult(accepted = true, impliedSpeedMps = null)
        }

        val distanceMeters = DistanceUtils.haversineMeters(
            lastAccepted.latitude, lastAccepted.longitude,
            candidate.latitude, candidate.longitude
        )
        val impliedSpeed = distanceMeters / dtSeconds

        return if (impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
            FilterResult(accepted = false, impliedSpeedMps = impliedSpeed)
        } else {
            FilterResult(accepted = true, impliedSpeedMps = impliedSpeed)
        }
    }

    data class RawFix(
        val timestampEpochMs: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float
    )
}
