package com.roadlog

import android.app.Application
import com.roadlog.data.AppDatabase
import com.roadlog.data.TripRepository

class RoadLogApp : Application() {
    lateinit var repository: TripRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        repository = TripRepository(db)
    }
}
