package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.CumulativeStats
import com.roadlog.data.DistanceUnit
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import com.roadlog.data.VehicleUsageStats
import com.roadlog.data.WeeklyDistance
import com.roadlog.data.computeCumulativeStats
import com.roadlog.data.computeVehicleUsageStats
import com.roadlog.data.computeWeeklyDistancesThisMonth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Recomputes automatically whenever the trip list changes (new trip
 * completed, one deleted, etc.) since it's derived straight from the same
 * observeAllTrips() Flow the home screen uses — no separate refresh/poll.
 */
class StatsViewModel(
    repository: TripRepository,
    unitsRepository: UnitsRepository
) : ViewModel() {

    val unit: StateFlow<DistanceUnit> = unitsRepository.unit

    val stats: StateFlow<CumulativeStats> = repository.observeAllTrips()
        .map { trips -> computeCumulativeStats(trips) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CumulativeStats.EMPTY)

    val weeklyDistances: StateFlow<List<WeeklyDistance>> = repository.observeAllTrips()
        .map { trips -> computeWeeklyDistancesThisMonth(trips) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topVehicles: StateFlow<List<VehicleUsageStats>> = combine(
        repository.observeAllTrips(),
        repository.observeAllVehicleProfiles()
    ) { trips, vehicles -> computeVehicleUsageStats(trips, vehicles) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
