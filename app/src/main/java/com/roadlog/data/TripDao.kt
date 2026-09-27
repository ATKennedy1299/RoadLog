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

    @Query("SELECT vehicleProfileId FROM trips ORDER BY startTimeEpochMs DESC LIMIT 1")
    suspend fun getMostRecentVehicleProfileId(): Long?

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

    @Query(
        """
        UPDATE trips
        SET status = 'COMPLETED',
            endTimeEpochMs = :endTime,
            detectedVehicleType = :detectedVehicleType,
            motionDebugInfo = :motionDebugInfo
        WHERE id = :tripId
        """
    )
    suspend fun completeTrip(
        tripId: Long,
        endTime: Long,
        detectedVehicleType: VehicleType?,
        motionDebugInfo: String?
    )

    @Query("UPDATE trips SET vehicleProfileId = :vehicleProfileId WHERE id = :tripId")
    suspend fun updateVehicleProfileId(tripId: Long, vehicleProfileId: Long)

    @Query(
        """
        UPDATE trips
        SET speedLimitsFetched = :fetched,
            speedLimitDebugInfo = :debugInfo
        WHERE id = :tripId
        """
    )
    suspend fun recordSpeedLimitFetch(tripId: Long, fetched: Boolean, debugInfo: String?)

    @Delete
    suspend fun delete(trip: Trip)
}
