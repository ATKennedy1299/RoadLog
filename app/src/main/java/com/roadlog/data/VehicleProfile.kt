package com.roadlog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class VehicleType { CAR, MOTORCYCLE }

@Entity(tableName = "vehicle_profiles")
data class VehicleProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: VehicleType,
    val colorHex: String
)

/** Seeded on first DB creation so trips always have somewhere to attach in Phase 1. */
object DefaultVehicleProfile {
    const val ID = 1L
    val profile = VehicleProfile(
        id = ID,
        name = "Default Vehicle",
        type = VehicleType.CAR,
        colorHex = "#00E5A0"
    )
}
