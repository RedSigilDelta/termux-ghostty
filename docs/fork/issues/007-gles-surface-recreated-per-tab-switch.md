# 007 — GLES surface is recreated on every tab switch; the retention fix is dead (P2)

Status: open
Owner module: `terminal-compose-view`
Affects: `TerminalCanvas`, `GlesTerminalCanvasContent`, `GlesTerminalSurface`

## Symptom

Every tab switch shows one or more blank frames and a visible flash while the
glyph atlas and textures are rebuilt. Tabs feel slow to open even though the
terminal itself is ready.

## Evidence

The surface is keyed on the controller, and the controller is keyed on the
backend:

```kotlin
// terminal-compose-view/.../GlesTerminalCanvasContent.kt:30
val surface = rememberGlesTerminalSurface(surfaceKey = controller)
```

```kotlin
// terminal-compose-view/.../TerminalCanvas.kt  (rememberTerminalController)
val controller = remember(backend) { TerminalController(backend) }
```

`GlesTerminalSurface()` then wraps it in a keyed `AndroidView`:

```kotlin
// terminal-compose-view/.../gpu/GlesTerminalSurface.kt:409-420
val surface = remember(surfaceKey) { GlesTerminalSurface() }
DisposableEffect(surface) { onDispose { surface.release() } }
...
key(surface) { AndroidView(factory = { GlesTerminalSurfaceView(context, surface) }, ...) }
```

`GlesTerminalSurfaceView` (`gpu/GlesTerminalSurfaceView.kt`) is a `GLSurfaceView`
with its own `GlesTerminalRenderer`, EGL context, glyph atlas and textures, and
`dispose()` tears all of it down.

The retention mechanism added by `4b356bea` ("retain GLES snapshots in
GlesTerminalSurface/Handoff for reattach") lives *inside* the surface object
(`gpu/TerminalSnapshotHandoff.kt` — `retainedSnapshot`, `acquireOrRetained()`,
`peekRetained()`), so it can never survive the switch it was written to fix.

## Mechanism

Changing `backendState` recreates the controller, which disposes the old
`GlesTerminalSurface` (releasing the GL resources and its retained snapshot) and
creates a new one whose atlas starts empty. The new surface only paints once the
`DisposableEffect` publisher runs and the GL thread has uploaded a full frame —
one or more blank frames per switch, plus a full atlas re-upload. The retained
snapshot is allocated, maintained, and then thrown away on every switch.

## Fix

Key the surface on the **host**, not on the per-session controller, and let the
outgoing controller hand its last snapshot to the incoming one:

- `rememberGlesTerminalSurface(surfaceKey = <stable host key>)` — the canvas
  instance, not the controller.
- On controller change, publish the new controller's current frame immediately
  (already happens via `DisposableEffect(surface, controller)`), and carry the
  previous snapshot through `TerminalSnapshotHandoff` so the GL thread has
  content to draw during the swap instead of nothing.
- Verify the `presentationRevision` / `contentRevision` monotonicity contract in
  `TerminalSnapshotHandoff.publish()` still holds when the *frame sequence* jumps
  backwards (new session restarts at a lower `frame.sequence`). `publish()`
  currently rejects when `current.contentRevision > snapshot.frame.sequence`,
  which is correct for one session and wrong across a session switch — this is
  probably why the surface was keyed per controller in the first place. Fix the
  revision domain (per-surface generation, not per-session sequence) rather than
  recreating the surface.

## Invariants to preserve

- A published `TerminalFrame`, its rows, palette, cell layout and link layout stay
  immutable for the lifetime of every consumer.
- Never render `FrameDelta.transportSnapshot`; the GL pass consumes complete
  `TerminalFrame`s only.
- Do not call session, JNI or backend mutation while drawing.

## Regression test

Seam: `terminal-compose-view` unit tests (existing:
`gpu/GlesTerminalSurfaceTest.kt`, `gpu/TerminalSnapshotHandoffTest.kt`).

- Publishing a snapshot with a lower `frame.sequence` but a higher
  `presentationRevision` must be accepted on the same surface.
- A surface re-keyed on a host (not a controller) must still present the retained
  snapshot after the controller changes.
- Add a test that a controller change does **not** release the surface.

## Verification

```bash
./gradlew :terminal-compose-view:testDebugUnitTest :app:testDebugUnitTest
./gradlew ktlintFormat detekt
```
