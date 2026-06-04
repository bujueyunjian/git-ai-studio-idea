# Changelog

All notable changes to the Git AI Studio IntelliJ plugin are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
