package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.DistanceUnit
import com.roadlog.data.LocationPoint
import com.roadlog.data.Trip
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import com.roadlog.data.VehicleProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _vehicleProfile = MutableStateFlow<VehicleProfile?>(null)
    val vehicleProfile: StateFlow<VehicleProfile?> = _vehicleProfile.asStateFlow()

    // Loaded once: a completed trip's points never change, so this doesn't
    // need to be a reactive Flow like the fields above.
    private val _routePoints = MutableStateFlow<List<LocationPoint>>(emptyList())
    val routePoints: StateFlow<List<LocationPoint>> = _routePoints.asStateFlow()

    init {
        viewModelScope.launch {
            trip.collect { current ->
                _vehicleProfile.value = current?.let { repository.getVehicleProfile(it.vehicleProfileId) }
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
}
