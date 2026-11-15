# Nanote — Premium offline notes & PDF annotation for Android tablets

<p align="center"><b>v1.4.0 (Part 4 — final integration, QA & release)</b> · Kotlin · Jetpack Compose · Material 3 · MVVM + Clean Architecture · Hilt · Room · DataStore</p>

Nanote is a premium note-taking app with a custom vector handwriting
engine and a high-performance PDF reader/annotator, inspired by the
workflows of Notewise, GoodNotes and Samsung Notes — with an independent
**Premium Blue** visual identity.

**100% offline by design:** no cloud, no accounts, no analytics, no
network permission. Everything lives on the device.

* **Language:** Kotlin 2.0 — fully modular Gradle project (16 modules)
* **UI:** Jetpack Compose + Material 3, custom design system, adaptive
  tablet-first shell (navigation rail ⇄ bottom bar via WindowSizeClass)
* **Engine:** custom five-layer handwriting engine (capture → input →
  document → geometry → rendering), fully vector end-to-end
* **PDF:** pdfium rendering + pdfbox text/page-ops/writing; imports are
  never modified — page operations work on a working copy
* **Architecture:** MVVM + repository pattern, Room index + `.nanote` JSON
  document files on disk, DataStore preferences
* **i18n:** English + Arabic (full RTL), per-app language on Android 13+
* **minSdk 26 · targetSdk 35 · light/dark/system themes + Material You + 7 accent colors**

---

## What's in the box (all four parts integrated)

* **Library & documents** — grid/list views, search, sorting (modified /
  created / alphabetical / custom), multi-selection with batch
  move/favorite/delete, metadata sheets, recent-activity feed
* **Notebooks & folders** — covers, icons, templates, sizes; nested
  folders with cycle-safe moves and delete-with-lift
* **Handwriting** — five pen engines (ballpoint, fountain, brush, pencil,
  highlighter) with pressure/tilt capture, palm rejection, three eraser
  modes (including bit-exact partial erase), lasso/rectangle selection
  with move/resize/rotate, vector clipboard, grouping
* **Canvas** — paged mode (A4/A5/Letter/Legal, 12 templates) and infinite
  mode; rich text (6 fonts, styles, lists, bidi AR/EN), images (picker +
  camera), 9 shape kinds with deterministic shape recognition
* **PDF** — import via SAF, lazy rendering, bookmarks, reading positions,
  long-press text selection (highlight/underline/strikethrough), page
  operations (delete/duplicate/reorder/rotate/extract/insert/merge)
* **Search** — grouped results: documents, folders, tags and matches
  **inside PDF text** (page + snippet), with recent-search history
* **Export** — PDF (flattened / keep-text-selectable markup / original),
  PNG, JPEG, `.nanote`, page subsets, Android Sharesheet + Print Framework
* **Backup** — versioned offline `.nanote-backup` archives; previewed
  restore that never overwrites existing data
* **Settings** — themes, accents, language, defaults, input behavior,
  autosave, export defaults, storage, backup & restore

See `documentation/FEATURES.md` for the full feature matrix with honest
status labels, and the deep dives: `HANDWRITING_ENGINE.md`,
`PDF_ENGINE.md`, `STORAGE_AND_BACKUP.md`, `DATABASE_SCHEMA.md`,
`ARCHITECTURE.md`, `TESTING.md`, `RELEASE.md`.

## Part 4 (this release)

Part 4 integrated the previously **unmerged Part 2 branch** (the
handwriting engine) with Part 3's PDF/storage/search/export work,
eliminated the resulting conflicts (models, eraser, export painter,
menus, settings), hardened security and prepared the release:

* Part 2's 4 new shape kinds ported into the Part 3 export painter
* Committed release keystore + passwords **removed** from the tree;
  env-var/secret-based signing with a debug-signed CI fallback
* CI builds **APK + AAB** artifacts and can restore the signing key from
  GitHub secrets (see `documentation/RELEASE.md`)
* Room schemas v2/v3 committed; version 1.4.0 (versionCode 5)
* 6 new integration tests covering the merge seams; **98 unit tests green**
* Full documentation set (12 files)

## Building

Requirements: **JDK 17**, Android SDK (platform 35 + build-tools).

```bash
git clone https://github.com/ahmedhaidr999-droid/Nanote.git && cd Nanote

# (Optional) point to your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Debug build
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Unit tests (98)
./gradlew testDebugUnitTest :core:model:test :core:storage:test --continue

# Release artifacts (R8; see documentation/RELEASE.md for signing)
./gradlew assembleRelease bundleRelease
```

Release signing credentials are **never committed** — see
`documentation/RELEASE.md` for the secure setup (local properties file,
environment variables or GitHub secrets).

To open in Android Studio: **File → Open** on the project root.

## Project layout

```
app/                     # single-activity shell: adaptive nav, NavHost, DI
core/
├── common/              # dispatchers, app-scope, logging
├── model/               # domain models + stroke geometry (StrokeOps, shapes)
├── navigation/          # route constants
├── design/              # NanoteTheme + shared Compose components + strings
├── database/            # Room: entities, DAOs, v1→v2→v3 migrations, schemas
├── storage/             # .nanote format v3 + migrations + backup format
├── data/                # repositories (Room + files), settings stores
├── graphics/            # handwriting engine (canvas, tools, selection, clipboard)
└── pdf/                 # pdfium renderer + pdfbox text/page-ops/export painter
feature/
├── home/                # dashboard: recents, favorites, grouped search, activity
├── library/             # document browser (filters, selection, sort)
├── notebooks/           # notebook form (cover/icon/template/size)
├── folders/             # nested folder browser
├── settings/            # appearance, input, defaults, export, backup, storage
└── editor/              # the canvas editor: toolbar, pages panel, export sheet
documentation/           # 12 documentation files
```

## Privacy

* No `INTERNET` permission — the app **cannot** transmit documents
* Camera + vibrate only; camera is used solely for inserting photos
* Android cloud auto-backup disabled (`allowBackup=false` + full data
  exclusion rules) — documents leave the device only through explicit
  user-initiated export or local backup archives
* No crash reporting, no analytics, no AI, no accounts

## Diagnostics

All subsystems log through `com.nanote.core.common.AppLog` with the
`Nanote.` tag prefix (e.g. `Nanote.DocumentRepository`, `Nanote.PdfEngine`).

## License

Provided as-is for evaluation and testing.
