"""League analytics. Everything here is pure: League/RosterEntry in, DataFrames out."""
from __future__ import annotations

from functools import lru_cache

import pandas as pd

from league import INACTIVE_SLOTS, SLOTS, League, RosterEntry

RECENT_WEEKS = 3


def team_weeks(league: League, include_playoffs: bool = False) -> pd.DataFrame:
    """One row per team per completed game."""
    rows = []
    for m in league.matchups:
        if not m.complete or m.away_id is None or (m.is_playoff and not include_playoffs):
            continue
        for tid, pts, oid, opts in (
            (m.home_id, m.home_points, m.away_id, m.away_points),
            (m.away_id, m.away_points, m.home_id, m.home_points),
        ):
            result = "W" if pts > opts else "L" if pts < opts else "T"
            rows.append({
                "week": m.week, "team_id": tid, "team": league.teams[tid].name,
                "points": pts, "opp_id": oid, "opponent": league.teams[oid].name,
                "opp_points": opts, "result": result, "margin": pts - opts,
            })
    return pd.DataFrame(rows, columns=[
        "week", "team_id", "team", "points", "opp_id", "opponent", "opp_points", "result", "margin",
    ])


def all_play(games: pd.DataFrame) -> pd.DataFrame:
    """Record each team would have if it played every other team every week."""
    rows = []
    for week, wk in games.groupby("week"):
        scores = wk["points"].tolist()
        for _, r in wk.iterrows():
            wins = sum(s < r.points for s in scores)
            ties = sum(s == r.points for s in scores) - 1
            losses = len(scores) - 1 - wins - ties
            rows.append({"team_id": r.team_id, "week": week, "ap_w": wins, "ap_l": losses, "ap_t": ties})
    df = pd.DataFrame(rows, columns=["team_id", "week", "ap_w", "ap_l", "ap_t"])
    return df.groupby("team_id")[["ap_w", "ap_l", "ap_t"]].sum()


def _minmax(s: pd.Series) -> pd.Series:
    span = s.max() - s.min()
    return (s - s.min()) / span if span else s * 0 + 0.5


def standings(league: League) -> pd.DataFrame:
    """Standings plus all-play record, luck, and a power ranking.

    Luck = actual wins minus the wins expected from all-play win rate. A team
    that keeps putting up the week's 2nd-best score but drawing the top scorer
    is unlucky; one that wins with the 9th-best score is lucky.
    """
    games = team_weeks(league)
    if games.empty:
        return pd.DataFrame()

    g = games.groupby("team_id")
    df = pd.DataFrame({
        "W": g["result"].apply(lambda r: (r == "W").sum()),
        "L": g["result"].apply(lambda r: (r == "L").sum()),
        "T": g["result"].apply(lambda r: (r == "T").sum()),
        "PF": g["points"].sum(),
        "PA": g["opp_points"].sum(),
        "Avg": g["points"].mean(),
        "StdDev": g["points"].std().fillna(0),
    })
    last_weeks = sorted(games["week"].unique())[-RECENT_WEEKS:]
    df["Recent"] = games[games["week"].isin(last_weeks)].groupby("team_id")["points"].mean()

    ap = all_play(games)
    df = df.join(ap)
    ap_games = df["ap_w"] + df["ap_l"] + df["ap_t"]
    df["AP%"] = (df["ap_w"] + 0.5 * df["ap_t"]) / ap_games
    played = df["W"] + df["L"] + df["T"]
    df["xW"] = df["AP%"] * played
    df["Luck"] = (df["W"] + 0.5 * df["T"]) - df["xW"]

    df["Power"] = 100 * (
        0.5 * df["AP%"] + 0.3 * _minmax(df["Avg"]) + 0.2 * _minmax(df["Recent"])
    )

    df.insert(0, "Team", [league.teams[t].name for t in df.index])
    df.insert(1, "Owner", [league.teams[t].owner for t in df.index])
    df["All-Play"] = df.apply(
        lambda r: f"{int(r.ap_w)}-{int(r.ap_l)}" + (f"-{int(r.ap_t)}" if r.ap_t else ""), axis=1
    )
    df = df.drop(columns=["ap_w", "ap_l", "ap_t"])
    df = df.sort_values(["W", "T", "PF"], ascending=False)
    df.insert(0, "Rank", range(1, len(df) + 1))
    df["Power Rank"] = df["Power"].rank(ascending=False, method="min").astype(int)
    return df


def weekly_scores(league: League) -> pd.DataFrame:
    games = team_weeks(league)
    return games.pivot(index="week", columns="team", values="points")


def head_to_head(league: League) -> pd.DataFrame:
    """Actual head-to-head records, row team vs. column team (all seasons games incl. playoffs)."""
    games = team_weeks(league, include_playoffs=True)
    names = sorted(t.name for t in league.teams.values())
    out = pd.DataFrame("", index=names, columns=names)
    for (team, opp), grp in games.groupby(["team", "opponent"]):
        w, l, t = ((grp["result"] == x).sum() for x in "WLT")
        out.loc[team, opp] = f"{w}-{l}" + (f"-{t}" if t else "")
    for n in names:
        out.loc[n, n] = "—"
    return out


def schedule_swap(league: League) -> pd.DataFrame:
    """Wins each team (row) would have with another team's (column) schedule.

    When the row team appears on the borrowed schedule, it plays the schedule's
    owner instead, which is the usual convention for this table.
    """
    games = team_weeks(league)
    if games.empty:
        return pd.DataFrame()
    score = {(r.week, r.team_id): r.points for r in games.itertuples()}
    opp = {(r.week, r.team_id): r.opp_id for r in games.itertuples()}
    weeks = sorted(games["week"].unique())
    ids = sorted(league.teams, key=lambda t: league.teams[t].name)

    data = {}
    for b in ids:
        col = {}
        for a in ids:
            wins = 0.0
            for w in weeks:
                if (w, a) not in score or (w, b) not in opp:
                    continue
                o = opp[(w, b)]
                if o == a:
                    o = b
                if a == o:
                    continue
                mine, theirs = score[(w, a)], score[(w, o)]
                wins += 1 if mine > theirs else 0.5 if mine == theirs else 0
            col[league.teams[a].name] = wins
        data[league.teams[b].name] = col
    return pd.DataFrame(data)


def notable_games(league: League) -> dict[str, pd.DataFrame]:
    games = team_weeks(league, include_playoffs=True)
    if games.empty:
        return {}
    winners = games[games["margin"] >= 0].drop_duplicates(subset=["week", "points", "opp_points"])
    cols = ["week", "team", "points", "opponent", "opp_points", "margin"]
    return {
        "Highest scores": games.nlargest(5, "points")[["week", "team", "points", "opponent", "result"]],
        "Lowest scores": games.nsmallest(5, "points")[["week", "team", "points", "opponent", "result"]],
        "Biggest blowouts": winners.nlargest(5, "margin")[cols],
        "Closest games": winners.nsmallest(5, "margin")[cols],
        "Most points in a loss": games[games["result"] == "L"].nlargest(5, "points")[cols],
    }


# --- Lineup efficiency -------------------------------------------------------

def optimal_lineup(roster: list[RosterEntry], slot_counts: dict[int, int]) -> tuple[float, list[tuple[str, RosterEntry]]]:
    """Exact best possible starting lineup for one team-week.

    Dynamic program over players with the remaining count of every starting
    slot as state. Rosters are ~16 players and ~7 slot types, so this is tiny,
    and unlike a greedy fill it handles overlapping FLEX/OP/RB-WR slots correctly.
    """
    slot_ids = sorted(s for s, c in slot_counts.items() if c and s not in INACTIVE_SLOTS)
    players = [p for p in roster if p.slot_id != 21]  # players stashed on IR couldn't have started
    options = [[i for i, s in enumerate(slot_ids) if s in p.eligible_slots] for p in players]

    @lru_cache(maxsize=None)
    def best(i: int, remaining: tuple[int, ...]) -> tuple[float, tuple[int, ...]]:
        if i == len(players) or not any(remaining):
            return 0.0, ()
        top = best(i + 1, remaining)
        choice = (top[0], (-1,) + top[1])
        for k in options[i]:
            if remaining[k]:
                nxt = remaining[:k] + (remaining[k] - 1,) + remaining[k + 1:]
                sub = best(i + 1, nxt)
                if players[i].points + sub[0] > choice[0]:
                    choice = (players[i].points + sub[0], (k,) + sub[1])
        return choice

    total, picks = best(0, tuple(slot_counts[s] for s in slot_ids))
    lineup = [(SLOTS.get(slot_ids[k], str(slot_ids[k])), players[i]) for i, k in enumerate(picks) if k >= 0]
    return total, lineup


def lineup_efficiency(league: League, box: dict[int, dict[int, list[RosterEntry]]]) -> pd.DataFrame:
    """Per team-week actual vs optimal points. ``box`` is week -> team id -> roster."""
    rows = []
    for week, rosters in box.items():
        for tid, roster in rosters.items():
            actual = sum(p.points for p in roster if p.started)
            optimal, lineup = optimal_lineup(roster, league.lineup_slot_counts)
            optimal = max(optimal, actual)
            should_start = {id(p) for _, p in lineup}
            benched_best = max(
                (p for p in roster if not p.started and id(p) in should_start),
                key=lambda p: p.points, default=None,
            )
            rows.append({
                "week": week, "team_id": tid, "team": league.teams[tid].name,
                "actual": actual, "optimal": optimal, "left_on_bench": optimal - actual,
                "efficiency": actual / optimal if optimal else 1.0,
                "top_benched": f"{benched_best.name} ({benched_best.points:.1f})" if benched_best else "",
            })
    return pd.DataFrame(rows, columns=[
        "week", "team_id", "team", "actual", "optimal", "left_on_bench", "efficiency", "top_benched",
    ])


def optimal_record(league: League, eff: pd.DataFrame) -> pd.DataFrame:
    """What each team's record would be if both sides had set their best lineup."""
    if eff.empty:
        return pd.DataFrame()
    opt = {(r.week, r.team_id): r.optimal for r in eff.itertuples()}
    games = team_weeks(league)
    games = games[games.apply(lambda r: (r.week, r.team_id) in opt and (r.week, r.opp_id) in opt, axis=1)]
    rows = []
    for r in games.itertuples():
        mine, theirs = opt[(r.week, r.team_id)], opt[(r.week, r.opp_id)]
        rows.append({"team": r.team, "actual_w": r.result == "W", "optimal_w": mine > theirs})
    df = pd.DataFrame(rows)
    out = df.groupby("team")[["actual_w", "optimal_w"]].sum().astype(int)
    out.columns = ["Actual W", "Optimal-lineup W"]
    out["Diff"] = out["Optimal-lineup W"] - out["Actual W"]
    return out.sort_values("Diff", ascending=False)
