# Repository Instructions

## Commit format
- **Never commit unless the user explicitly asks.** Do not stage or commit changes on your own initiative.
- Use Conventional Commits so the release changelog tooling can parse them.
- Required format: `type(scope): description`
- Allowed types: `feat`, `fix`, `update`, `ui`, `refactor`, `perf`
- Scope is optional, e.g. `feat(bubble): add unread tracking`
- Use sentence case for the description. Keep it to one line.
- Breaking changes: append `!` before `:`, e.g. `feat!: remove legacy backend`, or include `BREAKING CHANGE:` in the commit body.
- Skipped (not in changelog): `ci`, `chore`, `doc`, `Merge`, `Revert`

### Version bump rules
- `feat` → minor bump (0.X.0)
- `fix`, `update`, `ui`, `refactor`, `perf` → patch bump (0.0.X)
- Breaking change (`!`) → major bump (X.0.0)

## Examples
- `feat(bubble): add unread session tracking`
- `fix(terminal): crash on empty input`
- `ui(settings): update theme picker layout`
- `perf(emulator): reduce frame allocation overhead`
- `feat!: drop Android 5 support`

## Documentation

All fork-specific docs go under `docs/fork/`. Do not add `.md` files to the repo root.

- `docs/fork/` — research, comparisons, guides, architecture docs
- `docs/fork/plans/` — implementation plans, checklists, stabilization docs

**Note on plans:** Do NOT commit plan files (`.md` files outlining implementation steps/checklists) or updates to plans to the Git repository. Keep plan documents untracked or locally edited only. Do not stage them for commits.

## Testing workflow

- **Do not write new tests.** Use the existing test suites for validation; do not add or modify test files unless the user explicitly asks.
- **Unit tests: run them.** Fast, local, zero device time — run proactively after code changes (e.g. `./gradlew :compose-app:testUniversalDebugUnitTest`) if a test suite covers the change.
- **Live/device testing: never.** Do not install builds, launch emulators, connect devices, or run manual validation — that burns tokens and time. Leave manual validation to the user unless they explicitly ask otherwise.

## Linting & Static Analysis

Kotlin formatting, static analysis, and Android Lint are available through Gradle:

| Tool | Purpose | Command |
|---|---|---|
| **ktlint** | Formatting & style (indentation, spacing, naming) | `./gradlew ktlintCheck` (check), `./gradlew ktlintFormat` (auto-fix) |
| **detekt** | Static analysis (complexity, bugs, smells) | `./gradlew detekt` |
| **Android Lint** | XML, manifest, perf, accessibility | `./gradlew lint` |

**Quick combo:** `./gradlew ktlintFormat detekt` — fix style then run analysis.

**Required after any code changes:** Run `./gradlew ktlintFormat detekt` before committing. ktlint auto-fixes formatting; detekt catches code smells. Both must pass.

**Known ktlint coverage gap:** Current tasks do not cover `compose-app` Kotlin sources and may report only Kotlin-script tasks with `NO-SOURCE`. A successful `ktlintFormat`/`ktlintCheck` is not proof those sources were formatted or checked. Verify source-task coverage before claiming it.

Config files:
- `.editorconfig` — ktlint rules (shared with EditorConfig)
- `config/detekt/detekt.yml` — detekt rules

## Architectural Integrity & Code Hygiene

- **Solve problems at the architecture/library level, never with ad-hoc consumer hacks.** When fixing bugs or adding capabilities across the terminal stack (`terminal-emulator`, `terminal-compose-session`, `terminal-compose-view`), address the root cause at the owning library layer instead of applying defensive workarounds, manual event-forwarding hacks, or duct tape in consumers (`compose-app`, `app`). Both hosts must benefit from clean, coherent library contracts (e.g., separating process exit from session disposal, authoritative frame publication, input translation) rather than compensating for engine-level omissions.
- **No legacy shims or migration code unless explicitly requested.** Do not add backward-compat fallbacks, legacy preference keys, deprecated-API wrappers, or one-off data migrations to support old app versions.
- If existing legacy code is found (e.g. dual-writes to old preference keys, migration fallbacks), prefer removing it outright. If user data is at stake, do a one-time conversion that deletes the legacy key afterwards — never keep it alive.

## UI Hygiene

- Before adding a UI element, indicator, icon, label, or status affordance, check the surrounding UI for an existing equivalent. Avoid redundant elements and duplicate labels or indicators; prefer one clear source of feedback.

## Core Behaviors to Preserve

### Workspaces and tabs (Ecto)
- A workspace belongs to one server and has a root dir; its tabs (Terminal, SFTP, Git, Viewer) live in `workspaces.json` (`compose-app/.../workspace/`).
- Each SFTP tab owns its directory (`Tab.Sftp.dir`). The app never overwrites the user's manual navigation.
- Closing a terminal is always `TerminalSession.close()` before `SshShellChannel.close()`: the channel close frees the native shell handle.

### Herdr integration
- Only Herdr-enabled servers use the single-session route: one terminal runs Herdr's full normal UI. Herdr owns remote workspaces, tabs, and panes; do not restore app-managed remote terminal cards or per-pane attachments. Plain SSH/local retain app workspaces and multiple terminals.
- Git/SFTP open full-screen bottom sheets without disposing the terminal. Capture the originating remote workspace and pane cwd (`foregroundCwd`, then `cwd`) when opening; never follow live cwd changes while a sheet is open.
- File collections and SFTP browser state are scoped by server + control socket/session + remote Herdr workspace ID. Retain SFTP navigation, scroll, tree mode, and expanded folders across sheet dismiss/reopen while connected. Use pane cwd only to initialize a new browser, never to overwrite resumed navigation.
- Each preview open creates a new viewer, including repeated paths. Async completions retain their originating collection. Browse previews by horizontal swiping and close them with the top close button; there is no Herdr file grid.
- The Agents button replaces the tabs button. Retain collapsed workspace groups and keyboard visibility across the Agents sheet. Group panes by tab without numbered tab headings. Start focus requests concurrently with the sheet's hide animation; remove the sheet only after animation completes.
- Context uses server-global snapshot focus, not verified client-specific focus. Agent statuses are sampled every 5 seconds; terminal output remains live. Keep this limitation explicit and route refreshes through `HerdrControlClient.refresh()`'s existing coalesced loop, not independent snapshot fetches.

### SSH command execution
- `SshSession.execCommand` accepts POSIX scripts and executes them through `/bin/sh`, independent of the account's login shell (which may be fish). Preserve this at the SSH transport layer rather than adding consumer-specific shell workarounds.
