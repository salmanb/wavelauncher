# wavelauncher
100% vibe coded by glm-5.3-flash android launcher
After my favorite launcher was corrupted by corporate greed I wanted something new. I like the Niagara launcher but I don't want to pay $45 for life-time subscription for a launcher, that's just silly. I am also not a Kotlin or Android dev, so what to do?

With the recent release of https://github.com/MiaAI-Lab/GLM-5.3-Flash-EXL3-2x-DGX-Sparks I was able to get this launcher created 100% vibe coded.
# Wave Launcher

Open-source, Niagara-style Android launcher. This is the v0.1 working skeleton.

## Install (sideload)

The built APK is at `android/WaveLauncher.apk` (signed, debug keystore `android/debug.keystore`, storepass `wave123`).

1. Copy `android/WaveLauncher.apk` to your phone (USB, `adb install`, or upload).
2. Open it, allow "install unknown apps" when prompted.
3. First run:
   - Settings -> Apps -> Default apps -> Home app -> **Wave Launcher**
   - Settings -> Special app access -> Notification access -> enable **Wave Launcher** (this is what powers the dots on app rows)
   - Contacts search will prompt for contacts permission on first search.

## Build from source

Requirements (already installed under `~/.local/opt`): JDK 17, Android SDK (platform-34, build-tools 34.0.0), kotlinc 2.0.21.

```
cd android && ./build.sh
```

Pipeline: aapt2 compile/link -> kotlinc -> d8 -> zipalign -> apksigner. No Gradle.

## What works (v0.1)

- Home list built from PackageManager (icons on first N rows, letter glyphs below — configurable)
- Wave alphabet rail on the right: letters swing out while scrolling (velocity-driven), drag to scrub by letter with magnifier
- Swipe down on home -> search: apps, contacts (opens contact card), inline calculator (`12*7+2`), web fallback
- App drawer (bottom-right button) with search filter
- Notification dots per app via NotificationListenerService
- Settings: dark/light theme, 4 accent colors, clock size, icon count, 24h clock (persisted in SharedPreferences)
- Set as HOME (default launcher intent filters present)

## Not yet (v0.2 candidates)

- Pop-up folders, widgets (calendar/weather/usage), notification inline reply, wallpaper blur/dim, letter-rail magnifier polish, icon packs.
