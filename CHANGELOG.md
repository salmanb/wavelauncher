# Changelog

All notable changes to Wave Launcher.

## 0.12.4 (versionCode 84)

- App long-press shows the menu **before** release (menu-while-finger-down, like the dock)
- Moving the finger after a long-press dismisses the menu and converts it into a drag (14dp threshold — thumb tremor no longer triggers it)
- Long-press position is tracked at the Activity level, fixing a stale-position bug that spawned a full-screen drag box on long-press

## 0.12.0 (versionCode 80)

- **Removed categories** entirely (banners, headers, menus)
- **Folders live only in the dock** — no more FOLDERS section in the app list; all apps show in the plain A–Z list
- **Dock long-press** → "New folder" creates an empty folder directly in the dock
- **App menu additions**: App info (system settings), Uninstall (system prompt)
- Bug-audit fixes:
  - Widget cards no longer 2.6–3.5× too tall (`minHeight` is px, not dp)
  - Cancelling the widget bind dialog no longer deletes an existing widget
  - Widget placement dialog removed (placement was a no-op)
  - Light theme is readable (derived from theme mode, not a stuck flag)
  - Dock cells scroll sideways again; hold-still menu no longer launches the app behind it
  - Search loads app icons off the main thread (no stall on swipe-down); contacts permission asked once
  - Dock drag-out removal works (drag a dock item onto the list to remove it)
  - Folder dialogs no longer mutate state before Save; "New folder with this app" adds to an existing folder instead of wiping it
  - Dock storage migrated to JSON (folder names containing `|` no longer corrupt it)
  - Folder popup height sized by grid rows; anchored card fits narrow screens
  - Widget retry chain guarded against overlapping retries
  - Rail letter picking unbiased (tap directly on a letter)
  - Notification dots no longer double-count group summaries
  - Swipe-down-to-search threshold scaled by density
  - Dead code removed: DrawerActivity, dead settings, unused prefs

## 0.11.x

- Dock introduction: drag apps/folders from the list, drop-position ordering, resizable bar, transparency + icon-size settings
- Touch-driven drag architecture replacing Android DragEvent (views added mid-drag never receive events — the whole gesture is captured at the Activity level)
- Folder cells in the dock: accent outline, bold label, drop-app-to-folder
- Folder popup anchored above its dock icon with a 4-column grid
- Dashed-circle drop guides removed in favor of dock highlight
- Hold-450ms menus on dock items while the finger is down
- Play Protect warning fixed by removing `QUERY_ALL_PACKAGES` (the `<queries>` block covers app listing)

## 0.10.x

- Bottom-stack refactor: widgets topmost, clock removed, folder chips row
- Rounded folder chips with 3-icon previews

## 0.9.x

- Wave rail simplified: fixed letter slots + current-letter highlight + touch chip (offset tunable)
- Debug settings for rail hint offset
- 300dp default hint offset

## 0.8.x

- Categories and folders sections (later removed in 0.12.0)
- First public release on GitHub
