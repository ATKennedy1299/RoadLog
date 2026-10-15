package com.roadlog.util

import com.roadlog.data.LocationPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a full-fidelity CSV of a trip's raw recorded points — every field
 * GpsFilter/TripRepository actually stored, including filtered/rejected
 * points and gap markers, not just what the UI derives from them. This is a
 * troubleshooting tool (e.g. "why did top speed read X and not Y") rather
 * than a day-to-day feature, so it deliberately dumps everything rather
 * than picking a curated subset of columns.
 */
object CsvExporter {
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private const val MPS_TO_MPH = 2.23694

    fun buildCsv(points: List<LocationPoint>): String {
        val builder = StringBuilder()
        builder.append(
            "timestamp_iso,timestamp_epoch_ms,latitude,longitude,gps_speed_mps,gps_speed_mph," +
                "accuracy_m,altitude_m,bearing_deg,is_filtered,starts_new_segment,speed_limit_mps\n"
        )
        for (point in points) {
            builder.append(TIMESTAMP_FORMAT.format(Date(point.timestampEpochMs))).append(',')
            builder.append(point.timestampEpochMs).append(',')
            builder.append(point.latitude).append(',')
            builder.append(point.longitude).append(',')
            builder.append(point.gpsSpeedMps?.toString().orEmpty()).append(',')
            builder.append(
                point.gpsSpeedMps?.let { String.format(Locale.US, "%.1f", it * MPS_TO_MPH) }.orEmpty()
            ).append(',')
            builder.append(point.accuracyMeters).append(',')
            builder.append(point.altitudeMeters?.toString().orEmpty()).append(',')
            builder.append(point.bearing?.toString().orEmpty()).append(',')
            builder.append(point.isFiltered).append(',')
            builder.append(point.startsNewSegment).append(',')
            builder.append(point.speedLimitMps?.toString().orEmpty()).append('\n')
        }
        return builder.toString()
    }
}
