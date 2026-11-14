# Nanote — Roadmap

## Part 1 — Foundation, architecture & UI (this release, v1.1.0) ✅

* Repository audit of the v1.0.0 single-module app
* 15-module Gradle architecture (core / feature layering, version catalog, wrapper)
* Offline mandate: AI + INTERNET permission + OkHttp removed
* Home dashboard, library with all management features, notebook form,
  nested folders, settings
* Room v2 (+ migration), DataStore preferences, activity feed
* Tablet-first adaptive navigation, full en/ar localization
* CI: build + unit tests + lint + signed release
* Documentation set (README, ARCHITECTURE, TECH_STACK, FEATURES, ROADMAP, CHANGELOG)

## Part 2 — Handwriting engine (next)

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

## Part 3 — Organization & productivity

* Trash with restore (soft-delete instead of hard delete)
* Tags & smart lists, saved filters
* Custom notebook covers (images) and template editor
* Backup/restore to local file (user-initiated export/import of everything)
* Advanced search (inside document content)
* Phone layout refinements (adaptive detail panes)

## Part 4 — PDF suite

* PDF import pipeline upgrade (page insertion into notebooks, page ranges)
* PDF export options (page selection, backgrounds)
* PDF forms & links support where pdfium allows
* Annotation summary sidebar
* Performance: render-farm profiling on low-RAM tablets

## Continuous

* Keep CI green (build + tests + lint) on every PR
* Expand the Robolectric suite with every new repository capability
* Maintain the migration chain (never destructive)
