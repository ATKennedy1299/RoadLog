package com.roadlog.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Trip count, total distance, total time, and top speed for one time bucket (e.g. "this week"). */
data class PeriodStats(
    val tripCount: Int,
    val totalDistanceMeters: Double,
    val totalDurationMs: Long,
    val highestSpeedMps: Double = 0.0
) {
    companion object {
        val EMPTY = PeriodStats(0, 0.0, 0L, 0.0)
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
            totalDurationMs = inPeriod.sumOf { it.durationMs() },
            highestSpeedMps = inPeriod.maxOfOrNull { it.maxSpeedMps } ?: 0.0
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
            totalDurationMs = totalDurationMs,
            highestSpeedMps = highestSpeedMps
        )
    )
}

/** One month's total distance, for a small trailing bar chart on a vehicle's page. */
data class MonthlyDistance(val monthLabel: String, val distanceMeters: Double)

/** Trailing [monthsBack]+1 months (inclusive of the current one), oldest first. */
fun computeMonthlyDistances(
    trips: List<Trip>,
    monthsBack: Int = 5,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): List<MonthlyDistance> {
    val completed = trips.filter { it.status == TripStatus.COMPLETED }
    val currentMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(zoneId))
    return (monthsBack downTo 0).map { offset ->
        val month = currentMonth.minusMonths(offset.toLong())
        val distance = completed
            .filter { YearMonth.from(Instant.ofEpochMilli(it.startTimeEpochMs).atZone(zoneId)) == month }
            .sumOf { it.distanceMeters }
        MonthlyDistance(month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), distance)
    }
}

/** One calendar-week-of-the-month's total distance, for the "this month" chart. */
data class WeeklyDistance(val weekLabel: String, val distanceMeters: Double)

/**
 * Buckets this month's completed trips into W1..W5 by day-of-month / 7 —
 * a simple positional bucket rather than true week-of-year boundaries,
 * which is all a small "this month" chart needs.
 */
fun computeWeeklyDistancesThisMonth(
    trips: List<Trip>,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): List<WeeklyDistance> {
    val completed = trips.filter { it.status == TripStatus.COMPLETED }
    val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    val startOfMonth = today.withDayOfMonth(1)
    val endOfMonth = today.withDayOfMonth(today.lengthOfMonth())

    fun dateOf(trip: Trip): LocalDate = Instant.ofEpochMilli(trip.startTimeEpochMs).atZone(zoneId).toLocalDate()
    fun weekIndex(date: LocalDate): Int = (date.dayOfMonth - 1) / 7

    val inMonth = completed.filter { !dateOf(it).isBefore(startOfMonth) && !dateOf(it).isAfter(endOfMonth) }
    val weekCount = weekIndex(endOfMonth) + 1
    return (0 until weekCount).map { index ->
        val distance = inMonth.filter { weekIndex(dateOf(it)) == index }.sumOf { it.distanceMeters }
        WeeklyDistance("W${index + 1}", distance)
    }
}

/** One vehicle's all-time totals, plus its share of total distance across the whole garage. */
data class VehicleUsageStats(
    val vehicle: VehicleProfile,
    val totalDistanceMeters: Double,
    val totalDurationMs: Long,
    val highestSpeedMps: Double,
    val shareOfTotalDistance: Float
)

/** Ranked by distance, descending — vehicles with zero completed trips are left out entirely. */
fun computeVehicleUsageStats(trips: List<Trip>, vehicles: List<VehicleProfile>): List<VehicleUsageStats> {
    val completed = trips.filter { it.status == TripStatus.COMPLETED }
    val totalDistance = completed.sumOf { it.distanceMeters }
    return vehicles.mapNotNull { vehicle ->
        val vehicleTrips = completed.filter { it.vehicleProfileId == vehicle.id }
        if (vehicleTrips.isEmpty()) return@mapNotNull null
        val distance = vehicleTrips.sumOf { it.distanceMeters }
        VehicleUsageStats(
            vehicle = vehicle,
            totalDistanceMeters = distance,
            totalDurationMs = vehicleTrips.sumOf { it.durationMs() },
            highestSpeedMps = vehicleTrips.maxOf { it.maxSpeedMps },
            shareOfTotalDistance = if (totalDistance > 0) (distance / totalDistance).toFloat() else 0f
        )
    }.sortedByDescending { it.totalDistanceMeters }
}
