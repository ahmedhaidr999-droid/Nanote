# Changelog

All notable changes to Nanote are documented here.

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
