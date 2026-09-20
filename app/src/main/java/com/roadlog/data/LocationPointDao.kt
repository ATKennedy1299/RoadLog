package com.roadlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationPointDao {

    @Insert
    suspend fun insert(point: LocationPoint): Long

    @Query("SELECT * FROM location_points WHERE tripId = :tripId ORDER BY timestampEpochMs ASC")
    suspend fun getPointsForTrip(tripId: Long): List<LocationPoint>

    @Query("SELECT * FROM location_points WHERE tripId = :tripId ORDER BY timestampEpochMs ASC")
    fun observePointsForTrip(tripId: Long): Flow<List<LocationPoint>>

    /** Route/replay source: excludes GpsFilter-flagged jumps so the drawn path doesn't zigzag. */
    @Query(
        """
        SELECT * FROM location_points
        WHERE tripId = :tripId AND isFiltered = 0
        ORDER BY timestampEpochMs ASC
        """
    )
    suspend fun getAcceptedPointsForTrip(tripId: Long): List<LocationPoint>

    /** Most recent *accepted* (non-filtered) point — used by GpsFilter for jump detection. */
    @Query(
        """
        SELECT * FROM location_points
        WHERE tripId = :tripId AND isFiltered = 0
        ORDER BY timestampEpochMs DESC LIMIT 1
        """
    )
    suspend fun getLastAcceptedPoint(tripId: Long): LocationPoint?

    @Query("SELECT COUNT(*) FROM location_points WHERE tripId = :tripId AND isFiltered = 0")
    suspend fun getAcceptedPointCount(tripId: Long): Int
}
