package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.TripRepository
import com.roadlog.data.VehicleProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class GarageViewModel(repository: TripRepository) : ViewModel() {
    val vehicles: StateFlow<List<VehicleProfile>> = repository.observeAllVehicleProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
