package com.roadlog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Bumping `version` requires adding a Room Migration in .addMigrations()
// below — without one, Room throws on the user's next launch after an
// update rather than silently deleting their trips. Never reach for
// fallbackToDestructiveMigration() as the fix for that; it wipes the DB.
@Database(
    entities = [Trip::class, LocationPoint::class, VehicleProfile::class],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun locationPointDao(): LocationPointDao
    abstract fun vehicleProfileDao(): VehicleProfileDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN isAutoDetected INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicle_profiles ADD COLUMN make TEXT")
                db.execSQL("ALTER TABLE vehicle_profiles ADD COLUMN model TEXT")
                db.execSQL("ALTER TABLE vehicle_profiles ADD COLUMN year INTEGER")
                db.execSQL("ALTER TABLE vehicle_profiles ADD COLUMN photoPath TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN detectedVehicleType TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN motionDebugInfo TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE location_points ADD COLUMN startsNewSegment INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_points ADD COLUMN speedLimitMps REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN speedLimitsFetched INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN speedLimitDebugInfo TEXT")
            }
        }

        // One-time reset while the speed-limit feature is still being
        // validated: any trip already marked speedLimitsFetched from before
        // speedLimitDebugInfo existed (e.g. a quiet 0-match "success" under
        // an earlier build) is stuck permanently — enrichSpeedLimitsIfNeeded
        // returns immediately on speedLimitsFetched=true and never touches
        // debugInfo, so those trips would never show a debug line or retry,
        // even after a real fix like SpeedLimitLookup's User-Agent header.
        // This forces every trip to get one fresh, fully-instrumented
        // attempt on its next open.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE trips SET speedLimitsFetched = 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "roadlog.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9
                )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Seed the default vehicle profile so Phase 1 trips
                            // always have a valid vehicleProfileId to attach to.
                            CoroutineScope(Dispatchers.IO).launch {
                                INSTANCE?.vehicleProfileDao()?.insert(DefaultVehicleProfile.profile)
                            }
                        }
                    }).build().also { INSTANCE = it }
            }
        }
    }
}
