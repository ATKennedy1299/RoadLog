package com.roadlog.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists whether automatic ride detection is turned on. Off by default —
 * it requires the ACTIVITY_RECOGNITION permission and runs a background
 * broadcast receiver, so it's opt-in rather than something a fresh install
 * enables silently.
 */
class RideDetectionSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    companion object {
        private const val PREFS_NAME = "roadlog_settings"
        private const val KEY_ENABLED = "auto_ride_detection_enabled"
    }
}
