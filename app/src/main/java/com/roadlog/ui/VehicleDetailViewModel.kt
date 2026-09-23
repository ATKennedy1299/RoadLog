package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.CumulativeStats
import com.roadlog.data.DistanceUnit
import com.roadlog.data.MonthlyDistance
import com.roadlog.data.Trip
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import com.roadlog.data.VehicleProfile
import com.roadlog.data.computeCumulativeStats
import com.roadlog.data.computeMonthlyDistances
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

    private val vehicleTripsFlow = repository.observeAllTrips()
        .map { trips -> trips.filter { it.vehicleProfileId == vehicleId } }

    val vehicleTrips: StateFlow<List<Trip>> = vehicleTripsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<CumulativeStats> = vehicleTripsFlow
        .map { trips -> computeCumulativeStats(trips) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CumulativeStats.EMPTY)

    val monthlyDistances: StateFlow<List<MonthlyDistance>> = vehicleTripsFlow
        .map { trips -> computeMonthlyDistances(trips) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
