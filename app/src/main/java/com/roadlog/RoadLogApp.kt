package com.roadlog

import android.app.Application
import com.roadlog.data.AppDatabase
import com.roadlog.data.RideDetectionSettings
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import com.roadlog.service.RideDetection
import org.maplibre.android.MapLibre

class RoadLogApp : Application() {
    lateinit var repository: TripRepository
        private set

    lateinit var unitsRepository: UnitsRepository
        private set

    lateinit var rideDetectionSettings: RideDetectionSettings
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        val db = AppDatabase.getInstance(this)
        repository = TripRepository(db)
        unitsRepository = UnitsRepository(this)
        rideDetectionSettings = RideDetectionSettings(this)

        // Defensive re-registration on every process start: cheap and
        // idempotent, and covers cases (e.g. a device reboot) where Play
        // Services' own persistence of the transition request is uncertain.
        if (rideDetectionSettings.enabled.value && RideDetection.hasPermission(this)) {
            RideDetection.register(this)
        }
    }
}
