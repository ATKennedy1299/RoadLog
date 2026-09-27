package com.roadlog.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** Trip count, total distance, and total time for one time bucket (e.g. "this week"). */
data class PeriodStats(
    val tripCount: Int,
    val totalDistanceMeters: Double,
    val totalDurationMs: Long
) {
    companion object {
        val EMPTY = PeriodStats(0, 0.0, 0L)
    }
}

/**
 * Cumulative driving/riding stats across a set of trips. Computed entirely
 * from each Trip's own rolling aggregates (distanceMeters/maxSpeedMps/
 * timestamps, updated incrementally as a trip records) — never by re-reading
 * raw GPS points — so this stays cheap regardless of trip history length.
 *
 * Deliberately takes a plain List<Trip> rather than reading the repository
 * itself: a future "filter by vehicle" feature just filters that list
 * before calling in (e.g. `trips.filter { it.vehicleProfileId == id }`),
 * with no change needed here.
 */
data class CumulativeStats(
    val totalTrips: Int,
    val totalDistanceMeters: Double,
    val totalDurationMs: Long,
    val highestSpeedMps: Double,
    val averageTripDistanceMeters: Double,
    val averageTripDurationMs: Long,
    val longestTrip: Trip?,
    val fastestTrip: Trip?,
    val thisWeek: PeriodStats,
    val thisMonth: PeriodStats,
    val thisYear: PeriodStats,
    val allTime: PeriodStats
) {
    companion object {
        val EMPTY = CumulativeStats(
            totalTrips = 0,
            totalDistanceMeters = 0.0,
            totalDurationMs = 0L,
            highestSpeedMps = 0.0,
            averageTripDistanceMeters = 0.0,
            averageTripDurationMs = 0L,
            longestTrip = null,
            fastestTrip = null,
            thisWeek = PeriodStats.EMPTY,
            thisMonth = PeriodStats.EMPTY,
            thisYear = PeriodStats.EMPTY,
            allTime = PeriodStats.EMPTY
        )
    }
}

/** A trip's clamped, never-negative duration in ms (0 if somehow missing an end time). */
private fun Trip.durationMs(): Long =
    ((endTimeEpochMs ?: startTimeEpochMs) - startTimeEpochMs).coerceAtLeast(0L)

/**
 * Only [TripStatus.COMPLETED] trips are considered: an in-progress trip's
 * distance/duration are still changing, and folding it in would make
 * "longest"/"fastest"/period totals shift live in a way that doesn't read
 * as a stable summary.
 *
 * A trip is bucketed into "this week/month/year" purely by its recorded
 * [Trip.startTimeEpochMs] — a trip that starts before midnight and ends
 * after is not split across periods, it belongs wholly to the period its
 * start falls in.
 */
fun computeCumulativeStats(
    trips: List<Trip>,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): CumulativeStats {
    val completed = trips.filter { it.status == TripStatus.COMPLETED }
    if (completed.isEmpty()) return CumulativeStats.EMPTY

    val totalDistanceMeters = completed.sumOf { it.distanceMeters }
    val totalDurationMs = completed.sumOf { it.durationMs() }
    val highestSpeedMps = completed.maxOf { it.maxSpeedMps }
    val longestTrip = completed.maxByOrNull { it.distanceMeters }
    val fastestTrip = completed.maxByOrNull { it.maxSpeedMps }

    val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    val weekFields = WeekFields.of(Locale.getDefault())
    val startOfWeek = today.with(weekFields.dayOfWeek(), 1L)
    val startOfMonth = today.withDayOfMonth(1)
    val startOfYear = today.withDayOfYear(1)

    fun periodSince(startDate: LocalDate): PeriodStats {
        val startMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val inPeriod = completed.filter { it.startTimeEpochMs >= startMs }
        return PeriodStats(
            tripCount = inPeriod.size,
            totalDistanceMeters = inPeriod.sumOf { it.distanceMeters },
            totalDurationMs = inPeriod.sumOf { it.durationMs() }
        )
    }

    return CumulativeStats(
        totalTrips = completed.size,
        totalDistanceMeters = totalDistanceMeters,
        totalDurationMs = totalDurationMs,
        highestSpeedMps = highestSpeedMps,
        averageTripDistanceMeters = totalDistanceMeters / completed.size,
        averageTripDurationMs = totalDurationMs / completed.size,
        longestTrip = longestTrip,
        fastestTrip = fastestTrip,
        thisWeek = periodSince(startOfWeek),
        thisMonth = periodSince(startOfMonth),
        thisYear = periodSince(startOfYear),
        allTime = PeriodStats(
            tripCount = completed.size,
            totalDistanceMeters = totalDistanceMeters,
            totalDurationMs = totalDurationMs
        )
    )
}
