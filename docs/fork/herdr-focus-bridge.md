# Herdr focus bridge

A small Go program, `tools/herdr-focus-bridge`, that runs on the SSH host and forwards selected pane, tab, and workspace
actions to Herdr's API socket over a private Unix socket. It removes per-action SSH channel setup; Herdr still handles
each request on its own local one-shot API connection. The helper is **optional** and does not change Herdr or install
agent hooks.

## Ownership and data flow

```text
Agents sheet -> AppViewModel -> HerdrActions (serial action intents, terminal ID -> pane ID)
                                   |-- FocusBridgeClient -> one retained SSH streamlocal channel
                                   |                         -> Go helper -> one-shot local Herdr API request
                                   `-- HerdrControlClient.request() (direct fallback before a bridge write)

HerdrControlClient -> events.subscribe + JSON session.snapshot -> Agents sheet topology/terminal IDs
                   -> binary endpoint shell.snapshot.v1       -> notification/status invalidation
```

The helper is **only** a command transport for `pane.focus`, `pane.close`, `pane.split`, `tab.create`, and
`workspace.create`. It does not provide pane lists, snapshots, status, event subscriptions, or client-specific focus.
The client only sends methods advertised by the bridge handshake; an older helper falls back to the direct socket for
methods it does not support.
`HerdrAgentsSheet` reads `HerdrControlClient.state.snapshot`, populated by
`session.snapshot`: topology events trigger coalesced refetches (at least 250 ms between fetch starts), and idle status
sampling runs about every five seconds. The existing binary endpoint currently invalidates the JSON cache on changed
agent statuses rather than directly replacing the sheet's snapshot. Do not remove JSON snapshots without supplying the
stable `terminal_id`, layout, and recovery semantics they currently provide. Herdr's focus snapshot is server-global,
not proof of which pane this app's attached client displays.

Key files: `FocusBridgeClient.kt` owns the retained channel and fallback, `FocusBridgeInstaller.kt` handles explicit
provisioning, `FocusBridgeMaintenance.kt` handles explicit removal, `HerdrActions.kt` preserves action ordering and reply
identity checks, `ServerFactory.kt` constructs/warm-ups the connection-scoped client, and `AppViewModel.kt` owns install
progress and the saved per-server opt-in. `Server.disconnect()` closes the app's channel, **not** the host helper.

## Build

- Requires `go` on `PATH`. The tested toolchain is **Go 1.26.8** (`go version go1.26.8 darwin/arm64`). `go.mod` declares
  `go 1.22`, which is the minimum the source needs.
- Every Gradle build of `compose-app` compiles the bridge for `darwin/arm64` (`CGO_ENABLED=0`, `-trimpath`,
  `-buildvcs=false`, `-ldflags=-s -w`). It then checks the Mach-O header and `go version -m`, embeds the binary as an asset,
  and generates `FocusBridgeArtifact.kt` with the binary's SHA-256 and size. Gradle tracks Go source, `go.mod`, and the
  Go toolchain version as task inputs; changing any of these rebuilds the binary and its asset. Do not copy a previously
  built helper into assets or use an APK built with a different source/toolchain.
- A missing `go` fails the build with a message that names this document.
- Built assets live under `compose-app/build/generated/herdrFocusBridge/`; the APK carries
  `assets/herdr-focus-bridge/darwin-arm64/herdr-focus-bridge`. The host binary is **macOS arm64**, independent of the
  Android APK ABI. Validate the embedded asset hash, Mach-O architecture, and linked libraries before shipping.

## Wire protocol (version 1)

Newline-delimited JSON on a persistent Unix socket. The full reference is the package comment in `main.go`.

- `bridge.hello` `{"protocol":1}` is the first request on every connection. The reply lists the supported methods.
- `pane.focus` `{"pane_id"}` returns Herdr's reply line unchanged.
- `pane.close` `{"pane_id"}` returns Herdr's reply line unchanged.
- `pane.split` `{"target_pane_id","direction","focus"}` accepts `right` or `down` for direction.
- `tab.create` `{"workspace_id","focus"}`.
- `workspace.create` `{"source_workspace_id","focus"}`.
- `bridge.stop` `{}` acknowledges with `bridge_stopping`, then drains and exits. The bridge removes its own socket. It is
  sent only by an explicit Remove.
- Bridge errors use `{"id","error":{"code","message"}}`. Codes: `bad_request`, `line_too_long`, `handshake_required`,
  `unsupported_protocol`, `unknown_method`, `too_many_connections`, `herdr_unavailable`, `herdr_timeout`, `herdr_bad_reply`.

## Opt-in, install, and remove

- The focus helper is off by default. For a connected Herdr-enabled server, open **Servers → Edit → Fast focus helper**;
  Edit remains available while connected. The **Install** button is the only way to turn it on. It
  checks that the host is macOS arm64, uploads the embedded binary only when the remote hash differs, starts it, and
  verifies the handshake. The saved `ServerConfig.herdrFocusBridgeEnabled` flag changes only after the helper answers
  and the save succeeds. No install or update happens on SSH connection or during an action. Installation reuses the server's
  existing SFTP client; opening a second SFTP client would close the one used by file tabs.
- The helper binary is versioned by its build hash. After an app update that changes the helper, use **Install** again
  to deploy the new version; until then, actions unsupported by the old helper use the direct Herdr socket.
- **Remove** closes the app's channel, sends `bridge.stop` to each helper socket in the helper directory, waits for each
  socket to disappear, and deletes the helper's own files. The saved setting is cleared only after all of that succeeds.
  Herdr and any other process are never touched.
- Helper files live in `~/.config/herdr-focus-bridge` on the host (mode 0700). Socket and binary names carry the first 12
  hex digits of the build hash. Removal uses the bridge's private-socket `bridge.stop`, never a PID file or `pkill`.
  A new app build may leave an older version running until explicit Remove; the current client addresses only its
  versioned socket.

## Focus path and fallback

- Focus, close pane, split pane, create tab, and create workspace use the bridge only when the helper is enabled and
  advertises that method. It makes one short socket open (1 second). If that fails, the bridge is held off for 30 seconds,
  and the action goes straight to the direct Herdr socket.
- Fallback happens only before the request is written. Once written, the outcome is final: Herdr's result or an error.
  An action is never sent twice.
- Once enabled, the app opens the persistent channel in the background after installation and on later SSH connections,
  so the first action need not pay for channel setup when that open succeeds. It never installs during an action.
- `HerdrActions` still resolves the tapped terminal against its last-known JSON snapshot and verifies that the returned
  terminal ID matches. A stale positional pane ID can briefly focus another pane before the mismatch is discovered;
  the existing one-time fresh-snapshot retry then applies. Do not retry an uncertain, already-written bridge request
  through the direct socket: doing so could reorder or duplicate focus intents.
- Timing marks `bridge_open_started`, `bridge_open_finished` or `bridge_open_failed`, `bridge_request_written`, and
  `bridge_reply_received` go to the existing `HerdrFocusTiming` debug trace next to the direct path's stages. They carry no
  pane IDs or paths. Enable with `adb shell setprop log.tag.HerdrFocusTiming DEBUG` and compare the trace stages in
  logcat. `observation_finished` includes the *subsequent JSON snapshot*, not the time the Herdr TUI finished drawing.

## Known limits

- A bridge process that dies while the SSH connection stays up fails the next bridged action once, after the request is
  written. The channel is then dropped, and the following action reconnects or falls back.
- The first Install on a slow link is bounded by the SFTP upload. Its progress shows as a percentage.
- A post-write hang is bounded by the same 10-second request timeout as the direct path.
- A bridge that is absent on reconnect falls back to direct Herdr requests (with a 30-second connection-attempt
  hold-off); it is never silently reinstalled. Use Install again if the host rebooted and the helper is no longer running.
- Go vet/build and the `compose-app` unit suite, detekt, and debug/release APK packaging were validated on macOS arm64.
  The helper's live install/focus/remove path over SSH and clean-install behavior were **not** exercised by the agent;
  do not claim production latency or remote lifecycle correctness from compilation alone. `ktlintFormat` currently has
  no `compose-app` Kotlin source coverage.
