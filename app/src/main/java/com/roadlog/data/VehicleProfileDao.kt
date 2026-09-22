package com.roadlog.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleProfileDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: VehicleProfile): Long

    @Update
    suspend fun update(profile: VehicleProfile)

    @Delete
    suspend fun delete(profile: VehicleProfile)

    @Query("SELECT * FROM vehicle_profiles ORDER BY id ASC")
    fun observeAll(): Flow<List<VehicleProfile>>

    @Query("SELECT * FROM vehicle_profiles WHERE id = :id")
    suspend fun getById(id: Long): VehicleProfile?

    @Query("SELECT * FROM vehicle_profiles WHERE id = :id")
    fun observeById(id: Long): Flow<VehicleProfile?>
}
