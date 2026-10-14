# Termux-Ghostty

An experimental Android terminal app using libghostty-vt as the terminal backend + added opinionated qol features.

> ⚠️ **NOTICE**: This is an **unofficial** fork and is **not affiliated with ghostty-org or termux**. It combines features from both projects under their respective licenses (GPLv3 and MIT).

> 🤖 **AI Assistance**: This project is developed with the assistance of AI coding tools.

## Features

- **Ghostty integration** — Uses Ghostty-backed VT parsing and terminal state management inside the app.
- **Workspaces and tabs** — Each workspace has a root folder and its own tabs: terminals, a file browser, and a Markdown / HTML / text viewer. The tabs grid shows a live preview of every terminal and switches or manages workspaces. Workspaces and tabs are restored after the app is killed, with each shell restarted in its last directory.
- **Floating key bar** — Esc, Ctrl, Alt (tap to arm, tap again to lock), an arrow joystick and Tab float above the keyboard, next to the button that opens the tabs grid.
- **Clickable terminal links** — `terminal-onclick-url-open = true` enables tap-to-open links; `terminal-onclick-url-open-when-mouse-tracking-active = true` prefers link taps over terminal mouse tracking.
- **Remember soft keyboard state** — `remember-soft-keyboard-state = true` restores the last soft keyboard visibility when reopening the app.
- **Material You theming** — `material-you-theme = "disabled" | "light" | "dark" | "black" | "system"` themes the app chrome and terminal ANSI palette with wallpaper-derived Material 3 colors on Android 12+. `black` uses dynamic accents on pure black. When enabled, `colors.properties` is ignored. Recreates automatically on wallpaper changes.

### Settings

Settings are edited in the app and stored with Jetpack DataStore. Any key in `~/.termux/termux.toml` overrides the in-app value; run `termux-reload-settings` after editing it. `allow-external-apps` can only be set in this file.

```toml
material-you-theme = "system"
allow-external-apps = true
```

`~/.termux/termux.properties` is no longer read. `colors.properties` and `font.ttf` work as before.

The Customization settings cover the wallpaper, cursor trail, effect frame rate, animations and custom font. Each one is also a `termux.toml` key:

| Key | Default | Values |
| --- | --- | --- |
| `wallpaper` | `true` | `true`, `false`. Shows `~/.termux/wallpaper` when the file exists |
| `wallpaper-background-opacity` | `0.7` | `0` to `1` |
| `wallpaper-scaling` | `"center-crop"` | `"center-crop"`, `"fit-center"`, `"fit-xy"`, `"center"` |
| `cursor-trail` | `"warp"` | `"none"`, `"warp"`, `"sweep"`, `"tail"` |
| `arrow-joystick` | `"swipe"` | `"swipe"`, `"hold"` |
| `visual-effect-frame-rate` | `"vsync"` | `"vsync"`, `"30"`, `"60"`, `"90"`, `"120"` |
| `animations` | `true` | `true`, `false` |
| `custom-font-for-ui` | `false` | `true`, `false` |

Choosing a wallpaper copies the image to `~/.termux/wallpaper`, and choosing a font copies it to `~/.termux/font.ttf`. Removing or resetting either one deletes the file.

## Ecto (`compose-app`)

A vibe coding companion for Android — an SSH client with Herdr integration and a Ghostty terminal backend, built as a separate Jetpack Compose app (`com.mrndtvndv.term`).

### Screenshots

| Local & SSH | Herdr Workspaces | Git Review |
| :---: | :---: | :---: |
| ![Local & SSH](compose-app/screenshots/local-and-ssh.png) | ![Herdr Workspaces](compose-app/screenshots/herdr-workspaces-integration.png) | ![Git Review](compose-app/screenshots/git-tab.png) |
| **Diff Viewer** | **SFTP Browser** | **Cursor Effects** |
| ![Diff Viewer](compose-app/screenshots/diff.png) | ![SFTP Browser](compose-app/screenshots/sftp.png) | ![Cursor Effects](compose-app/screenshots/shaders.png) |

### Features

- **Ghostty terminal backend** — Native VT parsing and terminal state via `libghostty-vt`, rendered with hardware-accelerated OpenGL ES by default.
- **SSH client** — Native SSH sessions with a server manager (add/edit servers, one-tap connect).
- **Herdr integration** — Queries `herdr workspace list` / `herdr pane list` and tracks navigation state per workspace.
- **Git review tab** — Review, stage, and commit changes, edit commit messages, and reset to commits from the app.
- **SFTP tab** — Browse and transfer files on the connected server, synced to the active workspace's directory.
- **Cursor effects** — Warp, Sweep, and Tail cursor trails (adapted from ghostty-cursor-shaders).
- **Local terminal sessions** — Drop into a local shell when you don't need SSH.
- **Split & tabbed workspaces** — Multiple terminals per workspace.
- **Material You theming** — Dynamic wallpaper-derived colors.
- **Extra keys toolbar** — Configurable extra keys row above the soft keyboard.

## Status

Experimental and fast-moving.
