# Changelog

All notable changes to the Git AI Studio IntelliJ plugin are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.4.7] - 2026-06-05

### Fixed

- **Plugin version always displayed as v0.1.0.** The webview read `window.__GITAI_PLUGIN_VERSION__` in React
  effects before the `onLoadEnd` bootstrap injection ran, hit the hard-coded `"0.1.0"` fallback, and never
  re-read. The version (and `__GITAI_HOST__`) is now injected as an inline `<script>` while serving
  `index.html`, so the globals are ready before any read; the fallback is an empty string instead of a fake
  old version.
- **Settings "Source code:" link had no visible URL.** The About card now shows a clickable
  `bujueyunjian/git-ai-studio-idea` link with an external-link icon (matching the desktop Settings style).
- **Blame drill-down on a historical commit reported "file not in this commit".** `git-ai blame` does not
  accept a commit ref (`Unknown option: --`), so every non-HEAD drill-down failed and the failure was
  mislabeled as `file_not_in_head`. The webview blame path now calls `git-ai blame-analysis --json
  '<payload>'` with `options.newest_commit` (mirroring the desktop `run_blame_analysis`/`convert_analysis`,
  incl. the mandatory `use_prompt_hashes_as_names`), with `rev-parse` / `cat-file -e` pre-checks separating
  `ref_not_found` / `file_not_in_head` from real failures (which now surface as loud errors). `blameJson` is
  narrowed to HEAD-only for the native gutter / Annotate / status-bar callers.

## [0.4.6] - 2026-06-05

### Fixed

- **Right-click Blame failed on git-ai 1.5.x.** The editor gutter, native Annotate column, status bar, and
  file AI-share popup now call the public `git-ai blame --json` command instead of the removed internal
  `blame-analysis` JSON payload path, fixing `Invalid JSON payload: key must be a string`.
- **Dashboard default repository.** When no Dashboard aggregate set has been explicitly configured, the IDE
  plugin now uses the current project repository by default; if an IDEA project has multiple Git roots, it
  falls back to the first IDE VCS root.
- **Repository navigation.** The Repo page is again registered in the runtime router and sidebar, so
  "Go to Repositories" buttons no longer fall back to Dashboard.
- **Settings i18n.** The sidebar Settings label and the simplified Settings cache section now use locale
  keys instead of rendering raw `nav.settings` / hand-written language branches.

## [0.4.5] - 2026-06-05

### Fixed

- **Blank blame popup — real root cause (v0.4.4 was necessary but insufficient).** v0.4.4 fixed the response
  *envelope* shape, but the dialog still went blank: `transformBlame` passed git-ai's `prompt_records` through
  verbatim, and git-ai 1.5.2 records carry no `other_files`/`commits` (nor a top-level `metadata`). The
  webview's `BlamePromptDetails` reads `record.other_files.length`/`record.commits.length` as required →
  `undefined.length` TypeError when clicking an AI line → with no ErrorBoundary the whole React tree unmounted
  → blank panel. `transformBlame` now backfills `other_files: []` / `commits: []` per record and synthesizes
  `metadata: {is_logged_in:false, current_user:null}`, mirroring the desktop Rust `convert_analysis`.
- **Webview ErrorBoundary.** A render exception in any single component now degrades to a local error card
  (with Retry) instead of blanking the entire JCEF tool window.
- **Commit-attribution drill-down (per-file AI lines + Notes "show original") returned nothing.**
  `list_ai_lines_in_commit` and `show_ai_note` parsed `refs/notes/ai` as pure JSON and returned shapes that
  didn't match the frontend contracts, so `status` was never `ok` → silent 0 / fail. Both now parse the real
  note format (text attestation section + `---` + JSON metadata, per upstream `notes_ai.rs`) and return the
  correct `AiLinesResult` / `ShowNoteResult` tagged unions.
- **Gutter "Blame" showed no model / silently failed.** `agent_id` is an object `{tool,id,model}`, but the
  editor Blame action and the native Annotate "AI" column called `.asString` on it →
  `UnsupportedOperationException` → "AI attribution failed" / the column vanished. Both now read
  `agent_id.model`.
- **"Select folder" did nothing on some machines (P1).** The native folder chooser ran with the default
  modality from a pooled thread; on some window managers (Linux-Wayland / multi-monitor Windows) the modal
  dialog could open unfocused or behind the main window. It now runs with `ModalityState.any()` and logs
  open/result to idea.log for diagnosis.

## [0.4.4] - 2026-06-05

### Fixed

- **Blank blame popup.** In the Stats commit detail, clicking a file to open the line-level blame drill-down
  showed an empty dialog: `read_file_at_*` returned `{path,binary,content,...}` instead of the expected
  `{status:"ok",text,size}` (and mis-detected every text file as binary), and `get_blame*` returned the raw
  payload instead of `{status:"ok",payload}`. Both now return the correct tagged-union shapes, so the file
  content and AI overlay render.

## [0.4.3] - 2026-06-05

### Changed

- Right-click "Git AI" submenu items now use git-ai's own vocabulary — **Blame** (per-line gutter),
  **Stats** (this file's AI share inline), **Blame in Panel**, **Dashboard** — instead of verbose labels.

## [0.4.2] - 2026-06-05

### Changed

- Right-click actions are now grouped under a single **"Git AI" submenu** (editor and Project view) with
  self-explanatory names — *AI Share of This File*, *Toggle AI Line Markers*, *Open File Attribution
  (Panel)*, *Open Project Dashboard (Panel)* — instead of four flat "(Git AI)"-suffixed items.

## [0.4.1] - 2026-06-05

### Added

- **In-editor right-click "AI Share of This File".** Right-click in any file → a balloon pops up *in place*
  (no panel/board switch) with the file's committed AI % (AI lines / total, via git-ai blame-analysis) and,
  if there are uncommitted changes, the working-tree AI / human / unknown line counts (via `git-ai status`).

## [0.4.0] - 2026-06-04

### Added

- **Native "AI by File" tab.** The Git AI Studio tool window now has a second, fully-native tab (JBTable):
  for the current HEAD commit it lists each changed file with its AI lines / added lines / **AI %** (purple),
  sorted by AI share; double-click opens the file. Computed off-EDT (git diff numstat + git-notes
  attestations), bounded to one commit. Per-file detail in IntelliJ-native style.
- **Update check.** On startup the plugin checks GitHub for a newer release and, if found, shows an IDE
  notification with a "下载更新 / Download update" action. Version-only network call — no code/data upload.

### Changed

- The JCEF tool window now uses **off-screen rendering** so the panel resizes (splitter) and drags/floats
  normally — previously the heavyweight browser swallowed those mouse events.
- UI now **defaults to Simplified Chinese** (no longer mis-detecting the JCEF browser locale as English).
- About → privacy wording updated to reflect the version-only update check.

## [0.3.1] - 2026-06-04

### Fixed

- VCS Log "AI" column showed no values even when attribution existed: resolve the commit sha via the more
  stable `getCommitMetadata` (falling back to the internal `getId`/`getCommitId`), wrap `getValue` in
  try/catch, and log warnings (git-ai not found / `git-ai stats` failures) to idea.log for diagnosis.

## [0.3.0] - 2026-06-04

### Added

- **Native Annotate (blame) "AI" column.** Running IntelliJ's built-in *Annotate with Git Blame* now adds
  an **AI** column next to author/date, marking each line AI vs human (purple = AI), via `git-ai
  blame-analysis`. Computed once when the annotation opens (off-EDT), so the gutter reads a ready map.
  The standalone *Toggle AI Attribution* editor action stays as a fallback until the native column is proven.

### Changed

- VCS Log "AI" column is now explicitly `isEnabledByDefault` (shows without manual enabling on first use;
  on a project whose Log layout predates the plugin, enable it once via the Log header → Show Columns).

## [0.2.0] - 2026-06-04

### Added

- **Native Git integration — VCS Log "AI" column.** Per-commit AI-authorship share now shows as a
  sortable column in IntelliJ's built-in Git Log (Git tool window → Log), right where you review history.
  The column reads only an in-memory cache on the EDT (never blocks the UI); a background worker warms it
  by running `git-ai stats` per commit and repaints when ready. Purple text echoes the locked AI color.

### Changed

- Restored a **slim Settings** page (language switch, git-ai auto-update status, clear-cache, about) — the
  language switcher had been lost when the full desktop Settings page was cut. Dropped the desktop-only
  sections (theme/tray/auto-launch/pet/notification watchers/in-app updater).
- Restored the **Hooks** page (one-click official `git-ai install` + per-agent hook status); the
  Claude-settings-merge / backup-restore UI remains out of scope.
- Wired `get_hooks_status`, `read_claude_settings`, `list_settings_backups`, `get_git_ai_config` as local
  file reads so the restored pages render real data.
- Diagnostics now shows **per-agent hook detection** (Claude/Cursor/Codex/OpenCode/Gemini/Pi).
- Added a **status bar widget** with the current file's AI share, and editor/project-view right-click
  actions to open a file's attribution or the project Dashboard.

## [0.1.0] - 2026-06-04

### Added

- Initial IntelliJ IDEA plugin: brings git-ai-studio into a JCEF tool window by reusing the desktop
  React UI verbatim (transport swapped from Tauri to a JCEF JS bridge via Vite aliases).
- Kotlin backend (`CommandDispatcher`) that reproduces the desktop Tauri command surface by shelling out
  to `git-ai` / `git` with matching args, flags and timeouts.
- In-editor **per-line AI attribution** in the gutter (native `TextAnnotationGutterProvider`):
  AI lines purple, human lines blue, via `git-ai blame-analysis`.
- Editor & project-view right-click actions: **View This File's AI Attribution** and
  **View Project AI Metrics** (open the tool window on the matching view).
- **Status bar widget** showing the AI-authorship share of the current file, refreshing on editor switch.
- **Per-agent hook detection** in Diagnostics for Claude / Cursor / Codex / OpenCode / Gemini / Pi,
  plus a one-click official hook install (`git-ai install`).
- The repository defaults to the open project's git root — no first-run setup wizard.
- IDE light/dark theme is mirrored into the web UI and follows LAF changes live.

### Notes

- Pared down to an IDE-focused surface — Dashboard, Stats (with line-level blame), People, Notes,
  Diagnostics. Desktop-only pages (install/hooks/logs/repo/checkpoints/settings), background watchers,
  the onboarding wizard, tray, auto-launch, in-app updater and the floating pet are intentionally omitted.
