package com.fantasyanalyzer.data

import kotlin.math.sqrt

/** How a player's points spread out: a steady scorer or a boom-or-bust one. */
enum class ScoringStyle(val label: String) { STEADY("Steady"), BOOM_BUST("Boom/bust") }

/** Floor, typical game and ceiling: roughly his worst-fifth, middle and best-fifth weeks. */
data class ScoreRange(val floor: Double, val typical: Double, val ceiling: Double)

/**
 * A player's finished weeks this season against ESPN's projections: how often
 * he beat them (oldest first in [results]) and, once there are enough games,
 * his scoring range and style.
 */
data class SeasonForm(val results: List<Boolean>, val range: ScoreRange?, val style: ScoringStyle?) {
    val beat: Int get() = results.count { it }
    val games: Int get() = results.size

    companion object {
        /** Games needed before a range or style means anything. */
        const val MIN_GAMES = 4

        /**
         * From [weeks] (NFL week -> points and projection), counting only weeks
         * before [before] (the week in progress) that he was expected to play.
         */
        fun of(weeks: Map<Int, WeekLine>, before: Int): SeasonForm? {
            val played = weeks.filterKeys { it < before }.toSortedMap().values
                .filter { it.actual != null && (it.projected ?: 0.0) > 0.0 }
            if (played.isEmpty()) return null
            val points = played.map { it.actual!! }
            val enough = points.size >= MIN_GAMES
            return SeasonForm(
                results = played.map { it.actual!! >= it.projected!! },
                range = if (enough) points.sorted().let { s -> ScoreRange(quantile(s, 0.2), quantile(s, 0.5), quantile(s, 0.8)) } else null,
                style = if (enough) style(points) else null,
            )
        }

        private fun style(points: List<Double>): ScoringStyle? {
            val mean = points.average()
            if (mean < 3.0) return null // too little scoring to call
            val sd = sqrt(points.sumOf { (it - mean) * (it - mean) } / points.size)
            return when {
                sd / mean <= 0.35 -> ScoringStyle.STEADY
                sd / mean >= 0.65 -> ScoringStyle.BOOM_BUST
                else -> null
            }
        }

        /** Linear-interpolated quantile of an already sorted list. */
        private fun quantile(sorted: List<Double>, q: Double): Double {
            val pos = q * (sorted.size - 1)
            val lo = pos.toInt()
            val hi = minOf(lo + 1, sorted.lastIndex)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
    }
}
