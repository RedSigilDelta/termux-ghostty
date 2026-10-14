# 009 — Tab strip selection desyncs from the displayed session (P2)

Status: open
Owner module: `app`
Affects: `SessionTabStripController`

## Symptom

The highlighted tab disagrees with the terminal actually on screen:

- a tab stays highlighted after its session was removed from the service
- after a session disappears, tab 0 is highlighted while a different session is
  displayed
- the highlight jumps to the wrong tab after sessions are added or removed while
  the right-aligned layout is active

## Evidence

```java
// app/src/main/java/com/termux/app/terminal/SessionTabStripController.java:151
int newIndex = service.getIndexOfSession(currentSession);
if (newIndex < 0) return;                       // no rebuild, highlight left untouched
```

```java
// same file, :126-135
if (mSelectedIndex < 0 && !sessions.isEmpty()) {
    mSelectedIndex = 0;
    int childIndex = getChildIndexForSessionIndex(0, sessions.size());
    View firstTab = mTabStrip.getChildAt(childIndex);
    if (firstTab instanceof TextView) styleTabSelected((TextView) firstTab);
}
```

The fallback highlights tab 0 **without** changing the displayed session. It is
reached exactly when `mActivity.getCurrentSession()` is not in the service list —
the dead-session state from issue 004, and the window during
`removeFinishedSession()` between `service.removeTermuxSession()` (which removes
synchronously and triggers a rebuild through
`TermuxService.onTermuxSessionExited()`) and the trailing `setCurrentSession(...)`.

Related fragility in the same file:

- `getChildIndexForSessionIndex()` (`:315-323`) re-reads
  `mActivity.getProperties().getSessionTabBarAlign()` on every call, while
  `notifyDataSetChanged()` reads it once for the build loop (`:96`). If the
  property changes between build and selection (style reload calls
  `setSessionTabStripView()` and creates a new controller, but
  `reloadActivityStyling()` ordering is not guaranteed), the child index
  arithmetic inverts and the wrong tab is styled.
- `createTabView()` captures `index` in the long-press closure
  (`showSessionContextMenu(session, index)`, `:243-246`); after a rebuild the
  captured index is stale.
- Tab state (`isRunning()`, `getExitStatus()`) is baked in at build time; a tab
  that exits afterwards is neither re-styled nor re-strikethrough until the next
  rebuild.

## Fix

- Make the strip a pure function of `(service session list, current session)`. If
  the current session is not in the list, highlight **nothing** and let the host
  resolve the current session (issue 005), rather than defaulting to tab 0.
- Rebuild whenever the observed session list or the current session does not
  match the built state, instead of silently returning. Compare
  `service.getTermuxSessions()` identity/size, not just the child count
  (`onSessionChanged():157-161` compares `getChildCount() != size + 1`).
- Resolve the tab-bar alignment once per rebuild and pass it through
  `getChildIndexForSessionIndex()` instead of re-reading properties.
- Derive the long-press target from the session object, not a captured index.
- Refresh per-tab running/exit styling on session state change
  (`onSessionFinished`), not only on rebuild.

## Invariants to preserve

- UI hygiene: exactly one source of selection feedback. The tab strip must not
  invent a selection the host did not make.
- The strip is presentation only; it must not call `setCurrentSession()` other
  than from its own click handler.

## Regression test

Seam: `app` unit test.

- Current session not in the service list → assert no tab is highlighted.
- Rebuild with a right-aligned strip, then change the alignment property and call
  `onSessionChanged()` → assert the correct tab is highlighted.
- Remove the session at index 0, then `onSessionChanged()` → assert the highlight
  follows the new current session, not a stale child index.

## Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew ktlintFormat detekt
```
