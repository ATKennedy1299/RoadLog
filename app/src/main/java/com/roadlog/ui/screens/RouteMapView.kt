package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roadlog.data.SpeedZone
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.AccentRed
import com.roadlog.ui.theme.SpeedZoneOrange
import com.roadlog.ui.theme.SpeedZoneYellow
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// Free public MapLibre demo style — no API key needed. Swap for a hosted
// style/tile provider before shipping to production.
private const val STREET_STYLE_URL = "https://demotiles.maplibre.org/style.json"

// Esri's public World Imagery + World Transportation tile services — also
// free/keyless, standard combo for a satellite-with-roads "hybrid" look.
private const val SATELLITE_TILE_URL =
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
private const val ROADS_OVERLAY_TILE_URL =
    "https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"
private const val ESRI_ATTRIBUTION = "Esri, Maxar, Earthstar Geographics, and the GIS User Community"
private const val TILE_JSON_VERSION = "2.1.0"
private const val RASTER_TILE_SIZE = 256

private const val ROUTE_SOURCE_ID = "roadlog-route-source"
private const val ROUTE_LAYER_ID = "roadlog-route-layer"
private const val ROUTE_ZONE_PROPERTY = "zone"
private const val SATELLITE_SOURCE_ID = "roadlog-satellite-source"
private const val SATELLITE_LAYER_ID = "roadlog-satellite-layer"
private const val ROADS_OVERLAY_SOURCE_ID = "roadlog-roads-overlay-source"
private const val ROADS_OVERLAY_LAYER_ID = "roadlog-roads-overlay-layer"
private const val CAMERA_PADDING_PX = 64

enum class MapType { STREET, SATELLITE }

/** What RouteMapView needs per point: position plus the recorded speed driving its segment's color. */
data class RoutePoint(val latLng: LatLng, val speedMps: Float?)

/**
 * Renders a route as a polyline colored by recorded speed (see [SpeedZone]),
 * with a built-in street/satellite toggle in the top-right corner
 * (satellite by default) and a speed-zone legend in the top-left. By
 * default also frames the camera to fit the whole route — pass
 * [autoFrameCamera] = false when the caller wants to own the camera itself
 * (e.g. trip replay's follow-camera). [onMapReady] fires once the
 * underlying map object exists — independent of which style is currently
 * applied — so callers can safely attach map listeners (e.g. a tap handler)
 * without worrying about the style toggle re-triggering them.
 */
@Composable
fun RouteMapView(
    points: List<RoutePoint>,
    modifier: Modifier = Modifier,
    autoFrameCamera: Boolean = true,
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier.background(SurfaceRaised),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No GPS points recorded for this trip.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // This fires when the composable is permanently removed from
            // composition (e.g. navigating back from trip detail/replay),
            // which is distinct from — and much more common than — the
            // Activity itself reaching ON_DESTROY (this is a single-Activity
            // app, so that only happens when the whole app closes). Without
            // an explicit onDestroy() here, every map screen visited leaks
            // its native GL/tile resources until the process dies. Run the
            // full pause/stop/destroy sequence regardless of the map's last
            // lifecycle state, since disposal can happen from any of them.
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val zoneColors = remember {
        ZoneColors(
            normal = AccentGreen.toArgb(),
            yellow = SpeedZoneYellow.toArgb(),
            orange = SpeedZoneOrange.toArgb(),
            red = AccentRed.toArgb()
        )
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var reportedReady by remember { mutableStateOf(false) }
    var mapType by remember { mutableStateOf(MapType.SATELLITE) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                view.getMapAsync { readyMap ->
                    map = readyMap
                    if (!reportedReady) {
                        reportedReady = true
                        onMapReady(readyMap)
                    }
                }
            }
        )

        // Re-applies only when the map first becomes available, the route
        // points change, or the user toggles map type — not on every
        // unrelated recomposition (e.g. a replay playback tick), which
        // would otherwise reload the style/tiles and rebuild the whole
        // route from scratch every frame.
        val currentMap = map
        if (currentMap != null) {
            LaunchedEffect(currentMap, mapType, points) {
                currentMap.setStyle(styleBuilderFor(mapType)) { style ->
                    drawRoute(style, points, zoneColors)
                    if (autoFrameCamera) {
                        frameCamera(currentMap, points)
                    }
                }
            }
        }

        MapTypeToggle(
            mapType = mapType,
            onToggle = { mapType = if (mapType == MapType.STREET) MapType.SATELLITE else MapType.STREET },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        )

        SpeedZoneLegend(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )
    }
}

@Composable
private fun MapTypeToggle(mapType: MapType, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = if (mapType == MapType.STREET) "SATELLITE" else "MAP",
        style = MaterialTheme.typography.labelSmall,
        color = AccentGreen,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(SurfaceRaised, RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun SpeedZoneLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(SurfaceRaised.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendEntry(AccentGreen, "<70")
        LegendEntry(SpeedZoneYellow, "70-89")
        LegendEntry(SpeedZoneOrange, "90-99")
        LegendEntry(AccentRed, "100+")
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

private fun styleBuilderFor(mapType: MapType): Style.Builder = when (mapType) {
    MapType.STREET -> Style.Builder().fromUri(STREET_STYLE_URL)
    MapType.SATELLITE -> {
        val satelliteTiles = TileSet(TILE_JSON_VERSION, SATELLITE_TILE_URL).apply {
            attribution = ESRI_ATTRIBUTION
            minZoom = 0f
            maxZoom = 19f
        }
        val roadsTiles = TileSet(TILE_JSON_VERSION, ROADS_OVERLAY_TILE_URL).apply {
            attribution = ESRI_ATTRIBUTION
            minZoom = 0f
            maxZoom = 19f
        }
        Style.Builder()
            .withSource(RasterSource(SATELLITE_SOURCE_ID, satelliteTiles, RASTER_TILE_SIZE))
            .withLayer(RasterLayer(SATELLITE_LAYER_ID, SATELLITE_SOURCE_ID))
            .withSource(RasterSource(ROADS_OVERLAY_SOURCE_ID, roadsTiles, RASTER_TILE_SIZE))
            .withLayer(RasterLayer(ROADS_OVERLAY_LAYER_ID, ROADS_OVERLAY_SOURCE_ID))
    }
}

private data class ZoneColors(val normal: Int, val yellow: Int, val orange: Int, val red: Int) {
    fun forZone(zone: SpeedZone): Int = when (zone) {
        SpeedZone.NORMAL -> normal
        SpeedZone.YELLOW -> yellow
        SpeedZone.ORANGE -> orange
        SpeedZone.RED -> red
    }
}

private data class ColoredSegment(val zone: SpeedZone, val points: List<LatLng>)

/**
 * Groups consecutive GPS points into runs that share the same [SpeedZone],
 * so the route renders as a handful of multi-point line features instead of
 * one tiny feature per point pair — cheaper to build and render, and with
 * no visible gaps at zone transitions since adjacent runs share their
 * boundary point. A segment between point i and i+1 is colored by the
 * speed recorded at point i (the segment's starting point).
 */
private fun buildColoredSegments(points: List<RoutePoint>): List<ColoredSegment> {
    if (points.size < 2) return emptyList()

    val segmentZones = (0 until points.size - 1).map { SpeedZone.fromMps(points[it].speedMps) }
    val runs = mutableListOf<ColoredSegment>()
    var runStart = 0
    var runZone = segmentZones[0]

    for (segmentIndex in 1 until segmentZones.size) {
        val zone = segmentZones[segmentIndex]
        if (zone != runZone) {
            runs += ColoredSegment(runZone, points.subList(runStart, segmentIndex + 1).map { it.latLng })
            runStart = segmentIndex
            runZone = zone
        }
    }
    runs += ColoredSegment(runZone, points.subList(runStart, points.size).map { it.latLng })
    return runs
}

private fun drawRoute(style: Style, points: List<RoutePoint>, zoneColors: ZoneColors) {
    val segments = buildColoredSegments(points)
    val features = segments.map { segment ->
        Feature.fromGeometry(
            LineString.fromLngLats(segment.points.map { Point.fromLngLat(it.longitude, it.latitude) })
        ).apply {
            addStringProperty(ROUTE_ZONE_PROPERTY, segment.zone.name)
        }
    }
    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(features)))
    style.addLayer(
        LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(zoneColorExpression(zoneColors)),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
}

/** Data-driven paint expression evaluated natively per feature — no per-frame Kotlin/Compose work. */
private fun zoneColorExpression(zoneColors: ZoneColors): Expression {
    val stops = SpeedZone.entries.map { zone ->
        Expression.stop(zone.name, Expression.color(zoneColors.forZone(zone)))
    }.toTypedArray()
    return Expression.match(
        Expression.get(ROUTE_ZONE_PROPERTY),
        Expression.color(zoneColors.normal), // fallback for any unrecognized/missing property value
        *stops
    )
}

private fun frameCamera(map: MapLibreMap, points: List<RoutePoint>) {
    if (points.size < 2) {
        val only = points.firstOrNull()?.latLng ?: return
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(only, 14.0))
        return
    }
    val bounds = LatLngBounds.fromLatLngs(points.map { it.latLng })
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING_PX))
}
