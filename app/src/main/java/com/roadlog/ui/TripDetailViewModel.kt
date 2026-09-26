package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.DistanceUnit
import com.roadlog.data.LocationPoint
import com.roadlog.data.Trip
import com.roadlog.data.TripRepository
import com.roadlog.data.TripStatus
import com.roadlog.data.UnitsRepository
import com.roadlog.data.VehicleProfile
import com.roadlog.util.TripEvent
import com.roadlog.util.TripEventAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TripDetailViewModel(
    private val tripId: Long,
    private val repository: TripRepository,
    unitsRepository: UnitsRepository
) : ViewModel() {

    val trip: StateFlow<Trip?> = repository.observeTrip(tripId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val unit: StateFlow<DistanceUnit> = unitsRepository.unit

    val vehicles: StateFlow<List<VehicleProfile>> = repository.observeAllVehicleProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _vehicleProfile = MutableStateFlow<VehicleProfile?>(null)
    val vehicleProfile: StateFlow<VehicleProfile?> = _vehicleProfile.asStateFlow()

    /**
     * A vehicle to suggest re-categorizing this trip as, based on
     * RideMotionClassifier's car/motorcycle guess — only offered when
     * exactly one vehicle in the garage matches the detected type (no
     * attempt to disambiguate between two motorcycles or two cars, which
     * this signal can't do), it isn't already the trip's assigned vehicle,
     * and the trip is done recording. Never applied automatically — see
     * changeVehicle(), which the UI calls only once the user accepts it.
     */
    val suggestedVehicle: StateFlow<VehicleProfile?> = combine(trip, vehicles) { currentTrip, allVehicles ->
        if (currentTrip == null || currentTrip.status != TripStatus.COMPLETED) return@combine null
        val detectedType = currentTrip.detectedVehicleType ?: return@combine null
        val onlyMatch = allVehicles.filter { it.type == detectedType }.singleOrNull() ?: return@combine null
        onlyMatch.takeIf { it.id != currentTrip.vehicleProfileId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Loaded once: a completed trip's points never change (aside from the
    // speed-limit enrichment below), so this doesn't need to be a reactive
    // Flow like the fields above.
    private val _routePoints = MutableStateFlow<List<LocationPoint>>(emptyList())
    val routePoints: StateFlow<List<LocationPoint>> = _routePoints.asStateFlow()

    /** Hard-accel/braking and speeding dots derived from the current route points — see TripEventAnalyzer. */
    val tripEvents: StateFlow<List<TripEvent>> = routePoints
        .map { TripEventAnalyzer.detectEvents(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Guards enrichSpeedLimitsIfNeeded() so it's only kicked off once per
    // ViewModel instance — trip's Flow can re-emit for unrelated reasons
    // (e.g. changeVehicle()), and there's no reason to retry mid-session
    // just because something else about the trip changed.
    private var speedLimitEnrichmentStarted = false

    init {
        viewModelScope.launch {
            trip.collect { current ->
                _vehicleProfile.value = current?.let { repository.getVehicleProfile(it.vehicleProfileId) }
                if (current != null && current.status == TripStatus.COMPLETED && !speedLimitEnrichmentStarted) {
                    speedLimitEnrichmentStarted = true
                    // Best-effort: no network, or Overpass being unavailable,
                    // just leaves speeding events out of this view — the
                    // trip stays unmarked so a later open retries.
                    runCatching { repository.enrichSpeedLimitsIfNeeded(tripId) }
                        .onSuccess { _routePoints.value = repository.getRoutePoints(tripId) }
                }
            }
        }
        viewModelScope.launch {
            _routePoints.value = repository.getRoutePoints(tripId)
        }
    }

    fun deleteTrip(onDeleted: () -> Unit) {
        val current = trip.value ?: return
        viewModelScope.launch {
            repository.deleteTrip(current)
            onDeleted()
        }
    }

    /** Only meaningful for a completed trip — an active trip's vehicle is set at start. */
    fun changeVehicle(vehicleProfileId: Long) {
        viewModelScope.launch { repository.updateTripVehicle(tripId, vehicleProfileId) }
    }
}
