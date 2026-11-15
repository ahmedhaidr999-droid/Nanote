# Nanote — Premium offline notes & PDF annotation for Android tablets

<p align="center"><b>v1.2.0 (Part 3 — PDF engine, storage, search & export)</b> · Kotlin · Jetpack Compose · Material 3 · MVVM + Clean Architecture · Hilt · Room · DataStore</p>

Nanote is a premium note-taking app with an infinite-canvas editor and a
high-performance PDF reader/annotator, inspired by the workflows of
Notewise, GoodNotes and Samsung Notes — with an independent **Premium Blue**
visual identity.

**100% offline by design:** no cloud, no accounts, no analytics, no network
permission. Everything lives on the device.

* **Language:** Kotlin 2.0 — fully modular Gradle project (15 modules)
* **UI:** Jetpack Compose + Material 3, custom design system, adaptive
  tablet-first shell (navigation rail ⇄ bottom bar via WindowSizeClass)
* **Architecture:** MVVM + repository pattern, Room index + `.nanote` JSON
  document files on disk, DataStore preferences (with migration from the
  v1.0 SharedPreferences)
* **i18n:** English + Arabic (full RTL), per-app language on Android 13+
* **minSdk 26 · targetSdk 35 · light/dark/system themes + Material You + 7 accent colors**

---

## Part 3 (this branch)

This is **Part 3 of 4** — the PDF engine, local storage, backup/restore,
search and export layer on top of the Part 1 foundation:

* Repository audit of v1.0.0 (single-module app) and restructure into
  15 Gradle modules with clean layering (see `documentation/ARCHITECTURE.md`)
* Offline mandate enforced: **AI features and the INTERNET permission were
  removed**; OkHttp is gone from the dependency tree
* Complete **library & document management**: grid/list views, search,
  sorting (modified/created/alphabetical/custom), multi-selection with batch
  move/favorite/delete, rename/duplicate/move/delete, metadata sheets
* **Notebook management**: create/edit with cover colour, icon, page
  template (plain/lined/grid/dotted/Cornell), orientation and page size
* **Nested folders** with breadcrumbs, folder tree picker, cycle-safe moves
  and delete-with-lift (nothing is lost when a folder is removed)
* **Recent activity feed** (new `recent_activity` table) + last-opened tracking
* Room **v1 → v2 migration** (preserves all v1.0.0 data)
* **DataStore** user preferences (theme, accent, language, library and
  notebook defaults) with one-time import from v1.0 settings
* Gradle **wrapper** added; CI builds debug + runs unit tests + lint +
  produces the signed release APK
* The v1.0.0 **editor is preserved and fully wired**, extended with
  strikethrough marks, a pages panel, bookmarks and reading positions

**New in Part 3:**

* **PDF engine** with separated responsibilities (pdfium rendering +
  pdfbox text/page-ops/writing); every export is verified by reopening
  it with an independent renderer. Imported originals are never modified —
  page operations (delete/duplicate/reorder/rotate/extract/insert/merge)
  work on an `edited.pdf` working copy
* **Local search** across document titles, folders, tags and **inside PDF
  text** (page + snippet), with recent-search suggestions and history
* **Export & sharing**: PDF (flattened / keep-text-selectable markup
  annotations / original), PNG, JPEG, `.nanote`, page subsets, Android
  Sharesheet and Print Framework
* **Backup & restore**: versioned offline `.nanote-backup` archives,
  previewed restore that never overwrites existing data
* **Storage**: Room v3 (tags, PDF text index, bookmarks, reading
  positions, search history) with a unit-tested v2→v3 migration;
  autosave preferences + editor save-status chip
* **Privacy**: Android cloud auto-backup disabled — documents never
  leave the device except through explicit user export

See `documentation/FEATURES.md` for the full feature matrix,
`documentation/PDF_ENGINE.md` and `documentation/STORAGE_AND_BACKUP.md`
for the deep dives, and `documentation/ROADMAP.md` for Part 4.

---

## Building

Requirements: **JDK 17**, Android SDK (platform 35 + build-tools).

```bash
git clone https://github.com/ahmedhaidr999-droid/Nanote.git && cd Nanote

# (Optional) point to your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Debug build
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Unit tests
./gradlew testDebugUnitTest :core:model:test :core:storage:test

# Signed release APK (test keystore included — replace for production)
./gradlew assembleRelease
```

The signing keystore lives in `keystore/` (test key) — **replace it with
your own for production and never commit a real release key.**

To open in Android Studio: **File → Open** on the project root.

---

## Project layout

```
app/                     # single-activity shell: adaptive nav, NavHost, DI
core/
├── common/              # dispatchers, app-scope, logging
├── model/               # domain models (documents, folders, prefs, sorting)
├── navigation/          # route constants
├── design/              # NanoteTheme + shared Compose components + strings
├── database/            # Room: entities, DAOs, v1→v2 migration
├── storage/             # .nanote file format + migrations
├── data/                # repositories (Room + files), DataStore prefs
├── graphics/            # ink canvas engine (Part 2 foundation)
└── pdf/                 # pdfium renderer + pdfbox text layer
feature/
├── home/                # dashboard: recents, favorites, folders, activity
├── library/             # document browser (filters, selection, sort)
├── notebooks/           # notebook form (cover/icon/template/size)
├── folders/             # nested folder browser
├── settings/            # appearance, defaults, storage, about
└── editor/              # the canvas editor (from v1.0.0, AI removed)
documentation/           # architecture, tech stack, features, roadmap
```

## Diagnostics

All subsystems log through `com.nanote.core.common.AppLog` with the
`Nanote.` tag prefix (e.g. `Nanote.DocumentRepository`, `Nanote.PdfEngine`).

## License

Provided as-is for evaluation and testing.
