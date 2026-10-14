# 008 — Delayed drawer scroll captures a stale session index (P2)

Status: open
Owner module: `app`
Affects: `TermuxTerminalSessionActivityClient.checkAndScrollToSession`

## Symptom

Opening the session drawer shortly after adding or closing a tab scrolls the
drawer list to the wrong row. Rapid session churn makes it land on an unrelated
session.

## Evidence

```java
// app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:504-517
final int indexOfSession = service.getIndexOfSession(session);
if (indexOfSession < 0) return;
final ListView termuxSessionsListView = mActivity.findViewById(R.id.terminal_sessions_list);
if (termuxSessionsListView == null) return;

termuxSessionsListView.setItemChecked(indexOfSession, true);
// Delay is necessary otherwise sometimes scroll to newly added session does not happen
termuxSessionsListView.postDelayed(() -> termuxSessionsListView.smoothScrollToPosition(indexOfSession), 1000);
```

`indexOfSession` is resolved at call time but used one second later. In between,
`TermuxService.onTermuxSessionExited()` (`app/.../TermuxService.java:687-706`) can
remove entries from `mShellManager.mTermuxSessions`, and the adapter is
constructed over that **live** list
(`app/.../TermuxActivity.java:setTermuxSessionsListView()` →
`new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions())`).

Secondary: the pending `postDelayed` runnable also keeps a strong reference to
the list view (and through the activity context) alive for a full second after
the activity may have moved on.

## Fix

- Resolve the index inside the delayed runnable, from the session identity, and
  bail out if the session is no longer in the list:

  ```java
  termuxSessionsListView.postDelayed(() -> {
      TermuxService service = mActivity.getTermuxService();
      if (service == null) return;
      int index = service.getIndexOfSession(session);
      if (index < 0) return;
      termuxSessionsListView.smoothScrollToPosition(index);
  }, SESSION_LIST_SCROLL_DELAY_MILLIS);
  ```
- Keep `setItemChecked(indexOfSession, true)` immediate, or move it into the
  runnable too so the checked state and the scroll position cannot disagree.
- Cancel the pending runnable when the list is detached or the activity stops.

## Invariants to preserve

- `checkAndScrollToSession` is presentation only; it must never mutate session or
  backend state.
- Index-based lookups into `TermuxService` must be re-validated at the point of
  use, because the list is live and mutated from session callbacks.

## Regression test

Seam: `app` unit test.

- `checkAndScrollToSession(session)` then remove a session before advancing the
  looper: assert the list is not scrolled to a stale position and no exception is
  thrown for an out-of-range index.

## Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew ktlintFormat detekt
```
