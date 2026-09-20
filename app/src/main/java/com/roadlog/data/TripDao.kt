package com.roadlog.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: Trip): Long

    @Update
    suspend fun update(trip: Trip)

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveTrip(): Trip?

    @Query("SELECT * FROM trips WHERE status = 'ACTIVE' LIMIT 1")
    fun observeActiveTrip(): Flow<Trip?>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getById(tripId: Long): Trip?

    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun observeById(tripId: Long): Flow<Trip?>

    @Query("SELECT * FROM trips ORDER BY startTimeEpochMs DESC")
    fun observeAllTrips(): Flow<List<Trip>>

    @Query(
        """
        UPDATE trips
        SET distanceMeters = :distanceMeters,
            maxSpeedMps = :maxSpeedMps,
            pointCount = :pointCount
        WHERE id = :tripId
        """
    )
    suspend fun updateAggregates(
        tripId: Long,
        distanceMeters: Double,
        maxSpeedMps: Double,
        pointCount: Int
    )

    @Query("UPDATE trips SET status = 'COMPLETED', endTimeEpochMs = :endTime WHERE id = :tripId")
    suspend fun completeTrip(tripId: Long, endTime: Long)

    @Delete
    suspend fun delete(trip: Trip)
}
