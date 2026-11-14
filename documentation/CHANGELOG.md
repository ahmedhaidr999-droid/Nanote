# Changelog

All notable changes to Nanote are documented here.

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
