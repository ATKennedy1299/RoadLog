package com.fantasyanalyzer.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fantasyanalyzer.data.Settings
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Starts and stops the background score checks to match the user's settings. */
object AlertScheduler {
    private const val PERIODIC = "score-alerts"
    private const val NOW = "score-alerts-now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun apply(context: Context, s: Settings) {
        val wm = WorkManager.getInstance(context)
        if (s.alerts.enabled && !s.useDemo && s.hasLeague) {
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ScoreAlertWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
            )
        } else {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(NOW)
        }
    }

    /**
     * Runs a check right away. Started while the app is on screen, it's allowed
     * to switch to every-minute checks for the rest of the live games.
     */
    fun runNow(context: Context, s: Settings) {
        if (!s.alerts.enabled || s.useDemo || !s.hasLeague) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ScoreAlertWorker>().setConstraints(network).build(),
        )
    }

    /** Whether another score check is already running (so two don't double-notify). */
    fun otherRunActive(context: Context, self: UUID): Boolean = try {
        val wm = WorkManager.getInstance(context)
        (wm.getWorkInfosForUniqueWork(PERIODIC).get() + wm.getWorkInfosForUniqueWork(NOW).get())
            .any { it.id != self && it.state == WorkInfo.State.RUNNING }
    } catch (e: Exception) {
        false
    }
}
