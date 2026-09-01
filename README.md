# wavelauncher
100% vibe coded by glm-5.3-flash android launcher
After my favorite launcher was corrupted by corporate greed I wanted something new. I like the Niagara launcher but I don't want to pay $45 for life-time subscription for a launcher, that's just silly. I am also not a Kotlin or Android dev, so what to do?

With the recent release of https://github.com/MiaAI-Lab/GLM-5.3-Flash-EXL3-2x-DGX-Sparks I was able to get this launcher created 100% vibe coded.

## Features

### Home
- Minimal single-column app list built from PackageManager, every row shows its real app icon
- **Wave alphabet rail** on the right edge — letters swing out over the list as their app row crosses the top of the screen, then dock tilted. Drag the rail to scrub by letter with a magnifier bubble showing the target letter.
  - **⚠ Known bug:** the wave motion is still buggy — letters do not consistently arc/flow the way Niagara's does. Treat the rail as a working letter-jump scrubber with experimental wave animation, not a finished effect. See `WaveRailView.kt`.
- **Categories** — create named groups (Favorites, Media, …), pinned at the top under a `CATEGORIES` banner, collapsible (tap the header), sorted A–Z, hidden until the first one exists. Long-press any app → Categories… to assign; long-press a category header to edit/delete.
- **Folders** — pop-up folder cards under a `FOLDERS` banner, also A–Z and hidden until non-empty. Long-press app → Folders…; tap a folder row to open its app list.
- **Work profile** — show work apps in the list (badged icons, correct user-handle launch) and pause/unpause the profile from Settings; a "PAUSED" banner shows when quiet mode is on.
- Settings is the last row of the app list.
- App drawer (bottom-right ⋮⋮ button) with instant filter.

### Widgets
- Real `AppWidgetHost` integration: widget picker with live preview grid (two columns)
- Place any widget **top of list** or **bottom bar** (e.g. Google search widget at the bottom)
- Widget config activities supported; list scrolls above bottom widgets
- **Update-proof persistence** — widget ids are re-derived from the system on every start (orphaned bindings get adopted), transient post-update nulls are retried, never pruned on first miss
- Manage / remove / move widgets from Settings → Widgets

### Search (swipe down on home)
- Apps by name, contacts (opens contact card, prompts for permission), inline calculator with a real expression parser (`12*7+2`, `23*4/5`), web search fallback

### Theming
- Dark / Light / **Wallpaper** modes; wallpaper picked via system image picker
- Text-readability dim slider for wallpaper mode (black scrim 0–90%)
- 4 accent colors, 4 font choices (applies to clock/date/rows), clock size 36–76sp, icon shape rounded/circle
- 24-hour clock toggle; settings persist in SharedPreferences

### Notifications
- Per-app notification dots on home rows via `NotificationListenerService` (grant Notification access on first run)

## Install (sideload)

Build the APK (below), then:

1. Copy `android/WaveLauncher.apk` to your phone (USB, `adb install`, or upload).
2. Open it, allow "install unknown apps" when prompted.
3. First run:
   - Settings → Apps → Default apps → Home app → **Wave Launcher**
   - Settings → Special app access → Notification access → enable **Wave Launcher** (powers the dots)
   - Contacts search prompts for contacts permission on first use.
   - Settings → Widgets → Add widget to home to place widgets; Manage / remove widgets to edit them later.

## Build from source

No Gradle — plain pipeline script.

Requirements: JDK 17, Android SDK (platform-34, build-tools 34.0.0), kotlinc 2.0.21. Paths default to `~/.local/opt` (override in `build.sh`).

```
cd android && ./build.sh
```

Pipeline: aapt2 compile/link → kotlinc → d8 (Kotlin stdlib merged) → zipalign → apksigner. A debug keystore is generated on first build.

## Known issues

- **Wave rail animation is buggy** — see above. Scrubbing/jump works; the arc effect does not yet match Niagara.
- Widget long-press (remove) is unreliable because host views can swallow touches — use Settings → Widgets → Manage instead.
- Folder/category dialogs are functional, not pretty.
- Icon packs, widget resizing, notification inline reply: not yet.

## Repo layout

- `android/` — the launcher (Kotlin, no Gradle; `build.sh` is the build)
- `android/src/com/salman/wavelauncher/WaveRailView.kt` — the wave rail (known-buggy animation lives here)
- `mock/` — the original interactive HTML mock the launcher was designed from, with Playwright verification scripts
