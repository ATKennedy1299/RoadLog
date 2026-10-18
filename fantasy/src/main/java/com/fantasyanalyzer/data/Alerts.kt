package com.fantasyanalyzer.data

import com.fantasyanalyzer.analysis.Outlook
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Which game-day notifications the user wants. */
data class AlertSettings(
    val enabled: Boolean = false,
    /** A starter scores (one notification with the new matchup score and win chance). */
    val playerScored: Boolean = true,
    /** Smallest jump in points between checks that counts as "scored" (a TD is 6). */
    val scoreThreshold: Int = 4,
    /** Also tell me when the opponent's players score. */
    val includeOpponent: Boolean = false,
    val leadChange: Boolean = false,
    /** Win chance crosses 50%. */
    val winSwing: Boolean = false,
    val finalResult: Boolean = true,
    val redZone: Boolean = false,
    /** A starter is ruled out or doubtful before kickoff. */
    val injuries: Boolean = false,
    /** About an hour before kickoff if a starter is out, on bye, or a slot is empty. */
    val lineupReminder: Boolean = false,
    /** Check every minute during live games (with an ongoing notification) instead of every 15. */
    val instant: Boolean = true,
)

data class GameAlert(val key: String, val title: String, val text: String)

/** What the last check saw, to compare the next one against. */
data class AlertSnapshot(
    val week: Int,
    val points: Map<Long, Double>,
    val redZone: Set<Long>,
    val status: Map<Long, String>,
    val myScore: Double,
    val oppScore: Double,
    val winProbability: Double?,
    val final: Boolean,
    /** Kickoff times already reminded about. */
    val reminded: Set<Long> = emptySet(),
) {
    fun toJson(): String = JSONObject()
        .put("week", week)
        .put("points", JSONObject().apply { points.forEach { (k, v) -> put(k.toString(), v) } })
        .put("redZone", JSONArray(redZone.toList()))
        .put("status", JSONObject().apply { status.forEach { (k, v) -> put(k.toString(), v) } })
        .put("my", myScore)
        .put("opp", oppScore)
        .put("win", winProbability ?: JSONObject.NULL)
        .put("final", final)
        .put("reminded", JSONArray(reminded.toList()))
        .toString()

    companion object {
        fun fromJson(json: String?): AlertSnapshot? = try {
            json?.let { s ->
                val o = JSONObject(s)
                val pts = o.getJSONObject("points")
                val st = o.getJSONObject("status")
                val rz = o.getJSONArray("redZone")
                val rem = o.optJSONArray("reminded") ?: JSONArray()
                AlertSnapshot(
                    week = o.getInt("week"),
                    points = pts.keys().asSequence().associate { it.toLong() to pts.getDouble(it) },
                    redZone = (0 until rz.length()).map { rz.getLong(it) }.toSet(),
                    status = st.keys().asSequence().associate { it.toLong() to st.getString(it) },
                    myScore = o.getDouble("my"),
                    oppScore = o.getDouble("opp"),
                    winProbability = if (o.isNull("win")) null else o.getDouble("win"),
                    final = o.getBoolean("final"),
                    reminded = (0 until rem.length()).map { rem.getLong(it) }.toSet(),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Turns two looks at the current week into the notifications worth sending. */
object AlertEngine {
    private const val REMIND_BEFORE_MS = 60L * 60 * 1000

    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
    private fun pct(p: Double) = "${(p * 100).toInt()}%"

    fun snapshot(league: League, rosters: Map<Int, List<RosterSpot>>, myTeam: Int, now: Long, reminded: Set<Long> = emptySet()): AlertSnapshot? {
        val m = league.matchups.firstOrNull { it.week == league.currentWeek && (it.homeId == myTeam || it.awayId == myTeam) }
            ?: return null
        val oppId = (if (m.homeId == myTeam) m.awayId else m.homeId) ?: return null
        val mine = rosters[myTeam] ?: return null
        val theirs = rosters[oppId].orEmpty()
        val starters = (mine + theirs).filter { it.started }
        val outlook = Outlook.matchup(league, WeekOutlook(league.currentScoringPeriod, rosters, emptyList()), myTeam, now)
        val allDone = starters.isNotEmpty() && starters.all { it.player.isOut || (it.player.gameStarted(now) && it.player.remainingShare(now) <= 0.0) }
        return AlertSnapshot(
            week = league.currentWeek,
            points = starters.associate { it.player.id to it.player.pointsSoFar(now) },
            redZone = starters.filter { it.player.fieldStatus == FieldStatus.RED_ZONE }.map { it.player.id }.toSet(),
            status = mine.associate { it.player.id to it.player.injuryStatus },
            myScore = mine.filter { it.started }.sumOf { it.player.pointsSoFar(now) },
            oppScore = theirs.filter { it.started }.sumOf { it.player.pointsSoFar(now) },
            winProbability = outlook?.winProbability,
            final = allDone,
            reminded = reminded,
        )
    }

    /**
     * Notifications for what changed from [prev] to [cur]. Nothing fires on the
     * first look at a week (there's nothing to compare against), so turning
     * alerts on mid-game doesn't flood you.
     */
    fun alerts(
        prev: AlertSnapshot?,
        cur: AlertSnapshot,
        settings: AlertSettings,
        league: League,
        rosters: Map<Int, List<RosterSpot>>,
        myTeam: Int,
        now: Long,
    ): Pair<List<GameAlert>, AlertSnapshot> {
        val out = mutableListOf<GameAlert>()
        var next = cur.copy(reminded = prev?.reminded.orEmpty().filter { it > now - 7L * 24 * 60 * 60 * 1000 }.toSet())
        val mine = rosters[myTeam].orEmpty()
        val myIds = mine.map { it.player.id }.toSet()
        val names = rosters.values.flatten().associate { it.player.id to it.player.name }
        val opp = league.matchups.firstOrNull { it.week == league.currentWeek && (it.homeId == myTeam || it.awayId == myTeam) }
            ?.let { if (it.homeId == myTeam) it.awayId else it.homeId }
        val oppName = opp?.let { league.teamName(it) } ?: "Opponent"
        val scoreLine = "You ${f1(cur.myScore)} – ${f1(cur.oppScore)} $oppName" +
            (cur.winProbability?.takeIf { !cur.final }?.let { " · ${pct(it)} to win" } ?: "")

        // Lineup reminder works off kickoff times, so it doesn't need an earlier look.
        if (settings.lineupReminder) {
            val starters = mine.filter { it.started }
            val nextKickoff = starters.mapNotNull { it.player.gameStart }.filter { it > now }.minOrNull()
            if (nextKickoff != null && nextKickoff - now <= REMIND_BEFORE_MS && nextKickoff !in next.reminded) {
                val advice = Outlook.startSit(mine, league.lineupSlotCounts, now)
                val issues = advice.alerts.filter { it.severe }.map { "${it.player.name} ${it.message}" } +
                    (if (advice.emptySlots > 0) listOf("${advice.emptySlots} empty starting slot(s)") else emptyList())
                if (issues.isNotEmpty()) {
                    out += GameAlert("remind-$nextKickoff", "Check your lineup before kickoff", issues.joinToString("; "))
                }
                next = next.copy(reminded = next.reminded + nextKickoff)
            }
        }

        if (prev == null || prev.week != cur.week) return out to next

        if (settings.playerScored) {
            val jumps = cur.points.mapNotNull { (id, pts) ->
                val before = prev.points[id] ?: return@mapNotNull null
                val gain = pts - before
                val counts = id in myIds || settings.includeOpponent
                if (counts && gain >= settings.scoreThreshold) Triple(id, gain, id in myIds) else null
            }.sortedByDescending { it.second }
            if (jumps.isNotEmpty()) {
                val title = jumps.joinToString(", ") { (id, gain, isMine) ->
                    (if (isMine) "" else "Opp: ") + shortName(names[id] ?: "Player") + " +" + f1(gain)
                }
                out += GameAlert("score-${jumps.first().first}-$now", title, scoreLine)
            }
        }

        if (settings.redZone) {
            val entered = cur.redZone - prev.redZone
            entered.filter { it in myIds || settings.includeOpponent }.forEach { id ->
                out += GameAlert("rz-$id-$now", "${if (id in myIds) "" else "Opp: "}${names[id] ?: "Player"} is in the red zone", scoreLine)
            }
        }

        if (settings.leadChange && prev.myScore + prev.oppScore > 0) {
            val before = prev.myScore - prev.oppScore
            val after = cur.myScore - cur.oppScore
            if (before <= 0 && after > 0) out += GameAlert("lead-$now", "You took the lead", scoreLine)
            if (before >= 0 && after < 0) out += GameAlert("lead-$now", "$oppName took the lead", scoreLine)
        }

        if (settings.winSwing && !cur.final) {
            val b = prev.winProbability
            val a = cur.winProbability
            if (b != null && a != null) {
                if (b < 0.5 && a >= 0.5) out += GameAlert("win-$now", "You're now favored (${pct(a)})", scoreLine)
                if (b >= 0.5 && a < 0.5) out += GameAlert("win-$now", "You're now the underdog (${pct(a)})", scoreLine)
            }
        }

        if (settings.finalResult && cur.final && !prev.final) {
            val result = when {
                cur.myScore > cur.oppScore -> "You won"
                cur.myScore < cur.oppScore -> "You lost"
                else -> "You tied"
            }
            out += GameAlert("final-${cur.week}", "Final: $result", "You ${f1(cur.myScore)} – ${f1(cur.oppScore)} $oppName")
        }

        if (settings.injuries) {
            mine.filter { it.started && !it.player.gameStarted(now) }.forEach { s ->
                val was = prev.status[s.player.id]
                val now2 = s.player.injuryStatus
                if (was != null && was != now2 && now2 in setOf("OUT", "DOUBTFUL", "INJURY_RESERVE", "SUSPENSION")) {
                    out += GameAlert("inj-${s.player.id}-$now2", "${s.player.name} is ${now2.lowercase().replace('_', ' ')}",
                        "They're in your starting lineup.")
                }
            }
        }
        return out to next
    }

    private fun shortName(name: String): String {
        if (name.contains("D/ST")) return name
        val parts = name.split(" ")
        return if (parts.size >= 2) "${parts[0].first()}. ${parts.drop(1).joinToString(" ")}" else name
    }
}
