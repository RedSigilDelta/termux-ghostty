# 005 — Removing a background session steals focus (P1)

Status: open
Owner module: `app`
Affects: `TermuxTerminalSessionActivityClient`

## Symptom

A background tab exits quietly (for example a wrapper script that ends with exit
code 0 or 130) and the visible tab jumps to a different session mid-typing.

## Evidence

```java
// app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:483-497
int index = service.removeTermuxSession(finishedSession);   // index BEFORE removal
finishedSession.close();

int size = service.getTermuxSessionsSize();
if (size == 0) {
    mActivity.finishActivityIfNotFinishing();
} else {
    if (index >= size) index = size - 1;
    TermuxSession termuxSession = service.getTermuxSession(index);
    if (termuxSession != null) setCurrentSession(termuxSession.getTerminalSession());  // unconditional
}
```

The callers do not restrict removal to the current session:

```java
// same file, :176-194
if (mActivity.isVisible() && finishedSession != mActivity.getCurrentSession()) {
    if (index >= 0) mActivity.showToast(toastTitle + " - exited", true);   // toast only
}
// ...
if (finishedSession.getExitStatus() == 0 || finishedSession.getExitStatus() == 130 || isPlugin) {
    removeFinishedSession(finishedSession);        // removes background sessions too
}
```

## Mechanism

`onSessionFinished` auto-removes any session with exit status 0 or 130, not just
the displayed one. `removeFinishedSession` then unconditionally calls
`setCurrentSession(...)` with the removed session's pre-removal index, which after
removal resolves to whatever session now occupies that slot. The user's visible
tab is replaced even though nothing about it changed.

Two secondary problems in the same block:

- If the service did not actually remove the session (see issue 004), `index` is
  `-1`, `getTermuxSession(-1)` returns `null`
  (`TermuxService.java:946-952`), and **no switch happens at all** — the host
  keeps displaying a session that is no longer in the list.
- The replacement session is chosen by index, not by "the session that was
  displayed", so even the current-session case is off by one whenever the removed
  session sat before the displayed one.

## Fix

- Compute the switch target from host state, not from the removed session's old
  index: remember whether the removed session was
  `mActivity.getCurrentSession()`; only then pick a replacement, and pick the
  neighbour that is now at that position (`min(oldIndex, size - 1)`).
- If the removed session was not the displayed one, do not touch the current
  session at all.
- If no live replacement exists, clear the host's current session
  (`setCurrentSession(null)`) and let the view drop its backend, rather than
  leaving a removed session installed.
- Guard the whole tail on "the service confirmed the removal" (ties into issue
  004).

## Invariants to preserve

- Removing a non-displayed session must be invisible to the user apart from the
  existing "exited" toast.
- `TerminalComposeView` must never keep a backend installed for a session that is
  no longer in `TermuxService`.

## Regression test

Seam: `app` unit test with a fake `TermuxService` (three sessions, middle one
finishes).

- Assert the current session is unchanged when the finished session is not the
  current one.
- Assert the current session moves to `min(oldIndex, size - 1)` when the finished
  session *is* the current one.
- Assert `setCurrentSession(null)` when the last session is removed instead of a
  stale session staying installed.

## Verification

```bash
./gradlew :app:testDebugUnitTest
./gradlew ktlintFormat detekt
```
