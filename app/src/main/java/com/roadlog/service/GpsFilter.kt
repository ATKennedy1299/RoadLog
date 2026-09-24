package com.roadlog.service

import com.roadlog.data.LocationPoint
import com.roadlog.util.DistanceUtils

/**
 * Decides whether an incoming raw fix is plausible given the last *accepted*
 * point for the trip. Three independent checks:
 *
 *  1. Accuracy gate — a fix with a huge accuracy radius (e.g. cold-start GPS,
 *     indoor multipath) is rejected outright regardless of implied speed.
 *  2. Implied-speed gate — distance/time between this fix and the last
 *     accepted fix must not exceed a plausible max speed for any road
 *     vehicle. This catches classic "GPS teleport" glitches.
 *  3. Contiguous-gap gate — a fix arriving long after the last one (a
 *     tunnel, a dead zone, or the phone being turned off mid-trip) is
 *     accepted, but flagged as the start of a new segment rather than
 *     treated as a continuation: a straight line between them isn't the
 *     real path, and dividing that straight-line distance by the (mostly
 *     dead) elapsed time isn't a real speed either — it reads as crawling
 *     to the destination at a few mph, which isn't what happened.
 *
 * Rejected points are still persisted (isFiltered = true) rather than
 * dropped, so trip aggregates stay correct and replay/debugging can show
 * what was excluded.
 */
object GpsFilter {

    private const val MAX_ACCEPTABLE_ACCURACY_METERS = 50f
    private const val MAX_PLAUSIBLE_SPEED_MPS = 130.0 // ~468 km/h, generous ceiling
    private const val MIN_INTERVAL_SECONDS = 0.5 // guards against divide-by-near-zero

    // Normal tracking gets a fix every ~1-2s (or ~3-5s while just checking a
    // ride-detection candidate) — a gap well past either of those is a real
    // loss of data, not jitter.
    private const val MAX_CONTIGUOUS_GAP_SECONDS = 60.0

    data class FilterResult(
        val accepted: Boolean,
        val impliedSpeedMps: Double?,
        val isGapStart: Boolean = false
    )

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

        if (dtSeconds > MAX_CONTIGUOUS_GAP_SECONDS) {
            return FilterResult(accepted = true, impliedSpeedMps = null, isGapStart = true)
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
