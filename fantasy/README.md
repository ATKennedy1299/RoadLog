# Fantasy Analyzer (Android)

An Android app for analyzing an ESPN fantasy football league, including
private leagues. It's a separate app from RoadLog that shares this Gradle
project and CI.

## Features

- **Matchup**: ESPN-style matchup page for any week (arrows to go back and
  forward): every game that week in a scoreboard strip, both teams' points
  with projections and win chances, and lineups side by side by position
  with each player's NFL game, kickoff or Live/Final, points and
  projection. Bench on demand. Weeks other than the current one load from
  ESPN the first time you open them.
- **My Team** (ESPN-style roster for any week, ‹ › to switch): slot pills you
  tap to move players, headshots, % rostered / % started, kickoff or live
  score with the opponent's rank against the position (green = soft, red =
  tough), stat lines, and points over projection.
  Also: this week's matchup with your win chances, start/sit advice
  from ESPN's projections (best lineup, what to swap, how many points it's
  worth), and warnings for starters who are on bye, out, doubtful or
  questionable, or empty slots. Players whose games have started are left
  where they are.
  You can also set your lineup here: tap a player to move them (a full slot
  swaps), or apply the suggested lineup, then **Save to ESPN**. The app checks
  the lineup first (slot limits, eligibility, locked players), shows every
  move for confirmation, sends it the way ESPN's website does, and reloads.
  ESPN doesn't publish this API, so if it changes, saving may stop working
  while everything else carries on.
- **Waivers**: free agents worth more than the weakest player you have at
  their position (60% ESPN projection, 40% last-3-week average), fill-ins for
  starters who can't play this week, trending adds, and the best available at
  each position.
- **Playoffs**: odds of making the playoffs, getting a bye and winning the
  title, projected wins, average seed, and what your next game is worth (odds
  if you win vs. lose), from 10,000 simulations of the rest of the season.
  Below that, remaining strength of schedule: every team ranked by how good
  its remaining opponents are, with how many of those games an average team
  would win, and your remaining opponents listed.
- **Standings**: record, points for/against, all-play record (your record if
  you'd played every team every week), expected wins, and luck.
- **Power rankings**: 50% all-play win rate, 30% season scoring average, 20%
  last 3 weeks, with movement vs. the standings and a recent-form list.
- **Luck**: wins above/below expected, and a *schedule swap* grid showing each
  team's record with every other team's schedule.
- **Lineups**: each manager's points as a share of their best possible lineup
  (exact solver; handles FLEX/superflex), points left on the bench, the record
  everyone would have with perfect lineups, the worst lineup calls of the
  season, and a week-by-week lineup inspector.
- **Scoring**: weekly score chart per team vs. the league average, and scoring
  consistency.
- **Head-to-head** grid and **records** (high/low scores, blowouts, closest
  games, most points in a loss).
- **Demo mode** with a made-up league, to look around before connecting.

### Polish and speed

- **Fast start**: ESPN's answers are saved on the phone, so the app opens on
  the last data instantly and refreshes in the background. Finished weeks
  never change, so they're only ever downloaded once.
- **Live mode** (LIVE in the top bar): refreshes the current week's lineups
  and NFL scores every minute while games are on (every 5 otherwise), only
  while the app is open. Live game state, scores and clocks come from ESPN's
  public NFL scoreboard.
- **Photos and logos**: player headshots and NFL logos from ESPN's image
  server, fantasy team logos from your league.
- **Tap any player** for a sheet with this week's game and stat line, a game
  log from the league's box scores, and upcoming opponents with how they rank
  against the position.
- Saving a lineup re-fetches only that week instead of reloading everything.

## Getting the APK

Every push runs `.github/workflows/build.yml`, which builds both apps and
uploads **fantasy-analyzer-debug-apk** as a workflow artifact. Download it from
the run's page on GitHub, unzip, and install the APK on your phone (allow
installs from your browser/files app when Android asks).

Or build locally with Android Studio or `./gradlew :fantasy:assembleDebug`;
the APK lands in `fantasy/build/outputs/apk/debug/`.

## Connecting your private league

1. Open the app → **Connect my league** → **Sign in with ESPN**.
2. Sign in on ESPN's page. Once the bar at the top says
   *Signed in*, tap **Done**.
3. The app lists your football leagues; pick one and tap **Load my league**.
   (If the list doesn't come up, open your league in the sign-in page so the
   app can read its ID from the URL, or type the ID in.)

Behind the scenes the app uses the cookies ESPN sets when you sign in
(`espn_s2` and `SWID`); that's what ESPN's API needs for private leagues. It
sends exactly what the sign-in page would, and if ESPN rejects that it tries
the other cookie combinations it has seen. When ESPN still says no, the app
shows ESPN's own reason.
They're stored encrypted on the phone (Android Keystore) and only sent to
ESPN. App backups are disabled so they never leave the device. If ESPN logs you
out, sign in again from League setup.

If the in-app sign-in gives you trouble, **Enter cookies manually instead**
lets you paste `espn_s2` and `SWID` copied from a desktop browser's developer
tools (Application/Storage → Cookies → fantasy.espn.com).

## Code layout

- `data/`: ESPN API client, JSON parsing, models, demo data, settings storage.
- `analysis/Analysis.kt`: all the stats, as pure functions.
- `ui/`: Jetpack Compose screens.

`data/` (minus `SettingsStore`) and `analysis/` are plain Kotlin with no Android
dependencies; `src/test` covers them (`./gradlew :fantasy:testDebugUnitTest`).

Your team is picked automatically from the ESPN account you signed in with;
change it with the picker on the My Team tab.

Seasons from 2018 on are supported.
