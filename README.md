# RoadLog (Phase 1)

GPS trip-logging app: foreground-service location tracking, Room-backed
trip/point storage, a GPS jump/accuracy filter, and a dark Compose UI.

## Building the APK

This sandbox's network policy blocks `dl.google.com`, which is where the
Android SDK platform, build-tools, and the Android Gradle Plugin itself are
distributed — so the APK could not be compiled here. The source has been
reviewed for correctness and is ready to build as-is. To get an APK:

**Option A — Android Studio (easiest)**
1. Open this folder in Android Studio (Iguana or newer).
2. Let it sync (downloads AGP 8.4.2, compileSdk 34, build-tools automatically).
3. Build → Build Bundle(s) / APK(s) → Build APK(s).
4. APK lands in `app/build/outputs/apk/debug/`.

**Option B — command line, on a machine with normal internet access**
```
./gradlew assembleDebug
```
(requires Android SDK cmdline-tools + `ANDROID_HOME` set, or let Android
Studio provision it first)

**Option C — GitHub Actions**
A workflow is already included at `.github/workflows/build.yml`. Push this
repo to GitHub and it will build `assembleDebug` on GitHub's runners (which
have full internet access) and upload the APK as a workflow artifact.

## Project layout

- `data/` — Room entities/DAOs (`Trip`, `LocationPoint`, `VehicleProfile`) and `TripRepository`, the single source of truth shared by the UI and the service.
- `service/LocationTrackingService.kt` — foreground service owning the FusedLocationProviderClient; survives process death by resuming from the DB's ACTIVE trip.
- `service/GpsFilter.kt` — rejects fixes with poor accuracy or implausible implied speed (GPS teleport glitches), without dropping them from storage.
- `ui/` — MainActivity, HomeViewModel, and the Compose `HomeScreen`.
