package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.DistanceUnit
import com.roadlog.data.LocationPoint
import com.roadlog.data.SpeedSource
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import com.roadlog.util.TripEvent
import com.roadlog.util.TripEventAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * An instant along the replay timeline. [speedSource] is carried through
 * from the underlying LocationPoint rather than assumed, so the UI never
 * has to guess or hardcode where the speed reading came from. [speedLimitMps]
 * is null wherever no nearby road was matched (see SpeedLimitLookup) — the
 * speed-limit sign simply hides itself for that stretch rather than
 * guessing or showing a stale value.
 */
data class ReplayFrame(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val speedSource: SpeedSource,
    val speedLimitMps: Float?
)

class TripReplayViewModel(
    private val tripId: Long,
    private val repository: TripRepository,
    unitsRepository: UnitsRepository
) : ViewModel() {

    val unit: StateFlow<DistanceUnit> = unitsRepository.unit

    private val _points = MutableStateFlow<List<LocationPoint>>(emptyList())
    val points: StateFlow<List<LocationPoint>> = _points.asStateFlow()

    /** Same dots shown on Trip Detail's static map (see TripEventAnalyzer), so replay and detail always agree. */
    val tripEvents: StateFlow<List<TripEvent>> = points
        .map { TripEventAnalyzer.detectEvents(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1)
    val playbackSpeed: StateFlow<Int> = _playbackSpeed.asStateFlow()

    /** Position along the trip's recorded duration, 0f (start) to 1f (end). */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    val currentFrame: StateFlow<ReplayFrame?> = combine(_points, _progress) { points, progress ->
        computeFrame(points, progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val totalDurationMs: Long
        get() {
            val pts = _points.value
            if (pts.size < 2) return 0L
            return pts.last().timestampEpochMs - pts.first().timestampEpochMs
        }

    private var playbackJob: Job? = null

    init {
        viewModelScope.launch {
            _points.value = repository.getRoutePoints(tripId)
        }
    }

    fun togglePlayback() {
        if (_isPlaying.value) pausePlayback() else startPlayback()
    }

    fun seekTo(fraction: Float) {
        pausePlayback() // matches typical player UX: scrubbing manually stops auto-advance
        _progress.value = fraction.coerceIn(0f, 1f)
    }

    fun setPlaybackSpeed(multiplier: Int) {
        _playbackSpeed.value = multiplier
    }

    private fun startPlayback() {
        if (totalDurationMs <= 0L) return
        if (_progress.value >= 1f) _progress.value = 0f
        _isPlaying.value = true
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            var lastTick = System.currentTimeMillis()
            while (_isPlaying.value) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                val realDeltaMs = now - lastTick
                lastTick = now
                val advanceMs = realDeltaMs * _playbackSpeed.value
                val newProgress = _progress.value + (advanceMs.toFloat() / totalDurationMs.toFloat())
                if (newProgress >= 1f) {
                    _progress.value = 1f
                    _isPlaying.value = false
                } else {
                    _progress.value = newProgress
                }
            }
        }
    }

    private fun pausePlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    override fun onCleared() {
        playbackJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TICK_MS = 50L
    }
}

private fun computeFrame(points: List<LocationPoint>, progress: Float): ReplayFrame? {
    if (points.isEmpty()) return null
    val totalDurationMs = points.last().timestampEpochMs - points.first().timestampEpochMs
    if (points.size == 1 || totalDurationMs <= 0L) {
        val only = points.first()
        return ReplayFrame(
            only.latitude, only.longitude, only.gpsSpeedMps?.toDouble() ?: 0.0, only.speedSource, only.speedLimitMps
        )
    }

    val startTime = points.first().timestampEpochMs
    val targetElapsedMs = (progress.toDouble() * totalDurationMs).toLong()

    var nextIndex = points.indexOfFirst { (it.timestampEpochMs - startTime) >= targetElapsedMs }
    if (nextIndex <= 0) nextIndex = 1
    if (nextIndex >= points.size) nextIndex = points.size - 1

    val prev = points[nextIndex - 1]
    val next = points[nextIndex]
    val prevElapsedMs = prev.timestampEpochMs - startTime
    val nextElapsedMs = next.timestampEpochMs - startTime
    val span = (nextElapsedMs - prevElapsedMs).coerceAtLeast(1)
    val t = ((targetElapsedMs - prevElapsedMs).toDouble() / span).coerceIn(0.0, 1.0)

    if (next.startsNewSegment) {
        // No real path data between these two fixes (a tunnel, dead zone, or
        // the phone being off) — holding at prev's position until next's
        // timestamp arrives reads truer than smoothly gliding across ground
        // that was never actually recorded, and matches the route map's own
        // break in the line here.
        val holdAtPrev = t < 1.0
        val point = if (holdAtPrev) prev else next
        val speed = if (holdAtPrev) (prev.gpsSpeedMps?.toDouble() ?: 0.0) else (next.gpsSpeedMps?.toDouble() ?: 0.0)
        return ReplayFrame(point.latitude, point.longitude, speed, point.speedSource, point.speedLimitMps)
    }

    val lat = prev.latitude + (next.latitude - prev.latitude) * t
    val lng = prev.longitude + (next.longitude - prev.longitude) * t
    val prevSpeed = prev.gpsSpeedMps?.toDouble() ?: 0.0
    val nextSpeed = next.gpsSpeedMps?.toDouble() ?: 0.0
    val speed = prevSpeed + (nextSpeed - prevSpeed) * t

    return ReplayFrame(lat, lng, speed, next.speedSource, next.speedLimitMps)
}
