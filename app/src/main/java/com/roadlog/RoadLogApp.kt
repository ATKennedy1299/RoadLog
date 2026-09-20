package com.roadlog

import android.app.Application
import com.roadlog.data.AppDatabase
import com.roadlog.data.TripRepository
import com.roadlog.data.UnitsRepository
import org.maplibre.android.MapLibre

class RoadLogApp : Application() {
    lateinit var repository: TripRepository
        private set

    lateinit var unitsRepository: UnitsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        val db = AppDatabase.getInstance(this)
        repository = TripRepository(db)
        unitsRepository = UnitsRepository(this)
    }
}
