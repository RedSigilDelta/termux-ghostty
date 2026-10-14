# 006 — `TermuxActivity` never releases session backends (P2)

Status: open
Owner module: `app`
Affects: `TermuxActivity`, `TermuxTerminalSessionActivityClient`

## Symptom

Rotation, `recreate()` from a style reload, or a configuration change can produce
a spurious `SIGWINCH` with the *previous* activity's geometry, showing up as a
reflow/garbled redraw in a session the new activity already sized correctly.
Long-lived sessions also pin their frame caches longer than necessary.

## Evidence

`app/src/main/java/com/termux/app/TermuxActivity.java:400-416` — `onDestroy()`
unbinds the service and nulls `mTermuxService`; it never releases anything.

`TermuxTerminalSessionActivityClient` has exactly one release path:

```
app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:475
    if (backend != null) backend.release();
```

Compare the bubble host, which does it correctly:

```java
// app/src/main/java/com/termux/app/BubbleSessionActivity.java:274-278
for (TerminalSessionBackend backend : mSessionBackends.values()) backend.release();
mSessionBackends.clear();
```

The pending debounced resize survives a plain detach:

```kotlin
// terminal-compose-session/.../TerminalSessionBackend.kt
override fun detach() {                      // :83-86
    session.removeFrameCallback(frameCallback)
    listener = null
}

override fun resize(size: TerminalSize) {    // :117-126
    ...
    resizeHandler.postDelayed(resizeRunnable, resizeCoalescer.debounceMillis)
}

override fun release() {                     // :158-166
    resizeHandler.removeCallbacks(resizeRunnable)
    pendingResize = null
    ...
}
```

`applyPendingResize()` then calls `session.updateSize(...)` and resets `topRow`
on a session that a newer activity already owns.

## Mechanism

1. Activity A owns a backend for session S and posts a debounced resize
   (`DefaultResizeDebounceMillis = 50L`).
2. Activity A is destroyed. The Compose composition is disposed, so the
   controller calls `backend.detach()` — which does **not** cancel the pending
   resize.
3. Activity B is created, installs a *new* backend for S, and resizes it.
4. A's `resizeRunnable` fires and calls `session.updateSize(...)` with A's
   geometry, plus `topRow = 0`, racing B's size.

Additionally the `HashMap<String, TerminalSessionBackend>` pins, per session, a
`TerminalSessionFrameStore` → `RenderFrameCache` → `ScreenSnapshot` plus
`TerminalFrameContentCache` rows and styles until GC collects the destroyed
activity.

## Fix

- Give `TermuxTerminalSessionActivityClient` an explicit teardown that releases
  every backend and clears the map, and call it from `TermuxActivity.onDestroy()`.
  Mirror `BubbleSessionActivity.onDestroy()`.
- Also drop the `TerminalComposeView` backend (`setBackend(null)`) so the view
  stops referencing a session it no longer owns.
- Independently, consider cancelling a pending debounced resize in
  `TerminalSessionBackend.detach()` — a detached host has no business resizing.
  Keep `release()` idempotent.

## Invariants to preserve

- Backend ownership stays with the host that supplied it; the canvas must not
  release a shared backend on dispose (that regression is what issue 007's history
  already fixed once — check the commit message of `4b356bea` before changing it).
- Resize coalescing stays in the backend; do not re-add per-view resize state.

## Regression test

Seam: `app` unit test for the teardown contract, plus
`terminal-compose-session` for the resize cancellation.

- `TerminalSessionBackend`: `detach()` then advance the looper by > debounce →
  assert `session.updateSize` was not called.
- `app`: assert `onDestroy()` releases every backend in the map (a released
  backend is observable through `release()` being idempotent / frame callbacks
  removed).

## Verification

```bash
./gradlew :app:testDebugUnitTest :terminal-compose-view:testDebugUnitTest
./gradlew ktlintFormat detekt
```
