package com.roadlog

import android.app.Application
import com.roadlog.data.AppDatabase
import com.roadlog.data.AuthRepository
import com.roadlog.data.FriendsRepository
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

    // Live friend-location MVP. FirebaseApp itself is auto-initialized by a
    // manifest-declared ContentProvider before this runs, from the
    // google-services.json dropped into app/ — no explicit init call needed
    // here. IMPORTANT: there are no Firebase Security Rules restricting the
    // Realtime Database paths these repositories use yet (see
    // FriendsRepository's doc comment) — anyone who knew another user's uid
    // could currently read or write their data. Fine for two known personal
    // devices; needs rules written before this goes any further.
    lateinit var authRepository: AuthRepository
        private set

    lateinit var friendsRepository: FriendsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        val db = AppDatabase.getInstance(this)
        repository = TripRepository(db)
        unitsRepository = UnitsRepository(this)
        rideDetectionSettings = RideDetectionSettings(this)
        authRepository = AuthRepository()
        friendsRepository = FriendsRepository(authRepository)

        // Defensive re-registration on every process start: cheap and
        // idempotent, and covers cases (e.g. a device reboot) where Play
        // Services' own persistence of the transition request is uncertain.
        if (rideDetectionSettings.enabled.value && RideDetection.hasPermission(this)) {
            RideDetection.register(this)
        }
    }
}
