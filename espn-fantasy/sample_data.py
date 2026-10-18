"""Generates a fake league in ESPN's raw JSON shape, for demo mode and tests."""
from __future__ import annotations

import random

TEAM_NAMES = [
    "Gridiron Gurus", "Touchdown Tyrants", "Fourth & Long", "Blitz Brigade",
    "End Zone Elite", "Hail Mary Heroes", "Pigskin Prophets", "Red Zone Raiders",
    "Sunday Scaries", "The Waiver Wire", "Bye Week Blues", "Taco's Corner",
]
SLOT_COUNTS = {"0": 1, "2": 2, "4": 2, "6": 1, "23": 1, "16": 1, "17": 1, "20": 7, "21": 1}
# position id -> (eligible slots, weekly mean, weekly sd, roster count)
POSITIONS = {
    1: ([0, 7, 20, 21], 18, 7, 2),
    2: ([2, 3, 23, 7, 20, 21], 11, 7, 5),
    3: ([4, 3, 5, 23, 7, 20, 21], 11, 7, 5),
    4: ([6, 5, 23, 7, 20, 21], 8, 5, 2),
    16: ([16, 20, 21], 7, 5, 1),
    5: ([17, 20, 21], 8, 4, 1),
}
STARTERS = {1: [0], 2: [2, 2], 3: [4, 4], 4: [6], 16: [16], 5: [17]}


def _pairings(team_ids: list[int], week: int) -> list[tuple[int, int]]:
    """Round-robin circle method."""
    ids = team_ids[:]
    n = len(ids)
    rot = (week - 1) % (n - 1)
    ids = [ids[0]] + ids[1:][-rot:] + ids[1:][:-rot] if rot else ids
    return [(ids[i], ids[n - 1 - i]) for i in range(n // 2)]


def _make_rosters(rng: random.Random, team_ids: list[int]) -> dict[int, list[dict]]:
    rosters = {}
    pid = 1000
    for tid in team_ids:
        skill = rng.uniform(0.85, 1.15)
        players = []
        for pos, (slots, mean, sd, count) in POSITIONS.items():
            for n in range(count):
                pid += 1
                players.append({
                    "id": pid, "pos": pos, "slots": slots, "sd": sd,
                    "mean": mean * skill * rng.uniform(0.6, 1.3) * (1.0 if n < 2 else 0.8),
                    "name": f"Player {pid}",
                })
        rosters[tid] = players
    return rosters


def _week_roster(rng: random.Random, players: list[dict], week: int, manager_skill: float) -> tuple[list[dict], float]:
    pts = {p["id"]: max(0.0, round(rng.gauss(p["mean"], p["sd"]), 1)) for p in players}
    # Managers start by expected value with some noise; better managers have less noise.
    guess = {p["id"]: p["mean"] + rng.gauss(0, 4 * (1.5 - manager_skill)) for p in players}
    remaining = sorted(players, key=lambda p: guess[p["id"]], reverse=True)
    assigned = {}
    for pos, slots in STARTERS.items():
        for slot in slots:
            p = next(p for p in remaining if p["pos"] == pos and p["id"] not in assigned)
            assigned[p["id"]] = slot
    flex = next(p for p in remaining if p["pos"] in (2, 3, 4) and p["id"] not in assigned)
    assigned[flex["id"]] = 23

    entries, total = [], 0.0
    for p in players:
        slot = assigned.get(p["id"], 20)
        if slot != 20:
            total += pts[p["id"]]
        entries.append({
            "lineupSlotId": slot,
            "playerPoolEntry": {
                "appliedStatTotal": pts[p["id"]],
                "player": {
                    "fullName": p["name"], "defaultPositionId": p["pos"], "eligibleSlots": p["slots"],
                    "stats": [
                        {"scoringPeriodId": week, "statSourceId": 0, "appliedTotal": pts[p["id"]]},
                        {"scoringPeriodId": week, "statSourceId": 1, "appliedTotal": round(p["mean"], 1)},
                    ],
                },
            },
        })
    return entries, round(total, 2)


def generate(num_teams: int = 10, weeks_played: int = 6, reg_weeks: int = 14, seed: int = 7):
    """Returns (league_json, {week: box_score_json})."""
    rng = random.Random(seed)
    team_ids = list(range(1, num_teams + 1))
    rosters = _make_rosters(rng, team_ids)
    manager_skill = {t: rng.uniform(0.6, 1.4) for t in team_ids}

    members = [{"id": f"{{GUID-{t}}}", "displayName": f"owner{t}", "firstName": "Owner", "lastName": str(t)}
               for t in team_ids]
    teams = [{"id": t, "abbrev": TEAM_NAMES[t - 1][:4].upper(), "name": TEAM_NAMES[t - 1],
              "owners": [f"{{GUID-{t}}}"]} for t in team_ids]

    schedule, boxes, mid = [], {}, 0
    for week in range(1, reg_weeks + 1):
        week_sched = []
        for h, a in _pairings(team_ids, week):
            mid += 1
            m = {"id": mid, "matchupPeriodId": week, "playoffTierType": "NONE",
                 "home": {"teamId": h, "totalPoints": 0}, "away": {"teamId": a, "totalPoints": 0},
                 "winner": "UNDECIDED"}
            if week <= weeks_played:
                he, hp = _week_roster(rng, rosters[h], week, manager_skill[h])
                ae, ap = _week_roster(rng, rosters[a], week, manager_skill[a])
                m["home"]["totalPoints"], m["away"]["totalPoints"] = hp, ap
                m["winner"] = "HOME" if hp > ap else "AWAY" if ap > hp else "TIE"
                box = {k: v for k, v in m.items()}
                box["home"] = dict(m["home"], rosterForCurrentScoringPeriod={"entries": he})
                box["away"] = dict(m["away"], rosterForCurrentScoringPeriod={"entries": ae})
                week_sched.append(box)
            schedule.append(m)
        if week_sched:
            boxes[week] = {"schedule": week_sched}

    league = {
        "id": 123456, "seasonId": 2026, "scoringPeriodId": weeks_played + 1,
        "status": {"currentMatchupPeriod": weeks_played + 1},
        "settings": {
            "name": "Demo League (sample data)",
            "rosterSettings": {"lineupSlotCounts": SLOT_COUNTS},
            "scheduleSettings": {
                "matchupPeriodCount": reg_weeks,
                "matchupPeriods": {str(w): [w] for w in range(1, reg_weeks + 1)},
            },
        },
        "members": members, "teams": teams, "schedule": schedule,
    }
    return league, boxes
