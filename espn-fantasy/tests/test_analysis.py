import itertools
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import analysis
import sample_data
from league import RosterEntry, parse_box_scores, parse_league


def _demo():
    raw, boxes = sample_data.generate(num_teams=10, weeks_played=6)
    return parse_league(raw), {w: parse_box_scores(b, w) for w, b in boxes.items()}


def test_parse_league():
    lg, _ = _demo()
    assert len(lg.teams) == 10
    assert lg.completed_weeks() == [1, 2, 3, 4, 5, 6]
    assert lg.lineup_slot_counts[23] == 1 and 20 in lg.lineup_slot_counts
    assert lg.teams[1].owner == "owner1"


def test_legacy_team_name_and_bye():
    raw = {"teams": [{"id": 1, "location": "Big", "nickname": "Dogs"}],
           "schedule": [{"matchupPeriodId": 15, "playoffTierType": "WINNERS_BRACKET",
                         "home": {"teamId": 1, "totalPoints": 100}, "winner": "UNDECIDED"}]}
    lg = parse_league(raw)
    assert lg.teams[1].name == "Big Dogs"
    assert lg.matchups[0].away_id is None and lg.matchups[0].is_playoff


def test_standings_consistent():
    lg, _ = _demo()
    st = analysis.standings(lg)
    assert (st.W + st.L + st["T"] == 6).all()
    assert st.W.sum() == st.L.sum()
    assert abs(st.PF.sum() - st.PA.sum()) < 1e-6
    # luck sums to zero across the league (every all-play win is someone's loss)
    assert abs(st.Luck.sum()) < 1e-6
    assert st.Power.between(0, 100).all()


def test_all_play_hand_computed():
    raw = {"teams": [{"id": i, "name": n} for i, n in [(1, "A"), (2, "B"), (3, "C"), (4, "D")]],
           "schedule": [
               {"matchupPeriodId": 1, "home": {"teamId": 1, "totalPoints": 150},
                "away": {"teamId": 2, "totalPoints": 140}, "winner": "HOME"},
               {"matchupPeriodId": 1, "home": {"teamId": 3, "totalPoints": 90},
                "away": {"teamId": 4, "totalPoints": 80}, "winner": "HOME"}]}
    st = analysis.standings(parse_league(raw)).set_index("Team")
    assert st.loc["B", "All-Play"] == "2-1"
    assert st.loc["C", "Luck"] == 1 - 1 / 3  # won with the 3rd-best score


def test_schedule_swap_diagonal_matches_record():
    lg, _ = _demo()
    st = analysis.standings(lg).set_index("Team")
    swap = analysis.schedule_swap(lg)
    for team in swap.index:
        assert swap.loc[team, team] == st.loc[team, "W"] + 0.5 * st.loc[team, "T"]


def _brute_force(roster, counts):
    """Try every bench-or-slot assignment, allowing unfilled slots."""
    players = [p for p in roster if p.slot_id != 21]
    choices = [[None] + [s for s in p.eligible_slots if s in counts and s not in (20, 21)] for p in players]
    best = 0.0
    for combo in itertools.product(*choices):
        used = [s for s in combo if s is not None]
        if all(used.count(s) <= counts[s] for s in set(used)):
            best = max(best, sum(p.points for p, s in zip(players, combo) if s is not None))
    return best


def test_optimal_lineup_matches_brute_force():
    rng = random.Random(1)
    counts = {0: 1, 2: 1, 4: 1, 23: 1, 7: 1, 20: 3}  # QB, RB, WR, FLEX, OP
    elig = {"QB": [0, 7], "RB": [2, 23, 7], "WR": [4, 23, 7], "TE": [6, 23, 7]}
    for _ in range(30):
        roster = [RosterEntry(f"p{i}", pos, 20, round(rng.uniform(0, 30), 1), elig[pos])
                  for i, pos in enumerate(rng.choices(list(elig), k=7))]
        total, lineup = analysis.optimal_lineup(roster, counts)
        assert abs(total - _brute_force(roster, counts)) < 1e-9
        assert len(lineup) <= 5


def test_lineup_efficiency_demo():
    lg, box = _demo()
    eff = analysis.lineup_efficiency(lg, box)
    assert len(eff) == 60
    assert (eff.optimal >= eff.actual - 1e-9).all()
    assert eff.efficiency.between(0, 1).all()
    # actual starter totals match ESPN's reported team scores
    games = analysis.team_weeks(lg).set_index(["week", "team_id"])
    for r in eff.itertuples():
        assert abs(games.loc[(r.week, r.team_id), "points"] - r.actual) < 0.01
    rec = analysis.optimal_record(lg, eff)
    assert rec["Actual W"].sum() == 30 and rec["Optimal-lineup W"].sum() <= 30


def test_head_to_head_and_records():
    lg, _ = _demo()
    h2h = analysis.head_to_head(lg)
    assert h2h.shape == (10, 10)
    notable = analysis.notable_games(lg)
    assert len(notable["Highest scores"]) == 5
