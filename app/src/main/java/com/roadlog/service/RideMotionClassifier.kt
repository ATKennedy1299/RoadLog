package com.roadlog.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.roadlog.data.VehicleType
import kotlin.math.abs

/**
 * Suggests whether a just-recorded trip looks like it was ridden on a
 * motorcycle or driven in a car, purely from how the phone's orientation
 * moves through turns — this is a suggestion surfaced in the UI only,
 * never something that silently reassigns a trip's vehicle.
 *
 * The physics: a motorcycle leans INTO a turn (roll and turn direction
 * share a sign, and the lean can be tens of degrees) because that's how a
 * two-wheeled vehicle balances centripetal force. A car's body instead
 * rolls slightly AWAY from the turn under suspension compliance, by only
 * a few degrees. Comparing roll direction/magnitude against GPS turn
 * direction is a much more robust signal than reading an absolute lean
 * angle would be.
 *
 * IMPORTANT ASSUMPTION, not yet validated against a real ride: this reads
 * device roll assuming the phone is mounted rigidly, flat, screen up, top
 * edge toward the front of the vehicle — the same convention dedicated
 * motorcycle lean-angle apps require. A phone in a pocket, a bag, or an
 * unusual mount just won't produce a clean signal, which fails safe: too
 * few qualifying turns simply yields no suggestion rather than a wrong
 * one. The one thing that genuinely needs a real test ride to confirm is
 * the sign convention below (whether Android's roll-angle sign and its
 * compass-bearing sign agree or oppose for a lean in the same physical
 * direction) — if suggestions come out consistently backwards, flip the
 * `==` to `!=` in [evaluateTurn].
 */
class RideMotionClassifier : SensorEventListener {

    companion object {
        private const val BASELINE_SAMPLE_COUNT = 5

        // Below this, GPS bearing is too noisy (or meaningless, near a full
        // stop) to trust as a real turn direction.
        private const val MIN_TURN_SPEED_MPS = 3f

        // Degrees/second of heading change before this counts as an actual
        // turn rather than ordinary GPS bearing jitter going straight.
        private const val TURN_RATE_THRESHOLD_DEG_PER_SEC = 8f

        // A motorcycle-grade lean, once turning: comfortably above ordinary
        // car body roll, comfortably below what phone-mount jitter alone
        // could produce. Starting point only — expect to tune from real rides.
        private const val MOTORCYCLE_LEAN_THRESHOLD_DEG = 12f

        // At or below this during a confirmed turn reads as car-like body
        // roll (or none at all) rather than a two-wheeler's lean.
        private const val CAR_ROLL_MAX_DEG = 6f

        // Require several clearly-agreeing turns before trusting a verdict —
        // one turn could be noise, an odd mount angle, or a lane change.
        private const val MIN_QUALIFYING_TURNS = 4
        private const val AGREEMENT_RATIO_THRESHOLD = 0.7

        private val GAME_ROTATION_VECTOR = Sensor.TYPE_GAME_ROTATION_VECTOR
    }

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var baselineRollDeg: Float? = null
    private val baselineSamples = mutableListOf<Float>()
    private var latestRollDeg: Float? = null

    private var lastBearingDeg: Float? = null
    private var lastBearingTimeMs: Long? = null

    private var motorcycleAgreeCount = 0
    private var carAgreeCount = 0
    // Real turns whose roll fell in neither bucket (wrong-signed, or between
    // CAR_ROLL_MAX_DEG and MOTORCYCLE_LEAN_THRESHOLD_DEG) — not otherwise
    // visible anywhere, so debugSummary() surfaces it too: a high count here
    // relative to mc/car means the thresholds are the thing to retune, not a
    // sign-convention bug.
    private var ambiguousCount = 0

    /** Convenience for the service: null if this device has no usable sensor. */
    fun defaultSensor(sensorManager: SensorManager): Sensor? =
        sensorManager.getDefaultSensor(GAME_ROTATION_VECTOR)

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != GAME_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        // orientationAngles[2] is roll (rotation about the device's Y axis) —
        // with the assumed flat, screen-up, top-forward mount, that axis
        // points along the vehicle's direction of travel, so this is exactly
        // the vehicle's own roll/lean axis.
        val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

        if (baselineRollDeg == null) {
            baselineSamples += rollDeg
            if (baselineSamples.size >= BASELINE_SAMPLE_COUNT) {
                baselineRollDeg = baselineSamples.average().toFloat()
            }
        }
        latestRollDeg = rollDeg
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Call for every GPS fix that has a bearing while a trip is recording. */
    @Synchronized
    fun onLocationFix(bearingDeg: Float?, speedMps: Float?, timestampMs: Long) {
        if (bearingDeg == null || (speedMps != null && speedMps < MIN_TURN_SPEED_MPS)) return

        val prevBearing = lastBearingDeg
        val prevTime = lastBearingTimeMs
        val baseline = baselineRollDeg
        val roll = latestRollDeg
        if (prevBearing != null && prevTime != null && baseline != null && roll != null) {
            val dtSeconds = (timestampMs - prevTime) / 1000f
            if (dtSeconds > 0.2f) {
                val turnRateDegPerSec = signedBearingDelta(prevBearing, bearingDeg) / dtSeconds
                if (abs(turnRateDegPerSec) >= TURN_RATE_THRESHOLD_DEG_PER_SEC) {
                    evaluateTurn(turnRateDegPerSec, roll - baseline)
                }
            }
        }
        lastBearingDeg = bearingDeg
        lastBearingTimeMs = timestampMs
    }

    private fun evaluateTurn(turnRateDegPerSec: Float, rollDeviationDeg: Float) {
        val turnsRight = turnRateDegPerSec > 0
        val leansRight = rollDeviationDeg > 0
        val magnitude = abs(rollDeviationDeg)
        when {
            leansRight == turnsRight && magnitude >= MOTORCYCLE_LEAN_THRESHOLD_DEG -> motorcycleAgreeCount++
            magnitude <= CAR_ROLL_MAX_DEG -> carAgreeCount++
            // else: ambiguous (wrong-signed roll, or in between) — ignored
            // rather than forced into either bucket.
            else -> ambiguousCount++
        }
    }

    /** Smallest signed change from [from] to [to] in degrees, handling the 0/360 wraparound. */
    private fun signedBearingDelta(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    /** A verdict only when enough turns agreed clearly one way; null (no suggestion) otherwise. */
    @Synchronized
    fun finish(): VehicleType? {
        val total = motorcycleAgreeCount + carAgreeCount
        if (total < MIN_QUALIFYING_TURNS) return null
        val motorcycleRatio = motorcycleAgreeCount.toDouble() / total
        return when {
            motorcycleRatio >= AGREEMENT_RATIO_THRESHOLD -> VehicleType.MOTORCYCLE
            motorcycleRatio <= 1 - AGREEMENT_RATIO_THRESHOLD -> VehicleType.CAR
            else -> null
        }
    }

    @Synchronized
    fun reset() {
        baselineRollDeg = null
        baselineSamples.clear()
        latestRollDeg = null
        lastBearingDeg = null
        lastBearingTimeMs = null
        motorcycleAgreeCount = 0
        carAgreeCount = 0
        ambiguousCount = 0
    }

    /**
     * Temporary diagnostic instrumentation, surfaced on Trip Detail behind a
     * "DEBUG" label — remove once the classifier's thresholds are validated
     * against real rides. [sensorFound] comes from the caller (it knows
     * whether the device even has the sensor; this class only knows whether
     * samples arrived from one).
     */
    @Synchronized
    fun debugSummary(sensorFound: Boolean): String {
        val baseline = if (baselineRollDeg != null) "yes" else "no"
        return "sensor=$sensorFound baseline=$baseline mc=$motorcycleAgreeCount " +
            "car=$carAgreeCount ambiguous=$ambiguousCount"
    }
}
