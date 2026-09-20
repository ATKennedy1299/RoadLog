package com.roadlog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// Free public MapLibre demo style — no API key needed. Swap for a hosted
// style/tile provider before shipping to production.
private const val STYLE_URL = "https://demotiles.maplibre.org/style.json"
private const val ROUTE_SOURCE_ID = "roadlog-route-source"
private const val ROUTE_LAYER_ID = "roadlog-route-layer"
private const val CAMERA_PADDING_PX = 64

/**
 * Renders a route as a polyline framed to fit its bounds. [onMapReady] fires
 * exactly once per composable instance (not once per style (re)load), so
 * callers can safely attach map listeners without risking duplicates.
 */
@Composable
fun RouteMapView(
    points: List<LatLng>,
    modifier: Modifier = Modifier,
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val routeColorArgb = remember { AccentGreen.toArgb() }
    // Style application is a one-time setup for this MapView instance (not
    // re-run per recomposition) — otherwise every unrelated recomposition of
    // the containing screen would reload the style/tiles from the network.
    var styleApplied by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            if (!styleApplied) {
                styleApplied = true
                view.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                        drawRoute(style, points, routeColorArgb)
                        frameCamera(map, points)
                        onMapReady(map)
                    }
                }
            }
        }
    )
}

private fun drawRoute(style: Style, points: List<LatLng>, routeColorArgb: Int) {
    if (style.getSource(ROUTE_SOURCE_ID) != null) return // already drawn for this style load

    val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, Feature.fromGeometry(line)))
    style.addLayer(
        LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(routeColorArgb),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
}

private fun frameCamera(map: MapLibreMap, points: List<LatLng>) {
    if (points.size < 2) {
        val only = points.firstOrNull() ?: return
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(only, 14.0))
        return
    }
    val bounds = LatLngBounds.fromLatLngs(points)
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING_PX))
}
