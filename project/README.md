# AntiCarsickness (Android)

A lightweight Android implementation inspired by iOS Vehicle Motion Cues.

## What it does

- Detects likely in-vehicle usage from motion sensors (`accelerometer` + `gyroscope`).
- Shows animated dots on screen edges.
- Dots shift with sensed acceleration direction and speed proxy.
- Supports global overlay above other apps (non-touch, low-obstruction edge cues).
- Supports one-tap toggle from Android Quick Settings tile (`Motion Cues`).
- Includes iOS-like options:
  - `Off / Automatic / On`
  - `Regular / Dynamic`
  - `Normal / Larger dots`
  - `Normal / More dots`

## Build

1. Install Android SDK and command-line tools.
2. Set environment variables:
   - `ANDROID_HOME` (or `ANDROID_SDK_ROOT`)
   - `JAVA_HOME` (JDK 17+)
3. Generate/restore Gradle wrapper if needed, then build:

```powershell
./gradlew.bat clean :app:assembleRelease
```

Or use the provided script (recommended):

```powershell
./scripts/build_apk.ps1 -Release -BuildAab -SignReleaseApk
```

## ABI packaging

`app/build.gradle.kts` is configured for ABI split output and universal APK:

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`
- `universal`

Build output path (release):

- `app/build/outputs/apk/release/`
- Signed APK names:
  - `app-arm64-v8a-release.apk`
  - `app-armeabi-v7a-release.apk`
  - `app-x86-release.apk`
  - `app-x86_64-release.apk`
  - `app-universal-release.apk`

For app-store style automatic architecture delivery, build AAB:

```powershell
./gradlew.bat :app:bundleRelease
```

Output:

- `app/build/outputs/bundle/release/app-release.aab`

App stores install the correct ABI split automatically from AAB.

## Notes

Sensor-only vehicle detection is heuristic. Thresholds in `VehicleMotionEngine.kt` can be tuned per device model.

## Global overlay usage

1. Open app and grant overlay permission.
2. Tap `Start Global Overlay`.
3. Pull down Quick Settings, edit tiles, add `Motion Cues`.
4. Use the tile to instantly start/stop overlay while staying in other apps.

## Version folders

- `old_version/v0.1.0/`: archived source snapshot and previous APK/AAB outputs.
- `release_cn/v0.2.0/`: current Chinese-named release packages.
