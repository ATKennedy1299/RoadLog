# Fantasy Analyzer (Android)

An Android app for analyzing an ESPN fantasy football league, including
private leagues. It's a separate app from RoadLog that shares this Gradle
project and CI.

## Features

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

## Getting the APK

Every push runs `.github/workflows/build.yml`, which builds both apps and
uploads **fantasy-analyzer-debug-apk** as a workflow artifact. Download it from
the run's page on GitHub, unzip, and install the APK on your phone (allow
installs from your browser/files app when Android asks).

Or build locally with Android Studio or `./gradlew :fantasy:assembleDebug`;
the APK lands in `fantasy/build/outputs/apk/debug/`.

## Connecting your private league

1. Open the app → **Connect my league** → **Sign in with ESPN**.
2. Log in on ESPN's own page, then open your league. The bar at the top
   confirms once it has your sign-in and league ID; tap **Use**.
3. Tap **Load my league**.

Behind the scenes the app reads the `espn_s2` and `SWID` cookies that ESPN
sets when you sign in; that's what ESPN's API needs for private leagues.
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

Seasons from 2018 on are supported.
