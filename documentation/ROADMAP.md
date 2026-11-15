# Nanote — Roadmap

## Part 1 — Foundation, architecture & UI (v1.1.0) ✅

* Repository audit of the v1.0.0 single-module app
* 15-module Gradle architecture (core / feature layering, version catalog, wrapper)
* Offline mandate: AI + INTERNET permission + OkHttp removed
* Home dashboard, library with all management features, notebook form,
  nested folders, settings
* Room v2 (+ migration), DataStore preferences, activity feed
* Tablet-first adaptive navigation, full en/ar localization
* CI: build + unit tests + lint + signed release
* Documentation set (README, ARCHITECTURE, TECH_STACK, FEATURES, ROADMAP, CHANGELOG)

## Part 2 — Handwriting engine (absorbed into the Part 3 release)

The v1.0.0 engine was preserved and carried forward (ink with pressure,
eraser with pixel-split, lasso, shapes, text, images, split screen,
undo/redo, autosave). Part 3 added strikethrough text marks, page
operations, bookmarks and reading positions on top of it; the deeper
rework items below remain open.

* Upgrade `:core:graphics` in place: structured stroke model, pressure
  curves, stroke smoothing, low-latency input (MotionEvent batching /
  stylus prediction)
* Page model with templates as first-class renderables (the
  `PageTemplatePattern` previews become real page backgrounds)
* Page management UI: add / reorder / delete pages, per-notebook template
  changes
* Drag-and-drop reorder for custom library order (replacing move up/down)
* Consolidate editor runtime settings (tool/toolbar config) from
  `SettingsStore` (SharedPreferences) into DataStore
* Editor polish: rulers, zoom gestures refinement, shape recognition

## Part 3 — PDF engine, storage, search & export (this release, v1.2.0) ✅

* PDF engine with separated responsibilities (pdfium render + pdfbox
  write/extract) and verified exports
* Page operations (delete/duplicate/reorder/rotate/extract/insert/merge)
  on a working copy — imported originals never modified
* Strikethrough marks, bookmarks, reading positions, PDF details sheet
* Room v3 (tags, PDF text index, bookmarks, positions, search history)
* Local search across titles, folders, tags and PDF text + history
* Export sheet (PDF modes / PNG / JPEG / .nanote, page subsets) with
  Sharesheet + Print Framework
* Versioned offline backup & previewed restore
* Autosave preferences + save-status chip
* Still open from the original plan: trash/soft-delete, smart lists,
  custom covers, phone detail panes

## Part 4 — Final integration & QA

* Full-device QA pass on a real stylus tablet (import/annotate/export/
  backup/restore end-to-end)
* Remaining PDF items: forms & links support where the engines allow,
  annotation summary sidebar
* Performance profiling on low-RAM tablets
* Handwriting engine polish backlog from Part 2 (rulers, shape
  recognition, gesture reordering)

## Continuous

* Keep CI green (build + tests + lint) on every PR
* Expand the Robolectric suite with every new repository capability
* Maintain the migration chain (never destructive)
