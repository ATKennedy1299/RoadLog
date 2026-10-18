package com.fantasyanalyzer.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fantasyanalyzer.R
import com.fantasyanalyzer.data.GameAlert
import com.fantasyanalyzer.ui.MainActivity

/** Posts game-day notifications. */
object AlertNotifier {
    const val SCORING_CHANNEL = "scoring"
    const val LIVE_CHANNEL = "live"
    const val LIVE_ID = 4201

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SCORING_CHANNEL, "Scoring alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Scoring plays, lead changes and results for your matchup"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(LIVE_CHANNEL, "Live scoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app checks scores every minute during games"
            },
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // checked by canPost
    fun post(context: Context, alerts: List<GameAlert>) {
        if (alerts.isEmpty() || !canPost(context)) return
        ensureChannels(context)
        val nm = NotificationManagerCompat.from(context)
        alerts.forEach { a ->
            val n = NotificationCompat.Builder(context, SCORING_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_football)
                .setContentTitle(a.title)
                .setContentText(a.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            nm.notify(a.key.hashCode(), n)
        }
    }

    fun liveNotification(context: Context) = NotificationCompat.Builder(context, LIVE_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_football)
        .setContentTitle("Live scoring")
        .setContentText("Watching your matchup for scoring plays")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(openApp(context))
        .build()
}
