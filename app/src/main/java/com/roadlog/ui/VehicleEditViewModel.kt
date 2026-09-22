package com.roadlog.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.TripRepository
import com.roadlog.data.VehiclePhotoStore
import com.roadlog.data.VehicleProfile
import com.roadlog.data.VehicleType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VehicleFormState(
    val name: String = "",
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val type: VehicleType = VehicleType.CAR,
    val colorHex: String = "#00E5A0",
    val photoPath: String? = null
)

/** vehicleId == 0L means "new vehicle" — Room's autoGenerate assigns a real id on insert. */
class VehicleEditViewModel(
    private val vehicleId: Long,
    private val repository: TripRepository,
    private val photoStore: VehiclePhotoStore
) : ViewModel() {

    val isNew: Boolean = vehicleId == 0L

    private val _form = MutableStateFlow(VehicleFormState())
    val form: StateFlow<VehicleFormState> = _form.asStateFlow()

    // Deleting the last remaining vehicle would leave existing/future trips
    // with no valid profile to attach to, so the screen hides delete until
    // there's at least one other vehicle to fall back to.
    val vehicleCount: StateFlow<Int> = repository.observeAllVehicleProfiles()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    init {
        if (!isNew) {
            viewModelScope.launch {
                repository.getVehicleProfile(vehicleId)?.let { profile ->
                    _form.value = VehicleFormState(
                        name = profile.name,
                        make = profile.make.orEmpty(),
                        model = profile.model.orEmpty(),
                        year = profile.year?.toString().orEmpty(),
                        type = profile.type,
                        colorHex = profile.colorHex,
                        photoPath = profile.photoPath
                    )
                }
            }
        }
    }

    fun updateName(value: String) { _form.value = _form.value.copy(name = value) }
    fun updateMake(value: String) { _form.value = _form.value.copy(make = value) }
    fun updateModel(value: String) { _form.value = _form.value.copy(model = value) }
    fun updateYear(value: String) {
        _form.value = _form.value.copy(year = value.filter { it.isDigit() }.take(4))
    }
    fun updateType(value: VehicleType) { _form.value = _form.value.copy(type = value) }
    fun updateColor(value: String) { _form.value = _form.value.copy(colorHex = value) }

    fun onPhotoPicked(uri: Uri) {
        viewModelScope.launch {
            val oldPath = _form.value.photoPath
            val newPath = photoStore.savePhoto(uri)
            _form.value = _form.value.copy(photoPath = newPath)
            photoStore.deletePhoto(oldPath)
        }
    }

    fun save(onSaved: () -> Unit) {
        val current = _form.value
        if (current.name.isBlank()) return
        viewModelScope.launch {
            val profile = VehicleProfile(
                id = vehicleId,
                name = current.name.trim(),
                type = current.type,
                colorHex = current.colorHex,
                make = current.make.trim().ifBlank { null },
                model = current.model.trim().ifBlank { null },
                year = current.year.toIntOrNull(),
                photoPath = current.photoPath
            )
            if (isNew) repository.addVehicleProfile(profile) else repository.updateVehicleProfile(profile)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        if (isNew) return
        viewModelScope.launch {
            val profile = repository.getVehicleProfile(vehicleId) ?: return@launch
            repository.deleteVehicleProfile(profile)
            photoStore.deletePhoto(profile.photoPath)
            onDeleted()
        }
    }
}
