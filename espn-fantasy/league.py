"""Parses raw ESPN JSON into plain dataclasses the analysis code works on."""
from __future__ import annotations

from dataclasses import dataclass, field

POSITIONS = {1: "QB", 2: "RB", 3: "WR", 4: "TE", 5: "K", 16: "D/ST"}

SLOTS = {
    0: "QB", 1: "TQB", 2: "RB", 3: "RB/WR", 4: "WR", 5: "WR/TE", 6: "TE", 7: "OP",
    8: "DT", 9: "DE", 10: "LB", 11: "DL", 12: "CB", 13: "S", 14: "DB", 15: "DP",
    16: "D/ST", 17: "K", 18: "P", 19: "HC", 20: "BE", 21: "IR", 23: "FLEX", 24: "ER",
}
BENCH, IR = 20, 21
# Slots that never count toward a starting lineup.
INACTIVE_SLOTS = {BENCH, IR}


@dataclass
class Team:
    id: int
    name: str
    abbrev: str
    owner: str


@dataclass
class Matchup:
    week: int
    home_id: int
    away_id: int | None  # None for a playoff bye
    home_points: float
    away_points: float
    winner: str  # HOME, AWAY, TIE, UNDECIDED
    is_playoff: bool

    @property
    def complete(self) -> bool:
        return self.winner != "UNDECIDED"


@dataclass
class RosterEntry:
    name: str
    position: str
    slot_id: int
    points: float
    eligible_slots: list[int]

    @property
    def started(self) -> bool:
        return self.slot_id not in INACTIVE_SLOTS


@dataclass
class League:
    id: int
    year: int
    name: str
    teams: dict[int, Team]
    matchups: list[Matchup]
    current_week: int
    regular_season_weeks: int
    lineup_slot_counts: dict[int, int]
    # matchup period -> scoring periods (usually one NFL week each)
    matchup_periods: dict[int, list[int]] = field(default_factory=dict)

    def completed_weeks(self, include_playoffs: bool = False) -> list[int]:
        weeks = {
            m.week for m in self.matchups
            if m.complete and m.away_id is not None and (include_playoffs or not m.is_playoff)
        }
        return sorted(weeks)


def _team_name(raw: dict) -> str:
    if raw.get("name"):
        return raw["name"].strip()
    return f"{raw.get('location', '')} {raw.get('nickname', '')}".strip() or f"Team {raw['id']}"


def parse_league(raw: dict) -> League:
    settings = raw.get("settings", {})
    members = {m["id"]: m for m in raw.get("members", [])}

    teams = {}
    for t in raw.get("teams", []):
        owners = [members[o] for o in t.get("owners", []) if o in members]
        if owners:
            o = owners[0]
            owner = o.get("displayName") or f"{o.get('firstName', '')} {o.get('lastName', '')}".strip()
        else:
            owner = ""
        teams[t["id"]] = Team(t["id"], _team_name(t), t.get("abbrev", ""), owner)

    sched = settings.get("scheduleSettings", {})
    reg_weeks = sched.get("matchupPeriodCount") or 0

    matchups = []
    for m in raw.get("schedule", []):
        home, away = m.get("home"), m.get("away")
        if not home:
            continue
        week = m["matchupPeriodId"]
        is_playoff = m.get("playoffTierType", "NONE") != "NONE" or (reg_weeks and week > reg_weeks)
        matchups.append(Matchup(
            week=week,
            home_id=home["teamId"],
            away_id=away["teamId"] if away else None,
            home_points=float(home.get("totalPoints", 0) or 0),
            away_points=float(away.get("totalPoints", 0) or 0) if away else 0.0,
            winner=m.get("winner", "UNDECIDED"),
            is_playoff=bool(is_playoff),
        ))

    status = raw.get("status", {})
    current = status.get("currentMatchupPeriod") or raw.get("scoringPeriodId") or 1

    slot_counts = {
        int(k): v for k, v in settings.get("rosterSettings", {}).get("lineupSlotCounts", {}).items() if v
    }
    periods = {int(k): v for k, v in sched.get("matchupPeriods", {}).items()}

    return League(
        id=raw.get("id", 0),
        year=raw.get("seasonId", 0),
        name=settings.get("name", "Fantasy League"),
        teams=teams,
        matchups=matchups,
        current_week=current,
        regular_season_weeks=reg_weeks,
        lineup_slot_counts=slot_counts,
        matchup_periods=periods,
    )


def _player_points(entry: dict, scoring_period: int) -> float:
    pool = entry.get("playerPoolEntry", {})
    for s in pool.get("player", {}).get("stats", []):
        # statSourceId 0 = actual points, 1 = projection
        if s.get("scoringPeriodId") == scoring_period and s.get("statSourceId") == 0:
            return float(s.get("appliedTotal", 0) or 0)
    return float(pool.get("appliedStatTotal", 0) or 0)


def parse_box_scores(raw: dict, scoring_period: int) -> dict[int, list[RosterEntry]]:
    """Returns team id -> roster for one week."""
    rosters: dict[int, list[RosterEntry]] = {}
    for m in raw.get("schedule", []):
        for side in ("home", "away"):
            team = m.get(side)
            if not team:
                continue
            roster = team.get("rosterForCurrentScoringPeriod") or team.get("rosterForMatchupPeriod") or {}
            entries = []
            for e in roster.get("entries", []):
                player = e.get("playerPoolEntry", {}).get("player", {})
                entries.append(RosterEntry(
                    name=player.get("fullName", "Unknown"),
                    position=POSITIONS.get(player.get("defaultPositionId"), "?"),
                    slot_id=e.get("lineupSlotId", BENCH),
                    points=_player_points(e, scoring_period),
                    eligible_slots=player.get("eligibleSlots", []),
                ))
            if entries:
                rosters[team["teamId"]] = entries
    return rosters
