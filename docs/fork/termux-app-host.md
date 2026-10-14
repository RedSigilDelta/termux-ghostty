# Termux app host (`app`)

The `app` module is the local Termux host: the bootstrap, `TermuxService`, plugins, and a Compose UI on top of the shared terminal stack (`terminal-emulator` → `terminal-compose-session` → `terminal-compose-view`). It has no SSH, SFTP, Review or Herdr features; those live in Ecto (`compose-app`).

## Layers

| Layer | Language | Notes |
|---|---|---|
| UI: `TermuxActivity`, `TermuxScreen`, key bar, tabs grid, settings | Kotlin + Compose | Rewritten in the Compose port. |
| Terminal clients: `TermuxTerminalViewClient`, `TermuxTerminalSessionActivityClient` | Kotlin | Straight ports of the Java clients. |
| Backend: `TermuxService`, `TermuxInstaller`, `RunCommandService`, receivers, `TermuxDocumentsProvider` | Java | Unchanged; converted only when touched for another reason. |
| `termux-shared` | Java | Shared with the plugin ecosystem; keep its contracts stable. |

## Screen

`TermuxActivity` sets a single `ComposeView` as its content and binds `TermuxScreen` (`TermuxScreen.kt`):

- The terminal: the existing `TerminalComposeView` hosted in `AndroidView`, inset by the `terminal-margin-*` settings and `WindowInsets.safeDrawing`.
- `KeyBar` floats over the bottom of the terminal: Esc, Ctrl and Alt (tap to arm, tap again to lock), an arrow joystick, Tab, and a tab button. The button's badge shows the tab count and its icon shows the current tab's process.
- `TabsGrid` (`workspace/ui/TabsGrid.kt`) is a full-screen overlay opened from the tab button or Ctrl+Alt+Right. It lists the active workspace's tabs with a live preview of each terminal (`TerminalThumbnail`), lets you rename, close or add tabs (terminal or files), and switches or manages workspaces from a dropdown.
- `FilesTab` and `ViewerTab` (`workspace/ui/`) cover the terminal while their tab is active and bring their own floating bar. Files browses the filesystem from the workspace root; Markdown, HTML and text files open in a Viewer tab, anything else in another app through the Termux file provider.
- The `termux.toml` errors dialog.

A workspace (`workspace/`) groups terminal, files and viewer tabs under a root folder. `WorkspaceRepository` persists the list to `workspaces.json` and owns the live `TerminalSession` for each terminal tab; closing a tab kills its shell.

Java/Kotlin code never calls composables directly. It talks to small state holders on the main thread: `TermuxScreenState` (tabs grid visibility, margins, IME visibility, font stamp), `ModifierKeys` (key bar Ctrl / Alt state) and `TabsUiState` (an immutable snapshot of the workspaces and tabs, refreshed whenever the workspace state changes).

## Settings

`TermuxSettings` (`settings/TermuxSettings.kt`) is one `@Serializable` data class holding every key with its default. Effective values are layered:

```
defaults  <-  DataStore (edited in Settings)  <-  ~/.termux/termux.toml (edited by the user)
```

- `TermuxSettingsRepository` exposes `effective: StateFlow<EffectiveSettings>` (settings, keys set in the file, problems) and `current()` for synchronous Java callers. The first DataStore read happens once at startup.
- `TermuxConfigFile` parses `termux.toml` with tomlj and merges it over the stored values at the JSON tree level. Unknown keys and bad values are skipped and reported; a syntax error ignores the whole file. Problems are shown in a dialog on launch and after `termux-reload-settings`.
- `allow-external-apps` is only honoured from `termux.toml` and fails closed.
- `termux-shared` reads the handful of settings it needs through `TermuxSettingsProvider`, which the app installs in `TermuxApplication.onCreate`.
- `TermuxAppSharedPreferences` (font size, log level, soft keyboard switches) and the plugin preference classes remain SharedPreferences because other Termux apps read them through the shared UID.

The settings screen (`settings/ui/SettingsScreen.kt`) edits every key. Keys set in `termux.toml` show as locked. When the user returns to the terminal, `TermuxActivity.onStart` compares the current settings and file stamps with what it last applied and reloads, recreating only for theme changes.

## Files in `~/.termux`

| File | Purpose |
|---|---|
| `termux.toml` | Settings overrides (see README). |
| `colors.properties` | Terminal colors (Termux:Styling convention; ignored with Material You). |
| `font.ttf` | Terminal font; optionally the whole UI with `custom-font-for-ui`. |
| `wallpaper` | Terminal wallpaper image, decoded off the main thread. |

The Settings → Customization pickers copy into `font.ttf` and `wallpaper`, so scripts can manage them the same way.
