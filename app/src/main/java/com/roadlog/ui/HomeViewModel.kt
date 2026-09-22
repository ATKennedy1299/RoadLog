package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.DistanceUnit
import com.roadlog.data.RideDetectionSettings
import com.roadlog.data.Trip
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: TripRepository,
    private val unitsRepository: UnitsRepository,
    rideDetectionSettings: RideDetectionSettings
) : ViewModel() {

    val activeTrip: StateFlow<Trip?> = repository.observeActiveTrip()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tripHistory: StateFlow<List<Trip>> = repository.observeAllTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unit: StateFlow<DistanceUnit> = unitsRepository.unit

    // Read-only here — the actual enable/disable side effects (permission
    // request, registering with Play Services) need an Activity, so
    // MainActivity mutates RideDetectionSettings directly and this just
    // reflects the resulting state back into the UI.
    val autoDetectEnabled: StateFlow<Boolean> = rideDetectionSettings.enabled

    fun toggleUnit() = unitsRepository.toggle()

    /** Not offered in the UI for the trip currently being recorded. */
    fun deleteTrip(trip: Trip) {
        viewModelScope.launch { repository.deleteTrip(trip) }
    }
}
