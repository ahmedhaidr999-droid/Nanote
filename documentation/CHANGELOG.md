# Changelog

All notable changes to Nanote are documented here.

## [1.4.0] — Part 4: final integration, QA & release preparation

### Full application integration (the headline fix)
- **Part 2 (handwriting engine, canvas & notebook editor) had never been
  merged** — Part 3 was built on the Part 1 merge base. Part 4 integrates
  all three parts on main: 20 overlapping files, 14 content conflicts
  resolved semantically (models, eraser, repository, painter, menus,
  settings, strings).
- `DocumentContent` carries BOTH Part 2's `canvasMode` and Part 3's
  `strikeThroughs`; snapshot/undo/redo/export cover the union.
- The eraser commits shapes (Part 2) and strikethrough marks (Part 3) in
  one undoable batch.
- Part 2's four new shape kinds (double arrow, rounded rect, ellipse,
  polygon) ported into the Part 3 export architecture
  (`core/pdf/PdfContentPainter`).
- Editor UI: page strip (paged mode), pages panel, export sheet, PDF info
  sheet, canvas-mode switch, save-status chip and bookmark all coexist.
- Settings: input behavior (finger action, pressure curve, color memory)
  + export/autosave/backup sections merged.
- Room schema stays v3 (both branches shared an identical
  MIGRATION_1_2); exported schemas 2.json/3.json committed.

### Security & privacy hardening
- **Removed the committed release keystore and its passwords** from the
  tree and gitignored all keystore patterns (they remain in git history —
  the old key must never sign a public release; see RELEASE.md).
- Signing now resolves from `NANOTE_*` environment variables, then an
  untracked `keystore/keystore.properties`, else falls back to the debug
  key (clearly labeled, never presented as a store release).
- CI can restore the signing key from GitHub secrets
  (`NANOTE_KEYSTORE_BASE64` + friends) and now also builds the **AAB**.

### Release preparation
- Version 1.4.0 (versionCode 5); CI-sized Gradle memory with documented
  sandbox overrides; release job labeled honestly.

### Tests
- 6 new integration tests covering the merge seams:
  `MergedContentFormatTest` (Part 2 + Part 3 content in one `.nanote`
  file, legacy/part-only loads) and `MergedContentPaintTest` (merged
  content paints into a reopenable PDF; polygon vertex paths).
- **98 unit tests, 0 failures** (14 classes) on the merged tree; debug
  APK, androidTest compile, release APK and AAB all build.

### Documentation
- New: `RELEASE.md`, `DATABASE_SCHEMA.md`, `HANDWRITING_ENGINE.md`,
  `TESTING.md`. Updated: README, FEATURES (status labels), ARCHITECTURE,
  ROADMAP. Full set: 12 files.

### Honest limitations (carried and verified)
- No physical-device QA performed in this environment — see TESTING.md
  for the device checklist; no handwriting-performance claims are made.
- pdfium cannot load under JVM Robolectric; export unit tests verify via
  the independent pdfbox reload path (device pipeline re-verifies with
  pdfium).
- Arabic text export still uses WinAnsi-safe approximations (PDFBox
  Type1 limitation) — unchanged, documented.

## [1.2.0] — Part 2 Handwriting Engine, Canvas & Notebook Editor

### Handwriting engine
- Five distinct pen engines: ballpoint, **fountain** (pressure × velocity
  width), **brush** (strong pressure taper), **pencil** (seeded graphite
  grain), highlighter — each with color, thickness, opacity, pressure
  response and smoothing configuration.
- Pressure response curves (linear / soft / firm) baked into strokes at
  capture time so existing ink never re-renders differently.
- Full stylus capture: pressure, tilt (where reported), per-point timestamps,
  historical high-frequency event replay, exponential + commit smoothing.

### Palm rejection & input policy
- Configurable finger behavior (scroll / draw / ignore), hard palm guard
  while a stylus is active, hardware eraser tip and barrel button mapped to
  the eraser.

### Eraser
- Stroke, object and **partial** eraser. Partial erase splits strokes at
  interpolated capsule-boundary intersections, preserving the remaining
  geometry (coordinates, pressure, tilt, timestamps) bit-exactly — no
  rasterization.

### Editing & selection
- Undo/redo across all new operations (200 steps).
- Lasso + rectangle geometric selection (segment-polygon and Liang-Barsky
  intersection, not bbox-only), single-object tap, group semantics.
- Move, resize (4 corner handles), rotate (dedicated handle) with live ghost
  preview — strokes scale (incl. width) and rotate as vectors.
- Copy / cut / paste via a process-wide vector clipboard; group / ungroup;
  duplicate; delete.

### Canvas & pages
- **Infinite canvas mode** (per document, undoable, persisted): unbounded
  surface within safe numeric limits, dot-grid background, content-bounds
  pan clamping.
- Page management: add / delete / duplicate / rename / reorder pages with
  element migration; page rail with live thumbnails.
- **12 page templates** (blank, ruled, college ruled, wide ruled, grid, small
  grid, dotted, Cornell, music staff, planner, to-do, custom) — changing a
  template never touches handwriting.
- Page sizes A4/A5/Letter/Legal and portrait/landscape per page.

### Text, images, shapes
- Rich text: 6 font families, size, bold/italic/underline/strikethrough,
  text + background color, bidi-aware alignment, bulleted/numbered lists,
  rotation; Arabic & mixed-direction text verified by tests.
- Images: picker + camera, sampled decoding, LRU cache, transform set,
  crop model.
- Shapes: 9 kinds (line, arrow, double arrow, rect, rounded rect, circle,
  ellipse, triangle, polygon) with fill, opacity, angle snapping, and
  **deterministic shape recognition** (no AI).

### Persistence
- `.nanote` format **v3** with full backward compatibility (v1/v2 load with
  defaults and migrate). Autosave integration verified across page and
  canvas-mode switches.

### Performance
- Uniform-grid spatial index broad-phase; viewport culling in both canvas
  modes; zero allocations on the draw hot path.

### Tests
- 27 new unit tests (partial erase geometry, transforms, hit tests,
  smoothing, pressure curves, shape recognition incl. scribble rejection,
  page commands with undo/redo, grouping, composites, format v3 round-trip
  and v2 compatibility, Arabic/mixed bidi layout, per-tool render smoke,
  template painting, spatial index). Total suite: 68 unit tests green.

## [1.3.0] — Part 3: PDF engine, local storage, search & export

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
