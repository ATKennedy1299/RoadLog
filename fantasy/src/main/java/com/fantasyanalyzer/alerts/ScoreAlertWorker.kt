package com.fantasyanalyzer.alerts

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.fantasyanalyzer.data.AlertEngine
import com.fantasyanalyzer.data.AlertSnapshot
import com.fantasyanalyzer.data.EspnClient
import com.fantasyanalyzer.data.EspnException
import com.fantasyanalyzer.data.EspnLogin
import com.fantasyanalyzer.data.EspnParser
import com.fantasyanalyzer.data.FileJsonCache
import com.fantasyanalyzer.data.NflGame
import com.fantasyanalyzer.data.Settings
import com.fantasyanalyzer.data.SettingsStore
import com.fantasyanalyzer.ui.BrowserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Checks the user's matchup and posts notifications for what changed. Runs
 * every 15 minutes; during live games with instant alerts on, it keeps going
 * every minute as a foreground job (Android may refuse that while the app is
 * closed, in which case it falls back to the 15-minute checks).
 */
class ScoreAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val s = SettingsStore(applicationContext).load()
        val a = s.alerts
        if (!a.enabled || s.useDemo || !s.hasLeague) return Result.success()
        if (AlertScheduler.otherRunActive(applicationContext, id)) return Result.success()
        var foreground = false
        repeat(MAX_MINUTES) {
            val live = try {
                checkOnce(s)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!live || !a.instant) return Result.success()
            if (!foreground) {
                foreground = try {
                    setForeground(foregroundInfo())
                    true
                } catch (e: Exception) {
                    false // not allowed from the background right now: the 15-minute checks carry on
                }
                if (!foreground) return Result.success()
            }
            delay(60_000)
        }
        return Result.success()
    }

    private fun foregroundInfo(): ForegroundInfo {
        AlertNotifier.ensureChannels(applicationContext)
        val n = AlertNotifier.liveNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(AlertNotifier.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(AlertNotifier.LIVE_ID, n)
        }
    }

    /** One look at the matchup; true while any NFL game is in progress. */
    private suspend fun checkOnce(s: Settings): Boolean {
        val (cookies, swids) = withContext(Dispatchers.Main) {
            val headers = listOf(BrowserSession.apiCookieHeader()) + BrowserSession.otherCookieHeaders()
            EspnLogin.cookieCandidates(BrowserSession.apiCookieHeader(), BrowserSession.otherCookieHeaders(),
                EspnClient.cookieHeader(s.espnS2, s.swid)) to
                (EspnLogin.cookieValues(headers, "SWID") + listOfNotNull(s.swid.ifBlank { null }))
        }
        return withContext(Dispatchers.IO) {
            val cache = FileJsonCache(File(applicationContext.filesDir, "espn-cache/${s.leagueId}-${s.year}"))
            val net = EspnClient(s.leagueId.toLong(), s.year, cookies, cache = cache)
            // The league (schedule, settings) barely changes during a game: reuse a recent copy.
            val leagueJson = if ((cache.savedAt("league") ?: 0) > System.currentTimeMillis() - SIX_HOURS) {
                EspnClient(s.leagueId.toLong(), s.year, cookies, cache = cache, offline = true).league()
            } else {
                net.league()
            }
            val league = EspnParser.parseLeague(leagueJson)
            val pro = try { EspnParser.parseProTeams(net.proTeams()) } catch (e: EspnException) { emptyMap() }
            val (rosters, scoreboard) = net.currentWeek(league, pro, emptyMap())
            val myTeam = s.myTeamId.takeIf { it in league.teams } ?: league.teamOwnedBy(swids) ?: return@withContext false

            val now = System.currentTimeMillis()
            val prefs = applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
            val prev = AlertSnapshot.fromJson(prefs.getString("snapshot", null))
            val cur = AlertEngine.snapshot(league, rosters, myTeam, now)
            if (cur != null) {
                // Free agents as of the app's last load: no extra requests during games.
                val freeAgents = if (!s.alerts.opportunities) emptyList() else {
                    val saved = EspnClient(s.leagueId.toLong(), s.year, cookies, cache = cache, offline = true)
                    listOf(0, 2, 4, 6).flatMap { slot ->
                        try {
                            EspnParser.parseFreeAgents(saved.freeAgents(league.currentScoringPeriod, slot), league.currentScoringPeriod,
                                pro, emptyMap(), s.year)
                        } catch (e: EspnException) {
                            emptyList()
                        }
                    }.distinctBy { it.id }
                }
                val (alerts, next) = AlertEngine.alerts(prev, cur, s.alerts, league, rosters, myTeam, now, freeAgents)
                prefs.edit().putString("snapshot", next.toJson()).apply()
                AlertNotifier.post(applicationContext, alerts)
            }
            scoreboard.values.any { it.state == NflGame.IN }
        }
    }

    private companion object {
        const val MAX_MINUTES = 6 * 60
        const val SIX_HOURS = 6L * 60 * 60 * 1000
    }
}
