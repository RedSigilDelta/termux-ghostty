# 001 — Published frame transport is a mutable staging buffer (P0)

Status: open
Owner module: `terminal-emulator`
Affects: every host (`app`, `compose-app`)

## Symptom

Occasionally garbled or stale terminal rows — wrong text, mixed styling, rows
from a different moment — most visibly after a tab switch, on return from the
background, and during cursor blink. Because the corrupt delta is fed into
`RenderFrameCache`, the damage persists until the next full rebuild.

## Evidence

- `terminal-emulator/src/main/java/com/termux/terminal/FrameDelta.java:26-45`
  `mTransportSnapshot` / `mViewportLinkSnapshot` are raw references. No copy is
  made at construction.
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java:723-785`
  `buildAndPublishSnapshot()`:
  - `:742` `mContent.fillSnapshot(stagingSnapshot)` mutates the staging buffer
  - `:762-768` publishes `mPublishedFrameDelta` pointing at that same buffer
  - `:771-773` swaps buffers (two `ScreenSnapshot` objects only)
  - `:775-783` the gate is released in the `finally` of the posted
    `notifyFrameAvailable()` task
- `terminal-emulator/src/main/java/com/termux/terminal/FramePublicationGate.java`
  one-shot `mUIUpdatePending` flag: it only serialises the notify window.
- `terminal-emulator/src/main/java/com/termux/terminal/GhosttySessionWorker.java:315-317`
  `getPublishedFrameDelta()` — plain `AtomicReference.get()`, no gate check.

Two publications after a delta is published, that `ScreenSnapshot` instance
becomes `mCurrentStaging` again and the worker overwrites it with the gate open.

## Mechanism

Every main-thread read of the published delta **outside** the
`notifyFrameAvailable()` callback is a data race on a mutable `ScreenSnapshot`
(rows, styles, palette, dirty-row list, dimensions):

- `terminal-compose-view/.../TerminalComposeView.kt` — `setBackend()`,
  `onAttachedToWindow()`
- `terminal-compose-session/.../TerminalSessionBackend.kt:88` `refresh()` when
  called from `attach()` (`:75`) or a host hook
- `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java:87`
  → `onScreenUpdated()`
- `app/src/main/java/com/termux/app/TerminalCursorBlinker.java:60-70` — the blink
  runnable calls `mTerminalView.onFrameAvailable()` on a 100–2000 ms timer. This
  one fires **permanently off-window** and is the most frequent trigger.

`RenderFrameCache.apply()` then consumes a delta whose dirty-row indices and row
payloads were produced by two different terminal states.

## Blast radius

- Visual corruption of the visible frame.
- Breaks the documented invariant "partial deltas are only valid when the UI
  cache has contiguous sequence history" — the cache cannot detect this class of
  corruption.
- Any host that reads the published delta on a timer is affected, so this is not
  fixable by a host-side change.

## Fix

In `terminal-emulator`. Pick one:

- **A (preferred):** publish an immutable transport snapshot. Either deep-copy
  the staging `ScreenSnapshot` into a fresh object per publication, or move to a
  triple-buffer / generation scheme where a published buffer is never reused
  until all readers have left the window. The cheapest correct variant is to
  extend `FramePublicationGate` into a reader count (`acquireReader` /
  `releaseReader`) checked by `tryStartSnapshotBuild()`, so a buffer cannot be
  recycled while any main-thread reader holds it.
- **B:** make out-of-window reads impossible. `getPublishedFrameDelta()` returns
  `null` unless called inside the notify window, and hosts that need a frame
  outside it go through a coalesced main-thread "request refresh" that the worker
  answers with a fresh publication. This removes the race but costs a round trip
  for host-driven refreshes, so re-check callers such as
  `TermuxTerminalSessionActivityClient.onStart()` and the cursor blinker.

Do **not** fix this by adding locking in `app` or `compose-app`.

## Invariants to preserve

- `GhosttySessionWorker` remains the sole serialised owner of mutable native state.
- Frame sequences stay monotonic; a missing partial still triggers a full refresh.
- Terminal bytes are never dropped — snapshot builds may be coalesced, reads may
  not be reordered around the gate.

## Regression test

Seam: `terminal-emulator` unit test.

1. Build a session, apply deltas, then read `getPublishedFrameDelta()` from the
   "main thread" *outside* the notify window.
2. Drive the worker to publish two more frames.
3. Assert the previously obtained `ScreenSnapshot`'s contents are unchanged
   (option A) or that the out-of-window read returned `null` (option B).

Also add a `RenderFrameCache` test asserting it rejects a delta whose
`dirtyRowCount`/row hashes are inconsistent with a re-read snapshot — defence in
depth, not the fix.

## Verification

```bash
./gradlew :terminal-emulator:testDebugUnitTest :app:testDebugUnitTest
./gradlew ktlintFormat detekt
```
