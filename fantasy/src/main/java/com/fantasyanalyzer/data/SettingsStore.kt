package com.fantasyanalyzer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** League settings, with the ESPN cookies encrypted using a key held in the Android Keystore. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = open(context.applicationContext)

    fun load() = Settings(
        useDemo = prefs.getBoolean(KEY_DEMO, false),
        leagueId = prefs.getString(KEY_LEAGUE, "") ?: "",
        year = prefs.getInt(KEY_YEAR, defaultSeason()),
        espnS2 = prefs.getString(KEY_S2, "") ?: "",
        swid = prefs.getString(KEY_SWID, "") ?: "",
        myTeamId = prefs.getInt(KEY_MY_TEAM, 0),
    )

    fun save(s: Settings) {
        prefs.edit()
            .putBoolean(KEY_DEMO, s.useDemo)
            .putString(KEY_LEAGUE, s.leagueId)
            .putInt(KEY_YEAR, s.year)
            .putString(KEY_S2, s.espnS2)
            .putString(KEY_SWID, s.swid)
            .putInt(KEY_MY_TEAM, s.myTeamId)
            .apply()
    }

    private companion object {
        const val FILE = "league_settings"
        const val KEY_DEMO = "use_demo"
        const val KEY_LEAGUE = "league_id"
        const val KEY_YEAR = "year"
        const val KEY_S2 = "espn_s2"
        const val KEY_SWID = "swid"
        const val KEY_MY_TEAM = "my_team"

        fun open(context: Context): SharedPreferences {
            fun create() = EncryptedSharedPreferences.create(
                FILE,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return try {
                create()
            } catch (e: Exception) {
                // The keystore key can be lost (e.g. after a device restore). The
                // saved values are unreadable then, so start over rather than crash.
                context.deleteSharedPreferences(FILE)
                create()
            }
        }
    }
}
