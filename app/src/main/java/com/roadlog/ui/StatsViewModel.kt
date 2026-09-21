package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.CumulativeStats
import com.roadlog.data.DistanceUnit
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import com.roadlog.data.computeCumulativeStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
}
