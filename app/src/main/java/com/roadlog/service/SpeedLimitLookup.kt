package com.roadlog.service

import com.roadlog.data.LocationPoint
import com.roadlog.util.DistanceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up posted speed limits for a trip's recorded points from
 * OpenStreetMap's public Overpass API, so TripEventAnalyzer's speeding
 * detection has something to compare against. Coverage is best-effort and
 * varies a lot by road/region — a point near a road with no `maxspeed` tag
 * in OSM (common on minor/residential US roads) simply gets no limit, which
 * is the intended "if available" behavior, not a failure.
 *
 * This is deliberately a one-shot lookup run when a trip is first viewed
 * (see TripRepository.enrichSpeedLimitsIfNeeded), not something queried
 * live during recording — Overpass isn't fast or reliable enough for
 * per-fix use, and there's no reason to burn battery/data trying.
 */
object SpeedLimitLookup {

    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"

    // Pad the trip's bounding box so roads just outside the recorded route
    // (e.g. the far side of an intersection) are still considered.
    private const val BBOX_PADDING_DEG = 0.01 // roughly 1km at mid latitudes

    // A fix more than this far from the nearest tagged road isn't
    // confidently "on" it — leave its limit unknown rather than guess.
    private const val MAX_MATCH_DISTANCE_METERS = 25.0

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 25_000

    private data class RoadSegment(
        val startLat: Double, val startLon: Double,
        val endLat: Double, val endLon: Double,
        val limitMps: Float
    )

    /**
     * [segmentCount] and [taggedWayCount] exist purely for
     * Trip.speedLimitDebugInfo — without them, "0 speeding events" is
     * indistinguishable from "no roads near this trip have a maxspeed tag in
     * OSM" vs. "roads were tagged, but this trip's points didn't fall within
     * MAX_MATCH_DISTANCE_METERS of any of them" vs. "the request itself
     * failed." All three look identical from the UI otherwise.
     */
    data class LookupResult(
        val limitsByPointId: Map<Long, Float>,
        val taggedWayCount: Int,
        val segmentCount: Int
    )

    /**
     * Matches whichever of [points] fall within [MAX_MATCH_DISTANCE_METERS]
     * of a tagged road to that road's posted limit (m/s). Points with no
     * nearby match, or a maxspeed value that doesn't parse (e.g. "national",
     * "walk"), are simply left out rather than guessed at. Throws on
     * network/parsing failure so the caller can leave the trip unmarked and
     * retry on a later view.
     */
    suspend fun lookup(points: List<LocationPoint>): LookupResult = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext LookupResult(emptyMap(), 0, 0)

        var minLat = points[0].latitude
        var maxLat = points[0].latitude
        var minLon = points[0].longitude
        var maxLon = points[0].longitude
        for (point in points) {
            if (point.latitude < minLat) minLat = point.latitude
            if (point.latitude > maxLat) maxLat = point.latitude
            if (point.longitude < minLon) minLon = point.longitude
            if (point.longitude > maxLon) maxLon = point.longitude
        }
        minLat -= BBOX_PADDING_DEG
        maxLat += BBOX_PADDING_DEG
        minLon -= BBOX_PADDING_DEG
        maxLon += BBOX_PADDING_DEG

        val query = "[out:json][timeout:20];way[\"maxspeed\"]($minLat,$minLon,$maxLat,$maxLon);out geom;"
        val (segments, taggedWayCount) = parseSegments(postOverpassQuery(query))
        if (segments.isEmpty()) return@withContext LookupResult(emptyMap(), taggedWayCount, 0)

        val result = mutableMapOf<Long, Float>()
        for (point in points) {
            val match = nearestSegment(point.latitude, point.longitude, segments) ?: continue
            result[point.id] = match.limitMps
        }
        LookupResult(result, taggedWayCount, segments.size)
    }

    private fun postOverpassQuery(query: String): String {
        val connection = URL(OVERPASS_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write("data=" + URLEncoder.encode(query, "UTF-8"))
            }
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Overpass returned HTTP ${connection.responseCode}"
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Returns the parsed segments plus how many ways had a maxspeed tag that parsed at all, for debugging. */
    private fun parseSegments(responseJson: String): Pair<List<RoadSegment>, Int> {
        val elements = JSONObject(responseJson).optJSONArray("elements") ?: return emptyList<RoadSegment>() to 0
        val segments = mutableListOf<RoadSegment>()
        var taggedWayCount = 0
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            val limitMps = parseMaxSpeed(tags.optString("maxspeed", "")) ?: continue
            taggedWayCount++
            val geometry = element.optJSONArray("geometry") ?: continue

            var prevLat: Double? = null
            var prevLon: Double? = null
            for (g in 0 until geometry.length()) {
                val node = geometry.optJSONObject(g) ?: continue
                val lat = node.optDouble("lat", Double.NaN)
                val lon = node.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val fromLat = prevLat
                val fromLon = prevLon
                if (fromLat != null && fromLon != null) {
                    segments += RoadSegment(fromLat, fromLon, lat, lon, limitMps)
                }
                prevLat = lat
                prevLon = lon
            }
        }
        return segments to taggedWayCount
    }

    /**
     * Parses OSM's free-form `maxspeed` tag into m/s. Handles the two forms
     * that actually show up on driveable roads — a plain number (km/h, the
     * OSM default) or "<number> mph" (used explicitly in the US/UK) — and
     * returns null for anything else (e.g. "national", "signals", "walk"),
     * which the caller correctly treats as "no usable limit here."
     */
    private fun parseMaxSpeed(raw: String): Float? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty()) return null
        val mphMatch = Regex("""^(\d+(\.\d+)?)\s*mph$""").find(trimmed)
        if (mphMatch != null) {
            return mphMatch.groupValues[1].toFloat() * 0.44704f
        }
        val kmh = trimmed.toFloatOrNull() ?: return null
        return kmh / 3.6f
    }

    private fun nearestSegment(lat: Double, lon: Double, segments: List<RoadSegment>): RoadSegment? {
        var best: RoadSegment? = null
        var bestDistance = MAX_MATCH_DISTANCE_METERS
        for (segment in segments) {
            val distance = DistanceUtils.pointToSegmentMeters(
                lat, lon, segment.startLat, segment.startLon, segment.endLat, segment.endLon
            )
            if (distance < bestDistance) {
                bestDistance = distance
                best = segment
            }
        }
        return best
    }
}
