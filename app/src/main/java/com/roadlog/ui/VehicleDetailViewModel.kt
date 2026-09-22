package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.CumulativeStats
import com.roadlog.data.DistanceUnit
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import com.roadlog.data.VehicleProfile
import com.roadlog.data.computeCumulativeStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class VehicleDetailViewModel(
    vehicleId: Long,
    repository: TripRepository,
    unitsRepository: UnitsRepository
) : ViewModel() {

    val vehicle: StateFlow<VehicleProfile?> = repository.observeVehicleProfile(vehicleId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val unit: StateFlow<DistanceUnit> = unitsRepository.unit

    val stats: StateFlow<CumulativeStats> = repository.observeAllTrips()
        .map { trips -> computeCumulativeStats(trips.filter { it.vehicleProfileId == vehicleId }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CumulativeStats.EMPTY)
}
