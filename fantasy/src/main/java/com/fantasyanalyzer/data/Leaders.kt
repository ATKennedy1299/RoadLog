package com.fantasyanalyzer.data

/** One player's season so far, for the stat leaders table. */
data class Leader(
    val player: Player,
    /** Fantasy points this season under the league's scoring. */
    val points: Double,
    /** Games played, when ESPN reports it. */
    val games: Int?,
    /** Season totals, ESPN stat id -> value (e.g. 42 -> receiving yards). */
    val stats: Map<Int, Double>,
    /** The league team that has him, or null for a free agent. */
    val fantasyTeamId: Int?,
) {
    val average: Double? get() = games?.takeIf { it > 0 }?.let { points / it }

    companion object {
        /** Positions with a leaders table, and the lineup slot ESPN filters each by. */
        val POSITION_SLOTS = linkedMapOf("QB" to 0, "RB" to 2, "WR" to 4, "TE" to 6, "K" to 17, "D/ST" to 16)

        /** ESPN's games-played stat. */
        const val GAMES_PLAYED = 210
    }
}

/** The stat leaders as loaded so far: position -> leaders, best first. */
data class LeadersState(
    val byPosition: Map<String, List<Leader>> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
)
