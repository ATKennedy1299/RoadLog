package com.roadlog.data

enum class DistanceUnit(val distanceLabel: String, val speedLabel: String) {
    METRIC("km", "km/h"),
    IMPERIAL("mi", "mph");

    fun metersToDistance(meters: Double): Double =
        if (this == IMPERIAL) meters / 1609.344 else meters / 1000.0

    fun mpsToSpeed(mps: Double): Double =
        if (this == IMPERIAL) mps * 2.236936 else mps * 3.6
}
