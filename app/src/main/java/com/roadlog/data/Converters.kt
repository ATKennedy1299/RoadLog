package com.roadlog.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromTripStatus(value: TripStatus): String = value.name

    @TypeConverter
    fun toTripStatus(value: String): TripStatus = TripStatus.valueOf(value)

    @TypeConverter
    fun fromVehicleType(value: VehicleType): String = value.name

    @TypeConverter
    fun toVehicleType(value: String): VehicleType = VehicleType.valueOf(value)

    @TypeConverter
    fun fromSpeedSource(value: SpeedSource): String = value.name

    @TypeConverter
    fun toSpeedSource(value: String): SpeedSource = SpeedSource.valueOf(value)
}
