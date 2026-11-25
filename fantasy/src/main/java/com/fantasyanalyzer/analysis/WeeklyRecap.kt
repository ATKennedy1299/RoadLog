package com.fantasyanalyzer.analysis

import com.fantasyanalyzer.data.RosterEntry
import java.util.Locale
import kotlin.math.abs

/** One line of a week's recap: what it is, who, and the numbers. */
data class RecapLine(val label: String, val headline: String, val detail: String)

/** The week in a few headlines, for the group chat. */
object WeeklyRecap {
    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)

    /**
     * [games] has both sides of every game that week. [rosters] (team id ->
     * lineup with points) adds the top player and the worst bench call when
     * the week's box scores are loaded.
     */
    fun lines(
        games: List<TeamGame>,
        rosters: Map<Int, List<RosterEntry>>?,
        teamName: (Int) -> String,
        efficiency: List<EfficiencyRow> = emptyList(),
    ): List<RecapLine> {
        if (games.isEmpty()) return emptyList()
        val out = mutableListOf<RecapLine>()
        val high = games.maxBy { it.points }
        val low = games.minBy { it.points }
        out += RecapLine("High score", high.team, "${f1(high.points)} vs ${high.opponent}")
        out += RecapLine("Low score", low.team, "${f1(low.points)} vs ${low.opponent}")
        val decided = games.filter { it.margin > 0 }
        decided.maxByOrNull { it.margin }?.let { g ->
            out += RecapLine("Biggest blowout", "${g.team} over ${g.opponent}", "${f1(g.points)}–${f1(g.oppPoints)}, by ${f1(g.margin)}")
        }
        (decided + games.filter { it.margin == 0.0 && it.teamId < it.oppId }).minByOrNull { abs(it.margin) }?.let { g ->
            out += RecapLine(
                "Closest game",
                if (g.margin == 0.0) "${g.team} and ${g.opponent} tied" else "${g.team} over ${g.opponent}",
                "${f1(g.points)}–${f1(g.oppPoints)}" + if (g.margin > 0) ", by ${f1(g.margin)}" else "",
            )
        }
        rosters?.flatMap { (team, r) -> r.filter { it.started }.map { team to it } }?.maxByOrNull { it.second.points }?.let { (team, p) ->
            out += RecapLine("Top player", "${p.name} (${p.position})", "${f1(p.points)} for ${teamName(team)}")
        }
        efficiency.filter { it.leftOnBench > 0.05 }.maxByOrNull { it.leftOnBench }?.let { e ->
            out += RecapLine(
                "Bench blunder", e.team,
                "${f1(e.leftOnBench)} pts left on the bench" + if (e.topBenched.isNotBlank()) " (${e.topBenched})" else "",
            )
        }
        return out
    }
}
