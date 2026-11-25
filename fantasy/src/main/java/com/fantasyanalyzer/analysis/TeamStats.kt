package com.fantasyanalyzer.analysis

import com.fantasyanalyzer.data.BoxScores

/** A team's starters against ESPN's projections over the season. */
data class ProjectionRow(
    val teamId: Int,
    val team: String,
    val actual: Double,
    val projected: Double,
    /** Weeks the starters beat their projected total, out of [weeks]. */
    val weeksBeat: Int,
    val weeks: Int,
) {
    val diff: Double get() = actual - projected
    val perWeek: Double get() = if (weeks > 0) diff / weeks else 0.0
}

/** Current and longest runs of wins and losses. */
data class Streaks(val current: Char?, val currentLength: Int, val longestWin: Int, val longestLoss: Int) {
    val currentText: String? get() = current?.let { "$it$currentLength" }
}

/** A player a team leaned on: weeks in the lineup and points scored there. */
data class StarterUsage(val name: String, val position: String, val starts: Int, val points: Double)

object TeamStats {
    val POSITION_ORDER = listOf("QB", "RB", "WR", "TE", "K", "D/ST")

    /**
     * Each team's starters, summed, against ESPN's projections for them: who
     * gets more out of their lineup than ESPN expects. Only starters with a
     * projection count. Best first.
     */
    fun vsProjection(box: BoxScores, teamName: (Int) -> String): List<ProjectionRow> {
        val weekly = box.values.flatMap { rosters ->
            rosters.mapNotNull { (team, roster) ->
                val starters = roster.filter { it.started && it.projected != null }
                if (starters.isEmpty()) null
                else Triple(team, starters.sumOf { it.points }, starters.sumOf { it.projected!! })
            }
        }
        return weekly.groupBy { it.first }.map { (team, weeks) ->
            ProjectionRow(
                team, teamName(team),
                weeks.sumOf { it.second }, weeks.sumOf { it.third },
                weeks.count { it.second > it.third }, weeks.size,
            )
        }.sortedByDescending { it.perWeek }
    }

    /** Team id -> starters' points by position over the season. */
    fun pointsByPosition(box: BoxScores): Map<Int, Map<String, Double>> =
        box.values.flatMap { rosters -> rosters.flatMap { (team, r) -> r.filter { it.started }.map { team to it } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.groupBy { it.position }.mapValues { (_, e) -> e.sumOf { it.points } } }

    /** Win and loss streaks from one team's games. */
    fun streaks(games: List<TeamGame>): Streaks {
        val results = games.sortedBy { it.week }.map { it.result }
        var longestW = 0
        var longestL = 0
        var run = 0
        var last: Char? = null
        for (r in results) {
            run = if (r == last) run + 1 else 1
            last = r
            if (r == 'W') longestW = maxOf(longestW, run)
            if (r == 'L') longestL = maxOf(longestL, run)
        }
        return Streaks(last, run, longestW, longestL)
    }

    /** The players [teamId] started most, with the points they scored while starting. */
    fun mostStarted(box: BoxScores, teamId: Int, top: Int = 6): List<StarterUsage> =
        box.values.flatMap { it[teamId].orEmpty().filter { e -> e.started } }
            .groupBy { it.playerId.takeIf { id -> id != 0L } ?: it.name.hashCode().toLong() }
            .map { (_, e) -> StarterUsage(e.first().name, e.first().position, e.size, e.sumOf { it.points }) }
            .sortedWith(compareByDescending<StarterUsage> { it.starts }.thenByDescending { it.points })
            .take(top)
}
