# Changelog

All notable changes to the Git AI Studio IntelliJ plugin are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
