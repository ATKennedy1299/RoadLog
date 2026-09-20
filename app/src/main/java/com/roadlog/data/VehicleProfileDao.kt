package com.roadlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleProfileDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: VehicleProfile): Long

    @Query("SELECT * FROM vehicle_profiles ORDER BY id ASC")
    fun observeAll(): Flow<List<VehicleProfile>>

    @Query("SELECT * FROM vehicle_profiles WHERE id = :id")
    suspend fun getById(id: Long): VehicleProfile?
}
