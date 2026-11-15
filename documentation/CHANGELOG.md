# Changelog

All notable changes to Nanote are documented here.

## [1.2.0] — Part 3: PDF engine, local storage, search & export

### PDF engine
- Five separated responsibilities (render / extract / annotate / page ops /
  export) across pdfium-android and pdfbox-android; no invented APIs.
- Page operations on a working copy (`edited.pdf`) — the imported
  `original.pdf` is never modified: delete, duplicate, reorder, rotate,
  extract-to-new-document (split), insert-pages-from-PDF, merge.
- Strikethrough text-mark annotation (model, renderer, selection menu,
  eraser/selection integration, PDF export).
- Export modes: flattened, keep-text-selectable (real PDF markup
  annotations), original-only; optional page subsets; every export is
  verified by reopening with pdfium (independent renderer).
- PDF details sheet (file name/size/pages + document info when present).
- Reading positions (page + zoom) persisted and restored per document.
- PDF bookmarks: toggle on the current page, listing, jump, delete.

### Local storage & backup
- Room schema **v2 → v3 migration** (file metadata columns; new tables:
  tags, note_tags, pdf_text_pages, pdf_bookmarks, reading_positions,
  search_history) — unit-tested, non-destructive.
- Local full-text search index over extracted PDF text (built at import in
  the background, rebuilt after page operations; no OCR claimed).
- Document tagging with usage counts.
- Versioned offline `.nanote-backup` ZIP format (manifest + index rows +
  files) with future-version rejection; full backup and previewed restore
  that never overwrites existing data ("(Restored)" rename on collisions).
- Autosave preferences (enable + debounce) with an editor save-status
  chip; the final save on exit always runs.
- Android cloud auto-backup disabled (`allowBackup=false` + data-extraction
  exclusion rules) — documents never leave the device implicitly.

### Search
- Grouped results on the home screen: documents, folders, tags and
  matches inside PDF text (page + snippet); tapping a hit opens the
  document. Recent searches as chips with per-entry removal and clear-all.

### Export & sharing
- Export sheet: PDF / PNG / JPEG / .nanote with page selection.
- Android Sharesheet via FileProvider and Android Print Framework.
- Offscreen notebook page painter (template + ink + images + text) for
  raster exports.

### Tests
- `PdfPageOpsTest` (Robolectric): every page operation verified by
  reopening the produced PDF and reading back per-page text.
- `BackupFormatTest` (JVM): round-trip, future-version rejection, partial
  archives, dated default file names.
- `Part3ModelTest`: strikethrough serialization round-trip + commands.
- `MigrationTest`: v2→v3 preservation + all new tables.
- `DocumentRepositoryImplTest`: tags, search history, bookmarks, reading
  positions.

### Documentation
- New: `PDF_ENGINE.md`, `STORAGE_AND_BACKUP.md`; updated FEATURES.md,
  ROADMAP.md, README.md.

### Preserved
- All Part 1 library/folder/notebook management and the complete
  handwriting editor (ink, PDF annotation, split screen, autosave,
  customizable toolbar).

## [1.1.0] — Part 1 Foundation

### Architecture
- **BREAKING (internal):** restructured the single-module app into 15 Gradle
  modules — `app`, `core/{common,model,navigation,design,database,storage,data,graphics,pdf}`,
  `feature/{home,library,notebooks,folders,settings,editor}` — with a
  version catalog, the Gradle 8.9 wrapper, and a rewritten CI pipeline.
- Room schema **v1 → v2 migration** (notebook presentation columns, nested
  folders, `recent_activity` table). Existing v1.0.0 data is preserved.
- User preferences moved to **Preferences DataStore** with a one-time
  import of the v1.0 appearance keys.
- Repositories renamed to `DocumentRepository`/`DocumentRepositoryImpl`;
  domain model extracted to `:core:model`.

### Offline mandate (spec compliance)
- Removed the AI service, Ask-AI sheet, translate and web-search actions.
- Removed `android.permission.INTERNET` and the OkHttp dependency.

### Added
- Home dashboard: search (documents + folders), quick actions,
  recently-opened, favorites, notebooks, root folders, activity feed.
- Library: perspectives (all/notebooks/imported/favorites/recent), grid and
  list views, sorting (modified/created/alphabetical/custom), multi-selection
  with batch move/favorite/delete, metadata sheets, confirmation dialogs.
- Notebook management: create/edit with cover colour, icon, page template,
  orientation and page size; defaults configurable in Settings.
- Folder management: nested folders, breadcrumbs, tree picker, cycle-safe
  moves, delete-with-lift.
- Recent activity feed + last-opened tracking.
- Tablet-first adaptive shell: navigation rail ⇄ bottom bar (WindowSizeClass).
- Settings: light/dark/system themes, dynamic colors, 7 accent colors,
  language (en/ar, per-app locales on Android 13+), library and notebook
  defaults, storage panel, about.
- Gradle wrapper + CI (debug build, unit tests, lint, instrumented-test
  compile, signed release APK with artifact upload; tag releases fixed by
  adding `permissions: contents: write`).

### Preserved
- The complete v1.0.0 editor (ink, PDF, split screen, autosave, export,
  customizable toolbar) — carried into `:feature:editor`/`:core:graphics`/
  `:core:pdf` and wired into the new navigation.

### Tests
- `MigrationTest` (hand-built v1 database → v2, Robolectric)
- `DocumentRepositoryImplTest` (nesting, counts, cycles, filters, batches,
  custom order, activity cascade)
- `DocumentSorterTest` (locale-aware sorting, pure JVM)
- `DocumentFormatTest` (round-trip + truncated-file recovery)
- `AppLaunchTest` (instrumented smoke, compiled in CI)

## [1.0.0] — Initial release

Single-module Kotlin/Compose app: infinite-canvas editor (pen, pencil,
brush, highlighter, eraser, shapes, text, images, lasso, laser), PDF
import & annotation (pdfium + pdfbox text layer), split screen, undo/redo,
autosave, `.nanote` format, export to PDF, Notewise-style toolbar, en/ar
localization, signed release CI.
