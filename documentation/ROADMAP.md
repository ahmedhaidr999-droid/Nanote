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

## Part 2 — Handwriting engine, canvas & notebook editor (v1.2.0) ✅

* Five-layer vector handwriting engine (capture → input → document →
  geometry → rendering); five pen engines; pressure/tilt/timestamp
  capture with historical replay; palm rejection & finger policy
* Three eraser modes incl. bit-exact partial erase (capsule splits)
* Lasso/rectangle geometric selection, transforms, vector clipboard,
  grouping; infinite canvas mode; 12 templates; rich text; images;
  9 shapes + deterministic recognition; format v3
* Developed on `feature/part-2-handwriting-editor`; **integrated into
  main by Part 4** (it had been developed in parallel with Part 3)

## Part 3 — PDF engine, storage, search & export (v1.3.0) ✅

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

## Part 4 — Final integration, QA & release (v1.4.0) ✅ (this release)

* **Integrated the unmerged Part 2 branch with Part 3's main** — the
  core Part 4 deliverable (20 overlapping files, 14 conflicts resolved)
* Merge-seam integration tests (`MergedContentFormatTest`,
  `MergedContentPaintTest`) — 98 unit tests green
* Security audit: committed keystore removed, env/secret-based signing,
  privacy posture re-verified (no INTERNET, backup disabled)
* Release preparation: v1.4.0/versionCode 5, APK + AAB verified builds,
  R8, CI artifacts, RELEASE.md with secure signing instructions
* Documentation completed to 12 files with honest status labels

## Post-1.4.0 backlog (not yet scheduled)

* **Device QA pass on a real stylus tablet** — the critical remaining
  gate before any "production-ready" claim (see TESTING.md checklist)
* Physical-device performance profiling (Android Profiler /
  Macrobenchmark) and Baseline Profiles where beneficial
* Proper Arabic PDF export (embedded Arabic font + shaping engine)
* Trash/soft-delete with restore window for documents
* Smart lists, custom notebook covers, phone detail panes
* Drag-and-drop reordering (library custom order, page manager)
* PDF forms & links support where the engines allow; annotation
  summary sidebar
* Editor runtime settings consolidation into DataStore
* Rulers and further handwriting polish

## Continuous

* Keep CI green (build + tests + lint) on every PR
* Expand the Robolectric suite with every new repository capability
* Maintain the migration chain (never destructive)
