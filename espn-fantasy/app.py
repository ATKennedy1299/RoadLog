"""ESPN fantasy football league analyzer.

Run with:  streamlit run app.py
"""
from __future__ import annotations

import datetime as dt
import os

import altair as alt
import pandas as pd
import streamlit as st

import analysis
import sample_data
from espn_client import EspnClient, EspnError
from league import League, parse_box_scores, parse_league

st.set_page_config(page_title="Fantasy League Analyzer", page_icon="🏈", layout="wide")


def _secret(name: str, default: str = "") -> str:
    try:
        if name in st.secrets:
            return str(st.secrets[name])
    except Exception:  # no secrets.toml present
        pass
    return os.environ.get(name, default)


@st.cache_data(ttl=600, show_spinner="Loading league from ESPN…")
def load_espn(league_id: int, year: int, espn_s2: str, swid: str):
    client = EspnClient(league_id, year, espn_s2 or None, swid or None)
    raw = client.league()
    lg = parse_league(raw)
    box = {}
    for week in lg.completed_weeks(include_playoffs=True):
        periods = lg.matchup_periods.get(week, [week])
        if len(periods) != 1:
            continue  # multi-week playoff matchups: skip lineup analysis
        box[week] = parse_box_scores(client.box_scores(week, periods[0]), periods[0])
    return raw, box


@st.cache_data
def load_demo():
    raw, boxes = sample_data.generate()
    return raw, {w: parse_box_scores(b, w) for w, b in boxes.items()}


# --- Sidebar -----------------------------------------------------------------

with st.sidebar:
    st.header("League")
    demo = st.toggle("Use demo data", value=not _secret("ESPN_LEAGUE_ID"))
    if not demo:
        league_id = st.text_input("League ID", _secret("ESPN_LEAGUE_ID"),
                                  help="The leagueId=… number in your ESPN league URL.")
        year = st.number_input("Season", 2018, 2100, int(_secret("ESPN_YEAR", str(dt.date.today().year))))
        espn_s2 = st.text_input("espn_s2 cookie", _secret("ESPN_S2"), type="password")
        swid = st.text_input("SWID cookie", _secret("ESPN_SWID"), type="password",
                             help="Looks like {XXXXXXXX-XXXX-…}.")
        st.caption("Private leagues need both cookies. See README.md for how to find them. "
                   "They stay in this session's memory and are only sent to ESPN.")
        if st.button("Refresh data"):
            load_espn.clear()

if demo:
    raw, box = load_demo()
else:
    if not league_id.strip().isdigit():
        st.info("Enter your league ID and cookies in the sidebar, or switch on demo data.")
        st.stop()
    try:
        raw, box = load_espn(int(league_id), int(year), espn_s2, swid)
    except EspnError as e:
        st.error(str(e))
        st.stop()

league: League = parse_league(raw)
stand = analysis.standings(league)

st.title(f"🏈 {league.name}")
st.caption(f"{league.year} season · {len(league.teams)} teams · "
           f"{len(league.completed_weeks())} regular-season weeks complete")

if stand.empty:
    st.warning("No completed games yet this season.")
    st.stop()

tabs = st.tabs(["Standings & Power", "Weekly Scoring", "Luck & Schedules",
                "Lineup Efficiency", "Head-to-Head", "Records"])

# --- Standings ----------------------------------------------------------------

with tabs[0]:
    c1, c2, c3, c4 = st.columns(4)
    top = stand.sort_values("Power", ascending=False).iloc[0]
    c1.metric("Power #1", top.Team)
    c2.metric("Highest scoring", stand.loc[stand.PF.idxmax(), "Team"], f"{stand.PF.max():.1f} PF")
    c3.metric("Luckiest", stand.loc[stand.Luck.idxmax(), "Team"], f"{stand.Luck.max():+.1f} wins")
    c4.metric("Unluckiest", stand.loc[stand.Luck.idxmin(), "Team"], f"{stand.Luck.min():+.1f} wins")

    st.subheader("Standings")
    st.dataframe(
        stand[["Rank", "Team", "Owner", "W", "L", "T", "PF", "PA", "Avg", "All-Play", "xW", "Luck",
               "Power Rank"]],
        hide_index=True, width="stretch",
        column_config={
            "PF": st.column_config.NumberColumn(format="%.1f"),
            "PA": st.column_config.NumberColumn(format="%.1f"),
            "Avg": st.column_config.NumberColumn(format="%.1f"),
            "xW": st.column_config.NumberColumn("Expected W", format="%.1f",
                                                help="Wins expected from all-play win rate."),
            "Luck": st.column_config.NumberColumn(format="%+.1f",
                                                  help="Actual wins minus expected wins."),
        },
    )

    st.subheader("Power rankings")
    st.caption(f"50% all-play win rate · 30% season scoring average · 20% last {analysis.RECENT_WEEKS} weeks.")
    power = stand.sort_values("Power", ascending=False)
    st.altair_chart(
        alt.Chart(power).mark_bar().encode(
            x=alt.X("Power:Q", scale=alt.Scale(domain=[0, 100])),
            y=alt.Y("Team:N", sort="-x", title=None),
            tooltip=["Team", alt.Tooltip("Power:Q", format=".1f"), "All-Play",
                     alt.Tooltip("Avg:Q", format=".1f"), alt.Tooltip("Recent:Q", format=".1f")],
        ),
        width="stretch",
    )

# --- Weekly scoring -------------------------------------------------------------

with tabs[1]:
    games = analysis.team_weeks(league)
    picked = st.multiselect("Teams", sorted(games.team.unique()), default=None,
                            placeholder="All teams")
    view = games[games.team.isin(picked)] if picked else games
    st.altair_chart(
        alt.Chart(view).mark_line(point=True).encode(
            x=alt.X("week:O", title="Week"), y=alt.Y("points:Q", title="Points"),
            color=alt.Color("team:N", title="Team"),
            tooltip=["week", "team", alt.Tooltip("points:Q", format=".1f"), "opponent", "result"],
        ),
        width="stretch",
    )
    st.subheader("Scoring consistency")
    st.caption("Average vs. week-to-week spread. Top-left is what you want: high scoring, low variance.")
    st.altair_chart(
        alt.Chart(stand).mark_circle(size=140).encode(
            x=alt.X("StdDev:Q", title="Std. deviation", scale=alt.Scale(zero=False)),
            y=alt.Y("Avg:Q", title="Average points", scale=alt.Scale(zero=False)),
            tooltip=["Team", alt.Tooltip("Avg:Q", format=".1f"), alt.Tooltip("StdDev:Q", format=".1f")],
        ) + alt.Chart(stand).mark_text(dy=-12, fontSize=11).encode(x="StdDev:Q", y="Avg:Q", text="Team"),
        width="stretch",
    )
    st.subheader("Weekly scores")
    st.dataframe(analysis.weekly_scores(league).style.format("{:.1f}").background_gradient(axis=1),
                 width="stretch")

# --- Luck & schedules -----------------------------------------------------------

with tabs[2]:
    st.subheader("Actual vs. expected wins")
    luck = stand.assign(Actual=stand.W + 0.5 * stand["T"])
    st.altair_chart(
        alt.Chart(luck).mark_bar().encode(
            x=alt.X("Luck:Q", title="Wins above expected"),
            y=alt.Y("Team:N", sort="-x", title=None),
            color=alt.condition("datum.Luck > 0", alt.value("#2e9e5b"), alt.value("#d1495b")),
            tooltip=["Team", "Actual", alt.Tooltip("xW:Q", format=".1f"), "All-Play"],
        ),
        width="stretch",
    )
    st.subheader("Schedule swap")
    st.caption("Wins each team (row) would have with another team's (column) schedule. "
               "The diagonal is the team's real record.")
    swap = analysis.schedule_swap(league)
    st.dataframe(swap.style.format("{:g}").background_gradient(axis=1), width="stretch")

# --- Lineup efficiency -----------------------------------------------------------

with tabs[3]:
    eff = analysis.lineup_efficiency(league, box)
    if eff.empty:
        st.info("No box score data available.")
    else:
        summary = (eff.groupby("team")
                   .agg(actual=("actual", "sum"), optimal=("optimal", "sum"),
                        left_on_bench=("left_on_bench", "sum"), efficiency=("efficiency", "mean"))
                   .sort_values("efficiency", ascending=False).reset_index())
        summary["efficiency"] *= 100
        st.subheader("Manager efficiency")
        st.caption("Points scored as a share of the best possible lineup from the same roster each week.")
        st.dataframe(
            summary, hide_index=True, width="stretch",
            column_config={
                "team": "Team",
                "actual": st.column_config.NumberColumn("Actual pts", format="%.1f"),
                "optimal": st.column_config.NumberColumn("Optimal pts", format="%.1f"),
                "left_on_bench": st.column_config.NumberColumn("Left on bench", format="%.1f"),
                "efficiency": st.column_config.ProgressColumn("Efficiency", format="%.1f%%",
                                                              min_value=0, max_value=100),
            },
        )

        st.subheader("Record with perfect lineups (both sides)")
        st.dataframe(analysis.optimal_record(league, eff), width="stretch")

        st.subheader("Worst lineup decisions")
        worst = eff.nlargest(10, "left_on_bench")[["week", "team", "actual", "optimal", "left_on_bench",
                                                   "top_benched"]]
        st.dataframe(worst, hide_index=True, width="stretch",
                     column_config={"top_benched": "Best benched player",
                                    "left_on_bench": st.column_config.NumberColumn(format="%.1f")})

        st.subheader("Inspect a week")
        cw, ct = st.columns(2)
        week = cw.selectbox("Week", sorted(box))
        tid = ct.selectbox("Team", sorted(box[week]), format_func=lambda t: league.teams[t].name)
        roster = box[week][tid]
        total, lineup = analysis.optimal_lineup(roster, league.lineup_slot_counts)
        opt_ids = {id(p): slot for slot, p in lineup}
        st.dataframe(pd.DataFrame([{
            "Player": p.name, "Pos": p.position,
            "Your slot": analysis.SLOTS.get(p.slot_id, p.slot_id),
            "Optimal slot": opt_ids.get(id(p), "BE"), "Points": p.points,
        } for p in sorted(roster, key=lambda p: -p.points)]), hide_index=True, width="stretch")
        st.caption(f"Optimal total: {total:.1f}")

# --- Head-to-head ----------------------------------------------------------------

with tabs[4]:
    st.caption("Actual records this season, row team vs. column team (playoffs included).")
    st.dataframe(analysis.head_to_head(league), width="stretch")

# --- Records ----------------------------------------------------------------------

with tabs[5]:
    for title, df in analysis.notable_games(league).items():
        st.subheader(title)
        st.dataframe(df, hide_index=True, width="stretch")
