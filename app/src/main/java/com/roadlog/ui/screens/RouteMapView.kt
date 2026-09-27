package com.roadlog.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import com.roadlog.ui.theme.AccentAmber
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.AccentRed
import com.roadlog.ui.theme.SpeedZoneOrange
import com.roadlog.ui.theme.SpeedZoneYellow
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import com.roadlog.util.TripEventType
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
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
private const val START_MARKER_SOURCE_ID = "roadlog-start-marker-source"
private const val START_MARKER_LAYER_ID = "roadlog-start-marker-layer"
private const val END_MARKER_SOURCE_ID = "roadlog-end-marker-source"
private const val END_MARKER_LAYER_ID = "roadlog-end-marker-layer"
private const val END_PIN_IMAGE_ID = "roadlog-end-pin-icon"
private const val PIN_WIDTH_DP = 26f
private const val PIN_HEIGHT_DP = 34f
private const val CAMERA_PADDING_PX = 64
private const val EVENT_SOURCE_ID = "roadlog-event-source"
// internal (not private) so TripReplayScreen can hit-test taps against
// this layer to distinguish "tapped a dot" from "tapped the map to seek."
internal const val EVENT_LAYER_ID = "roadlog-event-layer"
internal const val EVENT_TYPE_PROPERTY = "eventType"

enum class MapType { STREET, SATELLITE }

/**
 * What RouteMapView needs per point: position, the recorded speed driving
 * its segment's color, and whether it starts a new segment after a GPS gap
 * (see GpsFilter) — the line is broken there rather than drawing a straight
 * line across ground that was never actually recorded.
 */
data class RoutePoint(val latLng: LatLng, val speedMps: Float?, val startsNewSegment: Boolean = false)

/** A hard-accel/braking or speeding dot (see TripEventAnalyzer) to overlay on the route. */
data class RouteEventMarker(val latLng: LatLng, val type: TripEventType)

/**
 * Renders a route as a polyline colored by recorded speed (see [SpeedZone]),
 * with a white circle marking the trip's start and a pin — tinted to match
 * the route's color at that end — marking the finish, a built-in
 * street/satellite toggle in the top-right corner (satellite by default),
 * and a speed-zone legend in the top-left. By
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
    events: List<RouteEventMarker> = emptyList(),
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
    val eventColors = remember {
        EventColors(
            hardAccel = AccentGreen.toArgb(),
            hardBrake = AccentRed.toArgb(),
            speeding = AccentAmber.toArgb()
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
            LaunchedEffect(currentMap, mapType, points, events) {
                currentMap.setStyle(styleBuilderFor(mapType)) { style ->
                    drawRoute(style, points, zoneColors)
                    drawEventMarkers(style, events, eventColors)
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

private data class EventColors(val hardAccel: Int, val hardBrake: Int, val speeding: Int)

private data class ColoredSegment(val zone: SpeedZone, val points: List<LatLng>)

/**
 * Groups consecutive GPS points into runs that share the same [SpeedZone],
 * so the route renders as a handful of multi-point line features instead of
 * one tiny feature per point pair — cheaper to build and render, and with
 * no visible gaps at zone transitions since adjacent runs share their
 * boundary point. A segment between point i and i+1 is colored by the
 * speed recorded at point i (the segment's starting point).
 *
 * A point with [RoutePoint.startsNewSegment] set breaks the line entirely
 * rather than joining a run: the hop leading into it crosses a GPS gap (a
 * tunnel, a dead zone, the phone being off), so there's no real path to
 * draw there, only a resumption point.
 */
private fun buildColoredSegments(points: List<RoutePoint>): List<ColoredSegment> {
    if (points.size < 2) return emptyList()

    val runs = mutableListOf<ColoredSegment>()
    var runStart = -1
    var runZone: SpeedZone? = null

    fun flushRun(endExclusive: Int) {
        val zone = runZone
        if (runStart >= 0 && zone != null) {
            runs += ColoredSegment(zone, points.subList(runStart, endExclusive).map { it.latLng })
        }
        runStart = -1
        runZone = null
    }

    for (i in 0 until points.size - 1) {
        if (points[i + 1].startsNewSegment) {
            flushRun(i + 1) // close out the run up to and including point i; draw nothing across the gap
            continue
        }
        val zone = SpeedZone.fromMps(points[i].speedMps)
        if (runStart < 0) {
            runStart = i
            runZone = zone
        } else if (zone != runZone) {
            flushRun(i + 1)
            runStart = i
            runZone = zone
        }
    }
    flushRun(points.size)
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

    addStartMarker(style, points.first().latLng)
    // Only meaningful once there's an actual route (2+ points). Tint the pin
    // with the last drawn segment's zone color so it reads as part of the
    // same route; if the final hop was a GPS gap (no drawn segment reaches
    // the endpoint), fall back to the endpoint's own recorded speed so the
    // marker still shows rather than being silently dropped.
    if (points.size >= 2) {
        val endZone = segments.lastOrNull()?.zone ?: SpeedZone.fromMps(points.last().speedMps)
        addEndMarker(style, points.last().latLng, zoneColors.forZone(endZone))
    }
}

private fun addStartMarker(style: Style, position: LatLng) {
    style.addSource(GeoJsonSource(START_MARKER_SOURCE_ID, Point.fromLngLat(position.longitude, position.latitude)))
    style.addLayer(
        CircleLayer(START_MARKER_LAYER_ID, START_MARKER_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.BLACK)
        )
    )
}

private fun addEndMarker(style: Style, position: LatLng, colorArgb: Int) {
    // sdf=true treats the bitmap as a plain white-on-transparent silhouette
    // that iconColor below can retint per trip, instead of baking one fixed
    // color into the image.
    style.addImage(END_PIN_IMAGE_ID, endPinBitmap, true)
    style.addSource(GeoJsonSource(END_MARKER_SOURCE_ID, Point.fromLngLat(position.longitude, position.latitude)))
    style.addLayer(
        SymbolLayer(END_MARKER_LAYER_ID, END_MARKER_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(END_PIN_IMAGE_ID),
            PropertyFactory.iconColor(colorArgb),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
    )
}

/**
 * A simple map-pin silhouette (circular head + triangular tip), drawn once
 * in plain white on transparent. Combined with sdf=true on addImage, its
 * alpha shape gets tinted to whatever color a given trip's endpoint needs
 * rather than requiring one bitmap per possible zone color.
 */
private val endPinBitmap: Bitmap by lazy {
    val density = android.content.res.Resources.getSystem().displayMetrics.density
    val width = (PIN_WIDTH_DP * density).toInt().coerceAtLeast(1)
    val height = (PIN_HEIGHT_DP * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val headRadius = width / 2f
    val centerX = width / 2f
    val centerY = headRadius

    val pinShape = Path().apply { addCircle(centerX, centerY, headRadius, Path.Direction.CW) }
    val tip = Path().apply {
        moveTo(centerX - headRadius * 0.55f, centerY + headRadius * 0.55f)
        lineTo(centerX, height.toFloat())
        lineTo(centerX + headRadius * 0.55f, centerY + headRadius * 0.55f)
        close()
    }
    pinShape.op(tip, Path.Op.UNION)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    canvas.drawPath(pinShape, fillPaint)

    // Punch a small hole near the top so the silhouette reads as a classic
    // map pin rather than a plain teardrop.
    val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    canvas.drawCircle(centerX, centerY, headRadius * 0.4f, holePaint)

    bitmap
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

/** Small color-coded dots overlaid on the route for hard-accel/braking and speeding events (see TripEventAnalyzer). */
private fun drawEventMarkers(style: Style, events: List<RouteEventMarker>, eventColors: EventColors) {
    if (events.isEmpty()) return
    val features = events.map { marker ->
        Feature.fromGeometry(Point.fromLngLat(marker.latLng.longitude, marker.latLng.latitude)).apply {
            addStringProperty(EVENT_TYPE_PROPERTY, marker.type.name)
        }
    }
    style.addSource(GeoJsonSource(EVENT_SOURCE_ID, FeatureCollection.fromFeatures(features)))
    style.addLayer(
        CircleLayer(EVENT_LAYER_ID, EVENT_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(eventColorExpression(eventColors)),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.BLACK)
        )
    )
}

private fun eventColorExpression(eventColors: EventColors): Expression = Expression.match(
    Expression.get(EVENT_TYPE_PROPERTY),
    Expression.color(android.graphics.Color.WHITE), // fallback; should never actually show
    Expression.stop(TripEventType.HARD_ACCEL.name, Expression.color(eventColors.hardAccel)),
    Expression.stop(TripEventType.HARD_BRAKE.name, Expression.color(eventColors.hardBrake)),
    Expression.stop(TripEventType.SPEEDING.name, Expression.color(eventColors.speeding))
)

private fun frameCamera(map: MapLibreMap, points: List<RoutePoint>) {
    if (points.size < 2) {
        val only = points.firstOrNull()?.latLng ?: return
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(only, 14.0))
        return
    }
    val bounds = LatLngBounds.fromLatLngs(points.map { it.latLng })
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING_PX))
}
