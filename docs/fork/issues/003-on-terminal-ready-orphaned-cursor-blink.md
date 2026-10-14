# 003 — `onTerminalReady()` is orphaned; cursor blink never starts (P1)

Status: open
Owner module: `app` (`compose-app` has the same contract — check it)
Affects: `TermuxTerminalViewClient`, `BubbleTerminalViewClient`

## Symptom

The cursor does not blink for the whole time an activity stays in the
foreground. Users report this as "the session looks frozen" / "the session died".
Most reproducible after rotation, after returning from the background, and on a
freshly created session.

## Evidence

The removed legacy view notified readiness after the size pass that initialises
the backend:

```java
// terminal-view/src/main/java/com/termux/view/TerminalView.java (deleted in a560b1a8)
mTermSession.updateSize(newColumns, newRows, cellWidthPixels, cellHeightPixels);
mClient.onTerminalReady();
```

The Compose path has **no caller**. `rg -n onTerminalReady` returns only:

- `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalClientBase.java:112` — empty default
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java:159` — override
- `app/src/main/java/com/termux/app/bubbles/BubbleTerminalViewClient.java:172` — override

And the only remaining starter is conditional on the backend already being up:

```java
// app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java:117-126
mTerminalCursorBlinkerStateAlreadySet = false;
TerminalSession currentSession = mActivity.getCurrentSession();
if (currentSession != null && currentSession.hasActiveTerminalBackend()) {
    setTerminalCursorBlinkerState(true);
    mTerminalCursorBlinkerStateAlreadySet = true;
}
```

`mTerminalCursorBlinkerStateAlreadySet` is only cleared in `onResume()`, so a
single `onResume()` that runs before backend initialisation disables blink
startup for the entire foreground lifetime.

## Mechanism

The backend is initialised lazily from the first canvas size pass
(`TerminalSession.updateSize()` → `initializeTerminalBackend()`), which happens
after `onResume()`. The old view signalled that moment; the new path has no
equivalent, so the "wait for ready" branch in `onResume()` waits forever.

Related: `TerminalCursorBlinker.setState()`
(`app/src/main/java/com/termux/app/TerminalCursorBlinker.java:46-72`) silently
returns when `!session.hasActiveTerminalBackend()`, so callers cannot tell the
difference between "not ready yet" and "blinking disabled".

## Fix

Emit the readiness signal from the layer that actually knows, and delete the
orphan indirection:

- `TerminalSession` should notify the client when its backend becomes active
  (same place that transitions to an active backend after
  `initializeTerminalBackend()` succeeds). Thread it through
  `TerminalSessionClient` so the existing `onTerminalReady()` contract is
  honoured rather than re-invented per host.
- Or, if a callback is judged not worth keeping, delete `onTerminalReady()` from
  all three files and let the blinker start on the first frame that carries
  cursor metadata. Do not leave both paths.

Do not fix this by removing the `hasActiveTerminalBackend()` guard or by starting
the blinker unconditionally from `onResume()` — the guard exists so the blinker
does not run against a session that cannot be blinked.

## Invariants to preserve

- Cursor blink state is worker-owned native state; the blinker only flips a flag.
- The blinker must not run while the activity is not visible.

## Regression test

Seam: `app` unit test (`app/src/test/java/com/termux/app/terminal/TermuxTerminalViewClientTest.java`
already exists and uses a recording client).

- Given a session whose backend is not active at `onResume()`, assert that the
  cursor blinker is started once readiness is signalled afterwards.
- If the callback is removed instead, assert the first published frame starts the
  blinker.

## Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew ktlintFormat detekt
```
