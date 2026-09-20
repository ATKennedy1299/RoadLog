package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.DistanceUnit
import com.roadlog.data.SpeedSource
import com.roadlog.ui.ReplayFrame
import com.roadlog.ui.TripReplayViewModel
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.roundToInt

private const val MARKER_SOURCE_ID = "roadlog-replay-marker-source"
private const val MARKER_LAYER_ID = "roadlog-replay-marker-layer"

@Composable
fun TripReplayScreen(
    viewModel: TripReplayViewModel,
    onBack: () -> Unit
) {
    val points by viewModel.points.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val currentFrame by viewModel.currentFrame.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Text(
            text = "‹ BACK",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.clickable(onClick = onBack)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "TRIP REPLAY",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )
        Spacer(Modifier.height(16.dp))

        if (points.size < 2) {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "Not enough GPS points to replay this trip.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        } else {
            ReplayMap(
                points = points.map { LatLng(it.latitude, it.longitude) },
                frame = currentFrame,
                unit = unit,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(Modifier.height(20.dp))

            ReplayControls(
                isPlaying = isPlaying,
                progress = progress,
                playbackSpeed = playbackSpeed,
                elapsedSeconds = ((progress.toDouble() * viewModel.totalDurationMs) / 1000).toLong(),
                totalSeconds = viewModel.totalDurationMs / 1000,
                onTogglePlay = viewModel::togglePlayback,
                onSeek = viewModel::seekTo,
                onSpeedChange = viewModel::setPlaybackSpeed
            )
        }
    }
}

@Composable
private fun ReplayMap(
    points: List<LatLng>,
    frame: ReplayFrame?,
    unit: DistanceUnit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        RouteMapView(
            points = points,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map = it }
        )

        val currentMap = map
        if (frame != null && currentMap != null) {
            val markerColor = remember { AccentGreen.toArgb() }
            SideEffect {
                upsertMarker(currentMap, LatLng(frame.latitude, frame.longitude), markerColor)
            }

            val density = LocalDensity.current
            val bubbleOffsetXPx = remember(density) { with(density) { (-40).dp.toPx() } }
            val bubbleOffsetYPx = remember(density) { with(density) { (-68).dp.toPx() } }
            val screenPoint = currentMap.projection.toScreenLocation(LatLng(frame.latitude, frame.longitude))

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (screenPoint.x + bubbleOffsetXPx).roundToInt(),
                            (screenPoint.y + bubbleOffsetYPx).roundToInt()
                        )
                    }
                    .background(SurfaceRaised, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Column {
                    Text(
                        text = String.format(Locale.US, "%.0f %s", unit.mpsToSpeed(frame.speedMps), unit.speedLabel),
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = frame.speedSource.displayLabel(),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private fun SpeedSource.displayLabel(): String = when (this) {
    SpeedSource.GPS_RAW -> "GPS"
}

private fun upsertMarker(map: MapLibreMap, position: LatLng, colorArgb: Int) {
    val style = map.style ?: return
    val point = Point.fromLngLat(position.longitude, position.latitude)
    val existingSource = style.getSourceAs<GeoJsonSource>(MARKER_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setGeoJson(point)
    } else {
        style.addSource(GeoJsonSource(MARKER_SOURCE_ID, point))
        style.addLayer(
            CircleLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(colorArgb),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(android.graphics.Color.BLACK)
            )
        )
    }
}

@Composable
private fun ReplayControls(
    isPlaying: Boolean,
    progress: Float,
    playbackSpeed: Int,
    elapsedSeconds: Long,
    totalSeconds: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Slider(
            value = progress,
            onValueChange = onSeek,
            colors = SliderDefaults.colors(
                thumbColor = AccentGreen,
                activeTrackColor = AccentGreen,
                inactiveTrackColor = SurfaceRaised
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatElapsed(elapsedSeconds), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(formatElapsed(totalSeconds), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 8).forEach { speed ->
                    val selected = speed == playbackSpeed
                    Text(
                        text = "${speed}x",
                        color = if (selected) Color.Black else TextSecondary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(if (selected) AccentGreen else SurfaceRaised, RoundedCornerShape(8.dp))
                            .clickable { onSpeedChange(speed) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Button(
                onClick = onTogglePlay,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isPlaying) "PAUSE" else "PLAY", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
