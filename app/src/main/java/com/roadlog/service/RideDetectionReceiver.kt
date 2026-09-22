package com.roadlog.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Receives STILL enter/exit transitions from Google's Activity Recognition
 * API — a battery-cheap, event-driven signal for "motion probably
 * started/stopped" — and forwards them to LocationTrackingService, which
 * does the real (GPS-speed-based) confirmation of whether a ride is
 * actually happening. Declared in the manifest rather than registered at
 * runtime, so it keeps receiving transitions even if the app process isn't
 * currently alive.
 */
class RideDetectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return

        for (event in result.transitionEvents) {
            if (event.activityType != DetectedActivity.STILL) continue
            val action = when (event.transitionType) {
                // No longer still — treat as "maybe a ride is starting" and
                // let the service confirm via actual GPS speed.
                ActivityTransition.ACTIVITY_TRANSITION_EXIT -> LocationTrackingService.ACTION_BEGIN_RIDE_CANDIDATE
                // Motion stopped — either cancel an unconfirmed candidate or
                // start the grace period before auto-finalizing a trip.
                ActivityTransition.ACTIVITY_TRANSITION_ENTER -> LocationTrackingService.ACTION_MOTION_STOPPED
                else -> null
            } ?: continue

            val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}

/** Registers/unregisters this app's interest in STILL transitions with Play Services. */
object RideDetection {

    fun hasPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // granted at install time via the pre-Q com.google.android.gms permission
        }

    fun register(context: Context) {
        if (!hasPermission(context)) return
        val request = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )
        )
        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(request, pendingIntent(context))
    }

    fun unregister(context: Context) {
        ActivityRecognition.getClient(context)
            .removeActivityTransitionUpdates(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RideDetectionReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private const val REQUEST_CODE = 4201
}
