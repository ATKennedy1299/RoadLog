# ESPN Fantasy Football League Analyzer

A Streamlit dashboard for an ESPN fantasy football league, including private
leagues. It pulls your league straight from ESPN's API and shows:

- **Standings & power rankings**: record, points for/against, all-play record,
  expected wins, luck, and a power score (50% all-play win rate, 30% season
  scoring average, 20% last 3 weeks).
- **Weekly scoring**: score trends per team, a consistency chart (average vs.
  week-to-week spread), and a week-by-team score grid.
- **Luck & schedules**: wins above/below expected, plus a *schedule swap*
  table showing each team's record if it had played another team's schedule.
- **Lineup efficiency**: each team's actual points vs. the best lineup it
  could have set from its own roster, points left on the bench, the worst
  lineup calls of the season, the record every team would have with perfect
  lineups on both sides, and a week-by-week lineup inspector.
- **Head-to-head** records and **notable games** (high/low scores, blowouts,
  nail-biters, most points in a loss).

## Run it

```bash
cd espn-fantasy
pip install -r requirements.txt
streamlit run app.py
```

It opens with demo data. Switch off **Use demo data** in the sidebar and
enter your league details.

## Connecting a private league

You need your **league ID** and two cookies from a browser where you're logged
into ESPN:

1. Open your league on fantasy.espn.com. The league ID is the `leagueId=`
   number in the URL.
2. Open developer tools (F12 or Cmd+Opt+I) and go to **Application**
   (Chrome/Edge) or **Storage** (Firefox), then **Cookies** →
   `https://fantasy.espn.com`.
3. Copy the values of **`espn_s2`** (a long string) and **`SWID`** (looks like
   `{ABCD1234-...}`).

These cookies give access to your ESPN account, so treat them like a
password. The app keeps them only in memory and sends them only to ESPN. They
expire when you log out of ESPN, so grab fresh ones if you get an access error.

To avoid retyping them, set environment variables or create
`.streamlit/secrets.toml` (gitignored) next to `app.py`:

```toml
ESPN_LEAGUE_ID = "12345678"
ESPN_YEAR = "2026"
ESPN_S2 = "AEB..."
ESPN_SWID = "{ABCD1234-...}"
```

## Notes

- Supports seasons from 2018 on (ESPN's older seasons use a different endpoint).
- The optimal-lineup solver is exact (it handles FLEX, superflex/OP, and other
  overlapping slots) and leaves out players stashed on IR.
- Lineup analysis is skipped for playoff rounds that span two NFL weeks.

## Tests

```bash
python -m pytest tests
```
