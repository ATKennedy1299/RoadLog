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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.roadlog.data.FriendRequest
import com.roadlog.ui.FriendWithLocation
import com.roadlog.ui.FriendsViewModel
import com.roadlog.ui.theme.AccentGreen
import com.roadlog.ui.theme.AccentRed
import com.roadlog.ui.theme.SurfaceRaised
import com.roadlog.ui.theme.TextSecondary
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val FRIENDS_SOURCE_ID = "roadlog-friends-source"
private const val FRIENDS_LAYER_ID = "roadlog-friends-layer"
private const val FRIENDS_STYLE_URL = "https://demotiles.maplibre.org/style.json"

@Composable
fun FriendsScreen(viewModel: FriendsViewModel, onBack: () -> Unit, onSignOut: () -> Unit) {
    val incomingRequests by viewModel.incomingRequests.collectAsStateWithLifecycle()
    val friendsWithLocation by viewModel.friendsWithLocation.collectAsStateWithLifecycle()
    var emailInput by remember { mutableStateOf("") }
    var sendResultMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "‹ BACK",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.clickable(onClick = onBack)
            )
            Text(
                text = "SIGN OUT",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.clickable(onClick = onSignOut)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(text = "FRIENDS", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Spacer(Modifier.height(16.dp))

        FriendsMapView(
            friendsWithLocation = friendsWithLocation,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it; sendResultMessage = null },
                label = { Text("Friend's email") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    viewModel.sendFriendRequest(emailInput) { success ->
                        sendResultMessage = if (success) "Request sent" else "No RoadLog user with that email"
                        if (success) emailInput = ""
                    }
                },
                enabled = emailInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black)
            ) {
                Text("ADD")
            }
        }
        sendResultMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }

        if (incomingRequests.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("REQUESTS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            incomingRequests.forEach { request ->
                IncomingRequestRow(
                    request = request,
                    onAccept = { viewModel.acceptRequest(request) },
                    onDecline = { viewModel.declineRequest(request) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("YOUR FRIENDS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        if (friendsWithLocation.isEmpty()) {
            Text(
                text = "No friends yet — add one by email above.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(friendsWithLocation, key = { it.friend.uid }) { entry ->
                    FriendRow(entry)
                }
            }
        }
    }
}

@Composable
private fun IncomingRequestRow(request: FriendRequest, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(request.fromDisplayName, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(request.fromEmail, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "ACCEPT",
                color = AccentGreen,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable(onClick = onAccept)
            )
            Text(
                "DECLINE",
                color = AccentRed,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable(onClick = onDecline)
            )
        }
    }
}

@Composable
private fun FriendRow(entry: FriendWithLocation) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(entry.friend.displayName, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(entry.friend.email, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text = timeAgoLabel(entry.location?.timestampEpochMs),
            color = if (entry.location != null) AccentGreen else TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun timeAgoLabel(timestampEpochMs: Long?): String {
    if (timestampEpochMs == null) return "No location yet"
    val ageSeconds = (System.currentTimeMillis() - timestampEpochMs) / 1000
    return when {
        ageSeconds < 60 -> "Live"
        ageSeconds < 3600 -> "${ageSeconds / 60}m ago"
        ageSeconds < 86400 -> "${ageSeconds / 3600}h ago"
        else -> "${ageSeconds / 86400}d ago"
    }
}

/**
 * Minimal marker-only map for friends' last-known positions — deliberately
 * not RouteMapView, which is built around a single trip's speed-colored
 * route rather than a handful of independent points. Mirrors the same
 * MapView lifecycle handling (including calling onDestroy on permanent
 * disposal) so this doesn't reintroduce the leak fixed there.
 */
@Composable
private fun FriendsMapView(friendsWithLocation: List<FriendWithLocation>, modifier: Modifier = Modifier) {
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
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var hasFramedCamera by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                view.getMapAsync { readyMap ->
                    map = readyMap
                    if (!styleLoaded) {
                        readyMap.setStyle(FRIENDS_STYLE_URL) { styleLoaded = true }
                    }
                }
            }
        )

        val currentMap = map
        if (currentMap != null && styleLoaded) {
            LaunchedEffect(friendsWithLocation, currentMap) {
                updateFriendMarkers(currentMap, friendsWithLocation)

                val positions = friendsWithLocation.mapNotNull {
                    it.location?.let { loc -> LatLng(loc.latitude, loc.longitude) }
                }
                // Only auto-frame the first time a position appears — not on
                // every subsequent update, which would make the map jump
                // around every time a friend's location refreshes.
                if (!hasFramedCamera && positions.isNotEmpty()) {
                    hasFramedCamera = true
                    if (positions.size == 1) {
                        currentMap.moveCamera(CameraUpdateFactory.newLatLngZoom(positions.first(), 13.0))
                    } else {
                        currentMap.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.fromLatLngs(positions), 80))
                    }
                }
            }
        }
    }
}

private fun updateFriendMarkers(map: MapLibreMap, friendsWithLocation: List<FriendWithLocation>) {
    val style = map.style ?: return
    val features = friendsWithLocation.mapNotNull { entry ->
        val loc = entry.location ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
    }
    val collection = FeatureCollection.fromFeatures(features)
    val existingSource = style.getSourceAs<GeoJsonSource>(FRIENDS_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setGeoJson(collection)
    } else {
        style.addSource(GeoJsonSource(FRIENDS_SOURCE_ID, collection))
        style.addLayer(
            CircleLayer(FRIENDS_LAYER_ID, FRIENDS_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(android.graphics.Color.parseColor("#00E5A0")),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(android.graphics.Color.BLACK)
            )
        )
    }
}
