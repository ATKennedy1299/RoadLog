package com.roadlog.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object DistanceUtils {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance between two lat/lon points, in meters. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Shortest distance from a point to a line segment, in meters. Projects
     * onto a local equirectangular plane (scaling longitude by cos(latitude))
     * rather than doing true great-circle segment math — accurate to well
     * within a meter at the road-matching scale (tens of meters) this is
     * used for, at a fraction of the complexity.
     */
    fun pointToSegmentMeters(
        pointLat: Double, pointLon: Double,
        segStartLat: Double, segStartLon: Double,
        segEndLat: Double, segEndLon: Double
    ): Double {
        val latScale = EARTH_RADIUS_METERS * Math.PI / 180.0
        val lonScale = latScale * cos(Math.toRadians(pointLat))

        val px = pointLon * lonScale
        val py = pointLat * latScale
        val ax = segStartLon * lonScale
        val ay = segStartLat * latScale
        val bx = segEndLon * lonScale
        val by = segEndLat * latScale

        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared > 0.0) {
            (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val closestX = ax + t * dx
        val closestY = ay + t * dy
        val ddx = px - closestX
        val ddy = py - closestY
        return sqrt(ddx * ddx + ddy * ddy)
    }
}
