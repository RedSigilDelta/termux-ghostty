# Session Tabs View Mode

Shows sessions as a horizontal tab strip next to the terminal instead of only in the drawer.

## Configuration

Turn it on in **Settings → Sessions**, or in `~/.termux/termux.toml`:

```toml
use-session-tabs = true

# "bottom" (default): just above the extra keys
# "top": at the top of the terminal screen
session-tab-bar-position = "bottom"

# "left" (default), "center" or "right"
session-tab-bar-align = "left"
```

Keys set in `termux.toml` override the in-app values and show as locked in Settings. Changes apply when you return to the terminal; no restart is needed.

## Features

- **Horizontal tab strip**: sessions shown as scrollable tabs
- **Quick switching**: tap a tab to switch to that session
- **New session**: tap `+` for a new session, long-press it for a failsafe session
- **Rename**: long-press a tab
- **Visual indicators**:
  - the current session tab is highlighted (bold, primary container)
  - finished sessions are struck through
  - sessions that exited with a non-zero status are shown in the error color
- **Right alignment** lists sessions in reverse order so the newest sits next to `+`

The drawer is still available from a swipe at the left edge (or the `DRAWER` extra key) for settings, the session list, the keyboard toggle and named sessions.
