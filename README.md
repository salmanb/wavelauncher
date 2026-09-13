# wavelauncher
100% vibe coded by glm-5.3-flash android launcher
After my favorite launcher was corrupted by corporate greed I wanted something new. I like the Niagara launcher but I don't want to pay $45 for life-time subscription for a launcher, that's just silly. I am also not a Kotlin or Android dev, so what to do?

With the recent release of https://github.com/MiaAI-Lab/GLM-5.3-Flash-EXL3-2x-DGX-Sparks I was able to get this launcher created 100% vibe coded.

## Features

### Home
- Minimal single-column app list built from PackageManager, every row shows its real app icon
- **Alphabet rail** on the right edge — fixed letter slots; the letter of the section at the top of the list renders bigger in the accent color. Drag the rail to scrub by letter; a bubble shows the target letter floating above your finger. While scrolling the list by touch, a chip with the current letter floats above the touch point.
- **Dock** — a bottom bar apps and folders can be added to. Drag an app from the list onto the dock to add it; drag onto a folder to add it to that folder; hold-still on a dock item for a menu (open / remove); hold-then-move to reorder or drag out to remove. Long-press the empty dock to create a new folder.
- **Folders** — live only in the dock. Tap a folder to open its apps in a card that pops up right above the folder icon (4-column grid). Drag apps into a folder to add them. Long-press an app inside a folder for the app menu.
- **App menu** — long-press any app (list or folder): add to dock, folders, app info, uninstall.
- **Work profile** — show work apps in the list (badged icons, correct user-handle launch) and pause/unpause the profile from Settings.
- Settings is the last row of the app list.

### Widgets
- Real `AppWidgetHost` integration: widget picker with live preview grid
- Widgets sit above the app list
- **Update-proof persistence** — widget ids are re-derived from the system on every start (orphaned bindings get adopted), transient post-update nulls are retried, never pruned on first miss
- Manage / remove widgets from Settings → Widgets

### Search (swipe down on home)
- Apps by name, contacts (opens contact card, prompts for permission), inline calculator with a real expression parser (`12*7+2`, `23*4/5`), web search fallback

### Theming
- Dark / Light / **Wallpaper** modes
- Text-readability dim slider for wallpaper mode (black scrim 0–90%)
- 4 accent colors, 4 font choices, icon shape rounded/circle
- Settings persist in SharedPreferences

### Notifications
- Per-app notification dots on home rows via `NotificationListenerService` (grant Notification access on first run)

## Install (sideload)

Build the APK (below), then:

1. Copy `android/WaveLauncher.apk` to your phone (USB, `adb install`, or upload).
2. Open it, allow "install unknown apps" when prompted.
3. First run:
   - Settings → Apps → Default apps → Home app → **Wave Launcher**
   - Settings → Special app access → Notification access → enable **Wave Launcher** (powers the dots)
   - Settings → Widgets → Add widget to home to place widgets.

## Build from source

No Gradle — plain pipeline script.

Requirements: JDK 17, Android SDK (platform-34, build-tools 34.0.0), kotlinc 2.0.21. Paths default to `~/.local/opt` (override in `build.sh`).

```
cd android && ./build.sh
```

Pipeline: aapt2 compile/link → kotlinc → d8 (Kotlin stdlib merged) → zipalign → apksigner. A debug keystore is generated on first build.

## Screenshots

Drop screenshots into `screenshots/` and they render here:

<p float="left">
  <img src="screenshots/home.png" width="200" />
  <img src="screenshots/dock.png" width="200" />
  <img src="screenshots/folder.png" width="200" />
</p>

- `home.png` — app list + alphabet rail
- `dock.png` — dock with apps and folders
- `folder.png` — folder popup grid

## Repo layout

- `android/` — the launcher (Kotlin, no Gradle; `build.sh` is the build)
- `android/src/com/salman/wavelauncher/WaveRailView.kt` — the alphabet rail (scrub, current-letter highlight, touch bubble)
- `mock/` — the original interactive HTML mock the launcher was designed from, with Playwright verification scripts

See [CHANGELOG.md](CHANGELOG.md) for release history.
