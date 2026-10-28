package com.fantasyanalyzer.data

/** A regular starter ruled out, and the available teammate most likely to take his work. */
data class Opportunity(val out: Player, val nextUp: Player)

/**
 * Who steps in when a starter is ruled out. ESPN has no depth chart, so this is
 * a best guess: among free agents on the same NFL team and position, the one
 * ESPN projects highest this week (its projections already move up the backup
 * once a starter is out), then the most rostered.
 */
object Opportunities {
    private val POSITIONS = setOf("QB", "RB", "WR", "TE")

    /** Started in at least this share of leagues: someone whose absence frees up real work. */
    private const val STARTER_PCT = 40.0

    fun find(rostered: List<Player>, freeAgents: List<Player>): List<Opportunity> {
        val starters = (rostered + freeAgents).distinctBy { it.id }
            .filter { it.isOut && !it.onBye && it.position in POSITIONS && it.percentStarted >= STARTER_PCT }
        return starters.mapNotNull { out ->
            freeAgents
                .filter { it.proTeam == out.proTeam && it.position == out.position && it.id != out.id && !it.isOut }
                .filter { (it.projected ?: 0.0) > 0.0 }
                .maxWithOrNull(compareBy<Player> { it.projected ?: 0.0 }.thenBy { it.percentOwned })
                ?.let { Opportunity(out, it) }
        }.sortedByDescending { it.out.percentStarted }
    }
}
