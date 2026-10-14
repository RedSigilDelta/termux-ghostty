# 004 — A closed `TerminalSession` is left in the service list → permanently dead tab (P1)

Status: open
Owner modules: `app` (`TermuxService`, `TermuxTerminalSessionActivityClient`), `termux-shared` (`TermuxSession`)
Affects: session tab strip, drawer session list

## Symptom

A tab appears in the session list but is completely dead: selecting it shows a
blank or frozen terminal, nothing can be typed, and it cannot be closed from the
tab strip. Recovery requires killing the app.

## Evidence

`TermuxService.removeTermuxSession()` delegates to `TermuxSession.finish()`:

```java
// app/src/main/java/com/termux/app/TermuxService.java:676-683
public synchronized int removeTermuxSession(TerminalSession sessionToRemove) {
    int index = getIndexOfSession(sessionToRemove);
    if (index >= 0) mShellManager.mTermuxSessions.get(index).finish();
    return index;                       // index reported even if nothing was removed
}
```

`TermuxSession.finish()` returns **without removing the session from the list** in
three cases (`termux-shared/src/main/java/com/termux/shared/termux/shell/command/runner/terminal/TermuxSession.java:187-209`):

- `if (mTerminalSession.isRunning()) return;`
- `if (mExecutionCommand.isStateFailed()) return;`
- `if (!mExecutionCommand.setState(ExecutionState.EXECUTED)) return;`

`TermuxService.killAllTermuxExecutionCommands()` reaches exactly this
(`app/src/main/java/com/termux/app/TermuxService.java:294-297`):

```java
termuxSessions.get(i).killIfExecuting(this, processResult);
if (!processResult) mShellManager.mTermuxSessions.remove(termuxSessions.get(i));
```

`killIfExecuting()` returns immediately for an already-executed session
(`termux-shared/.../TermuxSession.java:223-225`), and with `processResult == true`
that session is also not removed from the list — yet its `TerminalSession` has
already been closed by `onTermuxSessionExited()`
(`app/.../TermuxService.java:687-706`, which calls `close()`).

Meanwhile the host unconditionally disposes the session:

```java
// app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:473-497
TerminalSessionBackend backend = mSessionBackends.remove(finishedSession.mHandle);
if (backend != null) backend.release();
int index = service.removeTermuxSession(finishedSession);   // may remove nothing
finishedSession.close();                                   // always closes
```

## Mechanism

Select the dead tab afterwards:

1. `mSessionBackends` entry is gone (line 474), so `setCurrentSession()` builds a
   **new** `TerminalSessionBackend` over an already-closed session
   (`TermuxTerminalSessionActivityClient.java:322-327`).
2. `TerminalSessionBackend.refresh()` no-ops — `hasActiveTerminalBackend()` is
   false.
3. `TerminalSessionBackend.resize()` → `TerminalSession.updateSize()` no-ops —
   `if (mClosed) return` (`terminal-emulator/.../TerminalSession.java:262-266`).
4. `submit()` returns `TerminalCommandResult.Failure` for every input.

The tab is unrecoverable: nothing in the tab strip can close it, and the backend
can never be re-initialised because `mClosed` is latched in `close()`
(`TerminalSession.java:806-849`).

`SessionTabStripController.onSessionChanged():151` also returns early on
`newIndex < 0`, so the strip keeps highlighting a session the service does not
have (see issue 009).

## Fix

Make session removal and session disposal agree, at the service layer:

- `TermuxService.removeTermuxSession()` must report whether removal actually
  happened, and `onTermuxSessionExited()` must be the single place that removes
  from `mShellManager.mTermuxSessions` and closes the `TerminalSession`.
- `killAllTermuxExecutionCommands()` must remove every session it decided not to
  process, including already-executed ones, instead of leaving closed sessions in
  the list.
- `TermuxTerminalSessionActivityClient.removeFinishedSession()` must only
  `close()` what the service confirmed it removed, and must clear the host's
  current session when the removed session was the displayed one.

Optionally add a consistency sweep in `TermuxService.onCreate()`: drop any
`mShellManager.mTermuxSessions` entry whose `TerminalSession` is not running and
whose backend is closed.

## Invariants to preserve

- Process exit and session disposal stay separate (`6f90b859`): a finished
  process keeps its backend until its owner closes the session. This issue is
  about *removal bookkeeping*, not about re-merging those two steps.
- A session that leaves the list must never remain the host's current session.

## Regression test

Seam: `app` unit test with a fake `TermuxService`/`TermuxSession` pair.

- Session whose `ExecutionCommand` is already in a failed state: assert
  `removeTermuxSession()` both removes it from the list and closes it.
- `killAllTermuxExecutionCommands()` with one already-executed session: assert
  the list is empty afterwards.
- Selecting a session removed from the list: assert the host switches to a live
  session instead of installing a backend over a closed one.

## Verification

```bash
./gradlew :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest
./gradlew ktlintFormat detekt
```
