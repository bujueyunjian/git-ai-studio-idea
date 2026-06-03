# Git AI Studio — IntelliJ IDEA Plugin

English | [简体中文](README.zh-CN.md)

Local AI code-authorship insight **inside your JetBrains IDE**, built on top of the external
[`git-ai`](https://github.com/git-ai-project/git-ai) CLI. It is the IDE sibling of the
[git-ai-studio](https://github.com/bujueyunjian/git-ai-studio) desktop app — same parsing, same
zero-upload / zero-telemetry promise, now living in a tool window next to your editor, plus an
IDE-native feature the desktop app can't offer: **per-line AI attribution right in the editor gutter.**

> All parsing happens locally via the `git-ai` CLI. Nothing is uploaded. No accounts, no cloud.

## What it does

- **Commit attribution (Stats)** — per-commit AI / human / unknown line breakdown, tool·model split, merge & note hints.
- **Dashboard** — a cross-repo / single-repo timeline of AI-authorship over a time range, with daily buckets.
- **People** — per-author breakdown (AI share, commits, drill-down) over a time window.
- **Notes** — the `refs/notes/ai` authorship log viewer (prompts, sessions, attestations).
- **Diagnostics & install** — git-ai presence/version, environment health (`git-ai debug`).
- **In-editor line attribution (gutter)** — run `git-ai blame-analysis` on the current file and mark
  each line in the gutter: **AI → purple, you → blue** (the same locked color invariant as the desktop
  Ink-pet — color *is* data). Right-click in the editor → **Toggle AI Attribution (Git AI)**.

## Architecture

This is a Tauri → IntelliJ port that **reuses the entire git-ai-studio React UI** rather than rebuilding it:

```
┌──────────────────────────────────────────────┐
│ IntelliJ Tool Window  (right dock)            │
│  ┌────────────────────────────────────────┐  │
│  │ JBCefBrowser (bundled Chromium)         │  │   reused verbatim:
│  │   git-ai-studio React UI                │  │   recharts · shadcn/ui
│  │   Dashboard / Stats / People / Notes …  │  │   Tailwind v4 · i18next
│  └────────────────────────────────────────┘  │
└───────────────┬──────────────────────────────┘
                │  JS bridge (window.__gitaiSend / __gitaiReceive)
                ▼
   Kotlin CommandDispatcher  ──shells──▶  git-ai / git  (LC_ALL=C, --json)
                │
                └─ Editor gutter annotator (native LineAnnotation) ─▶ git-ai blame-analysis
```

- **UI reuse via transport swap.** The desktop frontend talks to a Tauri backend through `@tauri-apps/*`.
  Here, Vite `resolve.alias` redirects every `@tauri-apps/*` import to a thin JCEF-bridge shim
  (`webview/src/bridge/*`) — so the React source is reused **without a single edit to business code**.
  `invoke` / `listen` / `emit` ride the JS bridge; desktop-only bits (updater, tray, auto-launch, the
  floating pet) become no-ops.
- **Kotlin replaces Rust.** `CommandDispatcher` reproduces the desktop Tauri command surface by shelling
  out to `git-ai` / `git` with the exact same args, flags and timeouts as the desktop backend
  (git-ai upstream is the authority for schema/metrics/thresholds).
- **Theme follows the IDE.** Light/dark is driven by the IDE LAF (not the web app): Kotlin toggles the
  `.dark` class on load and on every LAF change.
- **Editor gutter is native.** Independent of the webview — a `TextAnnotationGutterProvider` paints the
  per-line attribution, so you see AI vs. human authorship while you code.

## Requirements

- IntelliJ IDEA (or other JetBrains IDE) **2024.3+** with a JetBrains Runtime that includes JCEF (the default).
- The [`git-ai`](https://github.com/git-ai-project/git-ai) CLI on your `PATH` (or set its path; the plugin
  also probes `~/.local/bin`, `~/.cargo/bin`, `/opt/homebrew/bin`, `/usr/local/bin`).
- JDK 21 to build.

## Build & run

```bash
# 1) Build the reused web UI once (outputs to src/main/resources/web)
cd webview && pnpm install && pnpm build && cd ..

# 2) Run the plugin in a sandbox IDE (Gradle auto-rebuilds the web UI if node_modules exists)
./gradlew runIde

# or package a distributable zip
./gradlew buildPlugin   # → build/distributions/git-ai-studio-idea-<version>.zip
```

Then open the **Git AI Studio** tool window on the right edge.

## Status (v1)

Fully wired (real `git-ai` / `git` data): repository resolution & selection, recent commits,
commit stats & working status, single-repo & cross-repo history, people breakdown, range summary,
git-notes list/show, changed-files & AI-lines per commit, branches, file listing/reading at any ref,
blame, whoami, raw show, effective ignore patterns, settings persistence, directory picker, and the
**editor gutter attribution**.

Honest follow-ups (return a clear error in v1 rather than faking data): streaming git-ai install /
hook configuration, daemon repair, Claude settings merge, checkpoints/mock, log viewer, agent-by-agent
hook detection in the Diagnostics page (the page renders the `git-ai debug` report today). Stats are
cached in-memory per session (the desktop app uses SQLite).

## License

[MIT](LICENSE). Independent OSS project, not affiliated with the Git AI commercial team — it only
consumes the open-source `git-ai` CLI and the public `refs/notes/ai` standard.
