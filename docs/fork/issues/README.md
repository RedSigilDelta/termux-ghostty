# Terminal session lifecycle issues (post Compose-migration)

Static-analysis findings from the `terminal-view` → `terminal-compose-*` migration
(`a560b1a8 refactor: migrate termux to compose terminal`, follow-ups `4b356bea`,
`6f90b859`, `511ae1e2`, `b69fcf71`).

One file per issue so agents can work them independently. Ordered by severity.

| # | Severity | Title | Primary owner |
|---|----------|-------|---------------|
| [001](001-published-frame-transport-is-mutable.md) | P0 | Published frame transport is a mutable staging buffer → torn/stale frames | `terminal-emulator` |
| [002](002-viewport-state-owned-by-shared-session.md) | P0 | Viewport/scroll state is owned by the shared session, not the host | `terminal-compose-session` + `app` |
| [003](003-on-terminal-ready-orphaned-cursor-blink.md) | P1 | `onTerminalReady()` is orphaned → cursor blink never starts | `app` |
| [004](004-closed-session-left-in-service-list.md) | P1 | Closed `TerminalSession` left in the service list → permanently dead tab | `app` + `termux-shared` |
| [005](005-remove-finished-session-steals-focus.md) | P1 | Removing a background session steals focus | `app` |
| [006](006-terminal-activity-never-releases-backends.md) | P2 | `TermuxActivity` never releases session backends | `app` |
| [007](007-gles-surface-recreated-per-tab-switch.md) | P2 | GLES surface recreated on every tab switch; retention fix is dead | `terminal-compose-view` |
| [008](008-check-and-scroll-to-session-stale-index.md) | P2 | 1 s delayed drawer scroll captures a stale index | `app` |
| [009](009-tab-strip-selection-desync.md) | P2 | Tab strip selection desyncs from the displayed session | `app` |
| [010](010-dead-text-changed-and-duplicated-styling.md) | P3 | Dead `onTextChanged` path + duplicated background-color styling | `app` |

## Ground rules for every fix

1. Read `docs/fork/terminal-architecture.md` before touching terminal code.
2. Fix at the owning library layer. `app` and `compose-app` are hosts; do not
   patch session/render invariants in a host. See issue 001 and 002 — both are
   host-unfixable by construction.
3. No legacy shims, no dual paths, no preference-key fallbacks. Delete dead code
   (see 003, 010).
4. Add a regression test at the real seam before declaring the issue fixed.
5. Required gate before any commit:

   ```bash
   ./gradlew :terminal-emulator:testDebugUnitTest \
     :terminal-compose-view:testDebugUnitTest \
     :compose-app:testUniversalDebugUnitTest \
     :app:testDebugUnitTest
   ./gradlew ktlintFormat detekt
   ```

6. Do not install builds, launch emulators, or run device validation. Leave
   manual validation to the user.
7. Do not commit these issue files or their edits.

## Known context that explains several of these

- `app` intentionally keeps one `TerminalSessionBackend` per session
  (`TermuxTerminalSessionActivityClient.mSessionBackends`) so that switching back
  to a tab does not start from an empty frame store. That decision is what makes
  issues 001, 002 and 007 observable.
- A `TerminalSession` can be hosted by two `app` surfaces at once: the session tab
  strip and `BubbleSessionActivity`. `TermuxService.bubbleSession()` does not
  remove the session from `mShellManager.mTermuxSessions`.
