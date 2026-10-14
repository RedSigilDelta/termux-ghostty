# 010 — Dead `onTextChanged` path and duplicated background-color styling (P3)

Status: open
Owner module: `app` (with a `terminal-emulator` contract cleanup)
Affects: `TerminalSessionClient.onTextChanged`, `TermuxTerminalSessionActivityClient`, `BubbleTerminalSessionClient`

## Symptom

No user-visible breakage. This is migration debt that hides real regressions: a
callback that looks live but never fires, and two copies of the same styling
routine that have already drifted.

## Evidence

`onTextChanged` has no producer:

```
$ rg -n "onTextChanged" --glob '*.java' --glob '*.kt' | grep -v /build/
terminal-emulator/.../TerminalSessionClient.java:13:  void onTextChanged(@NonNull TerminalSession changedSession);
termux-shared/.../TermuxTerminalClientBase.java:16:      public void onTextChanged(@NonNull TerminalSession changedSession) {}
app/.../terminal/TermuxTerminalSessionActivityClient.java:126
app/.../terminal/TermuxTerminalSessionClientDispatcher.java:65,67
app/.../bubbles/BubbleTerminalSessionClient.java:49
compose-app/.../server/ServerFactory.kt:108-109
```

No `TerminalSession` code path calls it any more — the worker publishes frames
instead (compare `TerminalSession.notifyFrameAvailable()`,
`terminal-emulator/.../TerminalSession.java:726-731`).

Compensating code that exists only because of this:

```java
// app/.../terminal/TermuxTerminalSessionActivityClient.java:87  (onStart)
mActivity.getTerminalView().onScreenUpdated();

// same file, :126-130  (onTextChanged override — unreachable)
if (mActivity.getCurrentSession() == changedSession) mActivity.getTerminalView().onScreenUpdated();
```

Duplicated styling with drift:

- `TermuxTerminalSessionActivityClient.updateBackgroundColor()`
  (`app/.../terminal/TermuxTerminalSessionActivityClient.java:526-543`) sets the
  decor background, `ExtraKeysView` background, and the toolbar `ViewPager`
  background.
- `BubbleTerminalSessionClient.updateBackgroundColor()`
  (`app/.../bubbles/BubbleTerminalSessionClient.java:145-159`) sets the decor and
  `ExtraKeysView` backgrounds only.
- `SessionTabStripController.updateBackgroundColor()`
  (`app/.../terminal/SessionTabStripController.java:325-333`) sets a third
  surface and does **not** guard on `hasActiveTerminalBackend()`, unlike the other
  two, so it can read a background colour from a session whose backend is gone.

Also duplicated between the two hosts: `applyTerminalStyling()` /
`checkForFontAndColors()` (colour properties, `reloadColorScheme()`, typeface)
appear in `TermuxTerminalSessionActivityClient:508-525` and
`BubbleTerminalSessionClient:118-143`.

## Fix

- Remove `onTextChanged` from `TerminalSessionClient`,
  `TermuxTerminalSessionClientBase`, the dispatcher, both host clients, and
  `compose-app`'s `ServerFactory`. Keep one explicit refresh path: the frame
  callback. Per repo rules, delete the dead path rather than keeping it as a
  compatibility shim.
- Extract the background-colour application into one helper that takes the
  surfaces to paint (decor, extra keys, toolbar pager, tab strip) and applies the
  same `hasActiveTerminalBackend()` guard everywhere.
- Extract the colour-scheme/typeface application into one shared helper used by
  both hosts.

## Invariants to preserve

- Worker-published volatile caches are valid for display metadata attached to an
  already-applied frame, and for non-authoritative UI hints (they are used for
  background colour here, which is allowed).
- No legacy shims or migration code for the removed path.

## Regression test

Seam: `app` unit test.

- Shared background helper: assert every registered surface gets the same colour
  for (a) a session with an active backend, (b) a session without one, and
  (c) a null session.

## Verification

```bash
./gradlew :app:testDebugUnitTest :compose-app:testUniversalDebugUnitTest
./gradlew ktlintFormat detekt
```
