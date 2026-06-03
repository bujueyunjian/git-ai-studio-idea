# Changelog

All notable changes to the Git AI Studio IntelliJ plugin are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Initial IntelliJ IDEA plugin: brings git-ai-studio into a JCEF tool window by reusing the desktop
  React UI verbatim (transport swapped from Tauri to a JCEF JS bridge via Vite aliases).
- Kotlin backend (`CommandDispatcher`) that reproduces the desktop Tauri command surface by shelling out
  to `git-ai` / `git` with matching args, flags and timeouts.
- In-editor **per-line AI attribution** in the gutter (native `TextAnnotationGutterProvider`):
  AI lines purple, human lines blue, via `git-ai blame-analysis`.
- IDE light/dark theme is mirrored into the web UI and follows LAF changes live.
