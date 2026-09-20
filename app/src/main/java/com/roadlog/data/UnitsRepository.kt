package com.roadlog.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's preferred display unit (metric/imperial) in
 * SharedPreferences and exposes it as a StateFlow so both the Compose UI
 * and the tracking service's notification read the same live value.
 */
class UnitsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _unit = MutableStateFlow(loadUnit())
    val unit: StateFlow<DistanceUnit> = _unit.asStateFlow()

    fun setUnit(unit: DistanceUnit) {
        prefs.edit().putString(KEY_UNIT, unit.name).apply()
        _unit.value = unit
    }

    fun toggle() {
        setUnit(if (_unit.value == DistanceUnit.METRIC) DistanceUnit.IMPERIAL else DistanceUnit.METRIC)
    }

    private fun loadUnit(): DistanceUnit {
        val stored = prefs.getString(KEY_UNIT, null) ?: return DistanceUnit.METRIC
        return runCatching { DistanceUnit.valueOf(stored) }.getOrDefault(DistanceUnit.METRIC)
    }

    companion object {
        private const val PREFS_NAME = "roadlog_settings"
        private const val KEY_UNIT = "distance_unit"
    }
}
