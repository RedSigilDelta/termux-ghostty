# 002 — Viewport/scroll state is owned by the shared session, not the host (P0)

Status: open
Owner modules: `terminal-compose-session` (primary), `terminal-emulator`, `app`
Affects: bubbled session shown as a tab and as a bubble simultaneously; tab switching

## Symptom

Scroll position jumps to the wrong place. Scrolling in the bubble window moves
the tab's viewport and vice versa. A tab that was scrolled up in the background
comes back at the wrong offset, or snapped to the top of the scrollback.

## Evidence

`terminal-compose-session/src/main/java/com/termux/terminal/compose/session/TerminalSessionBackend.kt:177-196`

```kotlin
private fun synchronizeViewport(frameDelta: FrameDelta) {
    topRow = resolveTopRow(
        previousTopRow = topRow,                       // host-local
        ...
        autoScrollDisabled = session.isAutoScrollDisabled,  // session-global
        rowShift = session.scrollCounter                   // session-global, destructive read
    )
    session.clearScrollCounter()                        // session-global, destructive write
    session.setGhosttyTopRow(topRow)                    // session-global write
}
```

- `topRow` is per **backend**; `isAutoScrollDisabled`, `mScrollCounter` and the
  worker-side `mCurrentTopRow` are per **session**:
  - `terminal-emulator/.../TerminalSession.java:123` `mScrollCounter`
  - `terminal-emulator/.../TerminalSession.java:662` `setGhosttyTopRow`
  - `terminal-emulator/.../TerminalSession.java:973` `isAutoScrollDisabled`
  - `terminal-emulator/.../GhosttySessionWorker.java:352-359` `appendToNative`
    already shifts `mCurrentTopRow` as output streams
- Two hosts each build their own backend for the same session:
  - `app/.../terminal/TermuxTerminalSessionActivityClient.java:59`
  - `app/.../BubbleSessionActivity.java:75`
- `TermuxService.bubbleSession()` (`app/.../TermuxService.java:1038-1048`) does not
  remove the session from `mShellManager.mTermuxSessions`, so the same
  `TerminalSession` is legitimately attached by both hosts.
- `mAutoScrollDisabled` is toggled per host but stored per session:
  `app/.../terminal/io/TermuxTerminalExtraKeys.java` (`"SCROLL"` key).

## Mechanism

Every applied frame from either host:

1. The first host to refresh consumes `session.scrollCounter` and clears it.
2. The second host reads `rowShift == 0` and therefore computes a different
   `topRow` from the same frame.
3. Both push their result into the single worker-owned `mCurrentTopRow`.

The worker viewport is last-writer-wins per frame, so the visible viewport
oscillates with callback ordering. `resolveTopRow`'s "keep the same content in
view" shift is also applied twice over: once in `appendToNative` on the worker and
once on the main thread from a `rowShift` that a competing host may already have
consumed.

## Blast radius

- Scroll position corruption whenever a session has more than one attached host.
- `SCROLL` (auto-scroll disable) toggled in one host silently changes the other
  host's behaviour.
- Any future second host (shortcut, picture-in-picture, multi-window) inherits
  the bug for free.

## Fix

Make the viewport **host-owned**; the session must not hold per-host scroll state.

- Keep the authoritative viewport mutation in `GhosttySessionWorker`, but scope
  the owner identity: the scroll/viewport command must name the requesting host,
  and the worker must only apply a viewport mutation for the host that owns the
  current publication consumer. Do not mirror Morphe's API here — use whatever is
  native to Reseam/Termux's worker model.
- Replace the destructive `scrollCounter` read with a per-host monotonic marker
  (e.g. the host tracks the last applied `frameSequence` and derives the shift
  from the delta itself), so a second host cannot steal the shift.
- `mAutoScrollDisabled` becomes host state in the backend, not session state.
- `TerminalSessionBackend` is the natural home for host-owned `topRow`; the worker
  command should carry `(hostId, topRow)`.

Deliberately do **not** add a host-side "last writer wins" guard in `app` — that
hides the race and leaves the second host permanently unable to scroll.

## Invariants to preserve

- `GhosttySessionWorker` serialises all native terminal mutation, including
  viewport mutation.
- Mode-dependent scroll routing stays on the worker after parsing queued PTY
  bytes. Do not route scroll from a rendered frame or a session cache.
- Viewport commands retain Handler queue order; do not coalesce them through a
  shared mutable "pending top row".
- Reason flags are a bitset — test with `flags and REASON != 0`.

## Regression test

Seam: `terminal-emulator` + `terminal-compose-session`.

1. Create one `TerminalSession` and two `TerminalSessionBackend`s over it.
2. Attach both, drive output, and alternate `refresh()` calls.
3. Assert each backend's `topRow` evolves independently and that the worker
   viewport only follows the host that submitted a viewport command.
4. Assert a host that never scrolls does not have its shift stolen by the other.

Existing unit seam for the pure function:
`resolveTopRow(...)` in `TerminalSessionBackend.kt` — extend its tests to cover
two interleaved hosts.

## Verification

```bash
./gradlew :terminal-emulator:testDebugUnitTest \
  :terminal-compose-view:testDebugUnitTest \
  :compose-app:testUniversalDebugUnitTest :app:testDebugUnitTest
./gradlew ktlintFormat detekt
```
