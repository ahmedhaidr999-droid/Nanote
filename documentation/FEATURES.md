# Nanote — Feature Matrix

## Status legend (Part 4 scheme — exactly one status per feature)

| Status | Meaning |
|---|---|
| **Tested** | Exists **and** passed its defined verification (unit/instrumented test or documented device QA) |
| **Implemented** | The functionality exists and builds, but has no dedicated automated verification yet |
| **Partially Implemented** | Some functionality exists but limitations remain |
| **Not Implemented** | Does not exist |

A feature is never labeled Tested merely because the code compiles.

## Summary status matrix

| Area | Status | Verification |
|---|---|---|
| Library/document management (list/grid, filters, sort, batch, metadata) | **Tested** | `DocumentRepositoryImplTest` (Robolectric) |
| Nested folders (cycle-safe moves, delete-lifts-children) | **Tested** | `DocumentRepositoryImplTest` |
| Notebook management (create/edit/defaults) | **Tested** | `DocumentRepositoryImplTest` |
| Persistence: `.nanote` format v3 + migrations (v1/v2→v3) | **Tested** | `DocumentFormatTest`, `DocumentFormatV3Test`, `MergedContentFormatTest` |
| Room migrations v1→v2→v3 | **Tested** | `MigrationTest` (Robolectric, full chain) |
| Undo/redo command history incl. page ops, grouping, canvas-mode switch | **Tested** | `DocumentModelPart2Test`, `Part3ModelTest` |
| Stroke geometry: partial erase, transforms, hit tests, smoothing | **Tested** | `StrokeOpsTest` |
| Shape recognition (deterministic, scribble rejection) | **Tested** | `ShapeRecognitionTest` |
| Per-tool ink rendering (all pen engines) | **Tested** (smoke level) | `ElementRendererTest` (Robolectric) — visual quality needs device QA |
| Rich text (fonts/styles/lists/bidi AR+EN layout) | **Tested** | `DocumentFormatV3Test`, `ElementRendererTest` |
| PDF page operations (delete/dup/reorder/rotate/extract/insert/merge) | **Tested** | `PdfPageOpsTest` (Robolectric, reopen-verified) |
| Export painting of merged content (shapes incl. 4 new kinds + marks) | **Tested** | `MergedContentPaintTest` (Robolectric, reload-verified) |
| Backup/restore format (versioned, previewed, non-destructive) | **Tested** | `BackupFormatTest` |
| PDF text-selection marks (highlight/underline/strikethrough) | **Tested** (model+commands) | `Part3ModelTest`, `MergedContentFormatTest` |
| Handwriting input (stylus pressure/tilt, palm rejection, latency) | **Implemented** | design + unit-tested geometry; **physical-stylus QA pending** |
| PDF viewer (lazy rendering, zoom, bookmarks, reading positions) | **Implemented** | repository tests cover positions/bookmarks; rendering needs device QA |
| Grouped search incl. PDF text hits + recent-search chips | **Implemented** | repository query covered by title-based tests; full grouped UI not automated |
| Export sheet (PDF modes/PNG/JPEG/.nanote/page subsets/share/print) | **Implemented** | underlying painters tested; sheet UI not automated |
| Settings (appearance/input/defaults/export/backup/storage) | **Implemented** | flows wired; UI not automated |
| Infinite canvas mode | **Partially Implemented** | model/persistence/undo tested; long-session performance unverified on device |
| Arabic text export to PDF | **Partially Implemented** | WinAnsi-safe approximation only (no shaping) — known limitation |
| Scanned-PDF text search | **Not Implemented** | no OCR — deliberate (offline, no cloud), never claimed |
| Trash/undo for document deletions | **Not Implemented** | confirmations used instead |
| Multi-page drag-reorder gestures | **Not Implemented** | move up/down actions instead |

## Detailed feature checklists

## Part 1 (v1.1.0) — Foundation & document management

### Home dashboard
- [x] App branding header
- [x] Search across documents **and** folders (debounced, live results)
- [x] Quick actions: new notebook, new folder, import PDF, import .nanote
- [x] Recently-opened strip (horizontal cards)
- [x] Favorites strip
- [x] Notebooks strip with “See all”
- [x] Root folders list with counts
- [x] Recent-activity feed (created / opened / renamed / duplicated / imported / moved)
- [x] Welcome empty state with contextual copy
- [x] Context menu (long-press) on every document: open, favorite, rename, duplicate, move, delete

### Library (all documents)
- [x] Perspectives: All · Notebooks · Imported · Favorites · Recent (route-parameterized)
- [x] Grid **and** list views (toggle, remembered default from Settings)
- [x] Sorting: recently modified · recently created · alphabetical (locale-aware) · custom order
- [x] Custom order with move up / move down (contextual)
- [x] Multi-selection (long-press) with select-all, batch **move**, batch **favorite**, batch **delete**
- [x] Per-document: open, favorite, rename, duplicate, move to folder, notebook settings, details sheet, delete
- [x] Metadata sheet: type, pages, created / modified / last-opened, template, page size
- [x] Confirmation dialogs for every destructive action
- [x] Create notebook (full form) and create folder from the library
- [x] Import PDF / .nanote via the system file picker (SAF)

### Notebook management
- [x] Create notebook with title, cover colour (8), icon (8), page template
      (plain / lined / grid / dotted / Cornell), orientation (portrait / landscape),
      page size (A4 / A5 / Letter)
- [x] Edit notebook settings after creation (same form)
- [x] Rename, duplicate, move, favorite, delete
- [x] Notebook defaults persisted in Settings and pre-filled in the form

### Folder management
- [x] Nested folders (unlimited depth)
- [x] Create inside current folder
- [x] Rename, delete, move (with cycle prevention)
- [x] Delete **lifts** children to the parent — nothing is ever lost
- [x] Breadcrumb navigation + up navigation
- [x] Folder tree picker with disabled own-subtree when moving a folder
- [x] Documents inside folders with the full library management set

### Persistence
- [x] Room v2 index: documents (with presentation columns), nested folders, recent activity
- [x] **Migration v1 → v2** preserving all v1.0.0 data (unit-tested)
- [x] `.nanote` document files with atomic writes and forward migrations (kept from v1.0.0)
- [x] DataStore preferences with one-time import of v1.0 appearance keys
- [x] Everything survives process death and app restart

### Settings
- [x] Theme: light / dark / system
- [x] Material You dynamic colors (accent roles stay Nanote)
- [x] **7 accent colors**
- [x] Language: system / English / Arabic (per-app locale on Android 13+)
- [x] Library defaults: view mode, sort order
- [x] New-notebook defaults (cover, icon, template, orientation, size)
- [x] Pen & tools: pressure sensitivity, palm rejection, page prefetch
- [x] Storage usage + thumbnail cache clearing
- [x] About with offline statement and version

### Platform / UX
- [x] Tablet-first adaptive shell: navigation rail (expanded) ⇄ bottom bar (compact)
- [x] Portrait + landscape, multi-window (`resizeableActivity`), no locked orientation
- [x] Full RTL support (mirrored icons, start/end-safe layouts)
- [x] English + Arabic strings, proper Arabic plurals
- [x] Splash screen, edge-to-edge theming, empty states, snackbar feedback
- [x] 100% offline — **no INTERNET permission**; camera + haptics only

### Preserved from v1.0.0 (still working)
- [x] Canvas editor: pen/pencil/brush/highlighter/eraser, shapes, text,
      images, lasso, laser, split screen, undo/redo, autosave
- [x] PDF engine: pdfium rendering (all pages), pdfbox text selection,
      highlight/underline annotations
- [x] Export: flatten to PDF or export the `.nanote` project
- [x] Notewise-style customizable toolbar
- [x] “Open PDF” intent from file managers (now imports + opens via the new shell)

## Part 3 (v1.3.0) — PDF engine, local storage, search & export

### PDF engine (five separated responsibilities)
- [x] Rendering: pdfium-android (lazy, cached, gesture-safe)
- [x] Text extraction: pdfbox-android (per page, word boxes for selection)
- [x] Annotations: Nanote vector model painted over rendered pages
- [x] Page operations: pdfbox (delete / duplicate / reorder / rotate / extract / insert / merge)
- [x] Exporting: pdfbox writing + pdfium verification — every exported PDF is reopened with an independent renderer before being reported as successful
- [x] `original.pdf` (imported source) is **never modified**; page operations write an `edited.pdf` working copy

### PDF import & viewer
- [x] SAF import with no broad storage permission; file size + source name recorded
- [x] Page count, metadata sheet (title / author / creator / producer when present)
- [x] Thumbnails, page-by-page navigation, jump-to-page, zoom / fit width / fit page
- [x] Bookmarks: toggle on the current page, list in the pages panel, jump & delete
- [x] Reading position (page + zoom) persisted per document and restored on open

### PDF annotation & text selection
- [x] Handwriting (pen / pencil / brush / highlighter with pressure), eraser, lasso, shapes, images, text boxes — integrated from the Part 2-era engine
- [x] Long-press word selection with drag handles on text-based pages
- [x] Copy / highlight / underline / **strikethrough** (new) from the selection menu
- [x] Scanned pages and handwritten ink are distinguished from selectable text (no OCR claimed)

### Page management
- [x] Delete / duplicate / rotate / move pages (with confirmations, working copy only)
- [x] Insert all pages of another picked PDF at the current position
- [x] Extract selected pages into a new document (split)
- [x] Text search index rebuilt automatically after page operations

### Export & sharing
- [x] Export sheet: PDF (flattened / markup annotations / original), PNG, JPEG, .nanote
- [x] Page-subset export for PDFs
- [x] Android Sharesheet (FileProvider) and Android Print Framework
- [x] Notebook pages render offscreen (template + ink + images + text) for raster export
- [x] Exports are never screenshots — real content streams via pdfbox

### Local storage, autosave, backup
- [x] Room v3: tags, PDF text index, bookmarks, reading positions, search history (migration v2→v3 unit-tested)
- [x] Autosave: enable/disable + adjustable debounce in Settings; save-status chip in the editor; final save always runs on exit
- [x] Document tagging with usage counts; tags searchable
- [x] Versioned offline `.nanote-backup` ZIP format (manifest + index rows + files)
- [x] Full backup to any SAF location; restore with preview dialog; duplicate names resolve to "(Restored)"; nothing existing is overwritten
- [x] Android cloud auto-backup disabled (`allowBackup=false` + exclusion rules) — documents never leave the device except through explicit user export

### Search
- [x] Grouped results: documents, folders, tags and **inside PDF text** (page + snippet)
- [x] Tapping a PDF text hit opens the document
- [x] Recent searches as suggestion chips; per-entry removal and clear-all

### Settings (new sections)
- [x] Default export format + default PDF annotations mode
- [x] PDF render quality (low / balanced / high), autosave preferences
- [x] Backup & restore panel with progress + result reporting
- [x] Storage usage + thumbnail cache cleanup (unchanged from Part 1)

### Tests added in Part 3
- [x] `BackupFormatTest` — round-trip, future-version rejection, partial archives (JVM)
- [x] `PdfPageOpsTest` — delete/duplicate/reorder/rotate/extract/insert/merge verified by reopening results (Robolectric)
- [x] `Part3ModelTest` — strikethrough serialization round-trip + command semantics
- [x] `MigrationTest` v2→v3 — data preservation + all new tables
- [x] `DocumentRepositoryImplTest` — tags, search history, bookmarks, reading positions


## Honest scope & known limitations

- Arabic text boxes export as WinAnsi-safe approximations (PDFBox Type1
  fonts cannot shape Arabic — needs an embedded font + layout engine)
- Scanned PDFs are not text-searchable and no OCR is performed or claimed
- Markup annotation opacity is approximated by colour blending
- Per-notebook backup selection exists in the engine; the UI ships full backup
- End-to-end restore into an independent install is automated-test covered,
  not device-verified in this environment (Part 4 QA)
- Drag-and-drop page reorder uses move up/down actions instead of gestures
- Phone-specific layouts beyond the compact bottom bar
- Trash/undo for deletions (confirmations instead)

See `ROADMAP.md` for the full plan.

## Part 2 (v1.2.0) — Handwriting engine, canvas & notebook editor

### Handwriting engine (custom vector canvas)
- [x] Five distinct pen engines: **ballpoint, fountain (pressure × velocity),
      brush (strong taper), pencil (seeded graphite grain), highlighter**
      (alpha capped so text stays readable)
- [x] Per-tool configuration: color, thickness, opacity, **pressure response**,
      **smoothing**
- [x] Pressure response curve: linear / soft / firm (global, in Settings)
- [x] Pressure response baked into each stroke — later setting changes never
      alter already-written ink
- [x] Full input capture: stylus detection, pressure, tilt (where reported),
      per-point timestamps, **high-frequency historical event replay**
- [x] Laser pointer with fading trails

### Pen customization (§3)
- [x] HSV color picker with custom colors (SV square + hue slider + hex)
- [x] Quick palette per tool, **recent colors** (10), **favorite colors** (14)
- [x] Named pen presets per tool (apply / save / long-press delete)
- [x] Live preview: every slider applies instantly to the active tool

### Palm rejection & finger behavior (§5)
- [x] Stylus draws; fingers navigate by default (pan / pinch zoom / fling)
- [x] Hard palm guard: finger input ignored while a stylus was recently active
- [x] Configurable finger behavior: **scroll / draw / ignore**
- [x] Hardware eraser tip and barrel button = temporary eraser
- [x] Graceful on hardware without tilt/pressure (defaults applied)

### Eraser (§6)
- [x] Stroke eraser, object eraser (strokes, texts, images, shapes,
      annotations), **partial eraser**
- [x] Partial erase is fully vector: capsule intersection points are
      interpolated and remaining geometry (coords, pressure, tilt, timestamps)
      is preserved bit-exactly — no rasterization, ever
- [x] Adjustable eraser size (8–160) with on-canvas cursor

### Editing (§8)
- [x] Undo / redo (200 steps, command pattern) over every operation below
- [x] Selection: lasso, rectangle, single-object tap, multi-object
- [x] Move, **resize (4 corner handles)**, **rotate (dedicated handle)** —
      live ghost preview during gestures, strokes included
- [x] Copy / cut / paste (process-wide vector clipboard), duplicate, delete
- [x] **Group / ungroup** — grouped elements select and transform together

### Canvas engine (§9)
- [x] Fixed-page mode: continuous vertical pages, boundaries, shadows,
      per-page templates, fit-width / fit-page / 100%, double-tap zoom, fling
- [x] **Infinite canvas mode**: unbounded logical surface within safe numeric
      limits (±150k pts), sparse dot grid, content-bounds pan clamping
- [x] Focal zooming (no content drift), zoom 50%–1000%
- [x] Per-document canvas mode persisted in the `.nanote` file (undoable)

### Page management (§10)
- [x] Add / delete / duplicate / **rename** / **reorder** pages (all undoable,
      elements migrate with their pages)
- [x] Page rail with **live thumbnails** (renders template + content)
- [x] Page navigation: tap thumbnail, slider, prev/next buttons
- [x] **12 page templates**: blank, ruled, college ruled, wide ruled, grid,
      small grid, dotted, Cornell, music staff, planner, to-do, custom
- [x] Changing a page template **never touches existing handwriting**
- [x] Page size (A4 / A5 / Letter / Legal) and portrait / landscape per page

### Selection engine (§11)
- [x] Freehand lasso with **geometric intersection** (any stroke segment
      crossing the polygon selects — not bbox-only)
- [x] Rectangle selection with Liang-Barsky segment-rect intersection
- [x] Selection bounds, 4 resize handles, rotate handle, contextual toolbar
- [x] Tap hit-testing with per-stroke width tolerance

### Text engine (§12)
- [x] Editable text boxes: insert (tap) / edit (tap existing) / delete
- [x] Font family (6), size, **bold / italic / underline / strikethrough**
- [x] Text color, background color, alignment (bidi-aware start/center/end)
- [x] **Bulleted and numbered lists**
- [x] Move, resize (via selection), rotate
- [x] Arabic, English and mixed-direction text (bidi StaticLayout, tested)

### Image engine (§13)
- [x] Insert PNG / JPEG / WebP via the system file picker (SAF) + camera
- [x] Memory-efficient decoding (sampling) + LRU bitmap cache
- [x] Move, resize (aspect follows per-axis factors), rotate, duplicate,
      copy/paste (same document), delete, selection
- [x] Crop support in the data model (`crop` rect) with region decoding

### Shape engine (§14)
- [x] **9 shapes**: line, arrow, double arrow, rectangle, rounded rectangle,
      circle, ellipse, triangle, polygon
- [x] Stroke color, **fill color + filled toggle**, thickness, opacity
- [x] Snap to geometry (0/45/90° axes for lines/arrows)
- [x] **Deterministic shape recognition** (no AI): straightness, radial
      variance, turn-angle corner detection — draws convert to shapes
      (toggleable; recognized polygons keep their vertices)
- [x] Shapes export to PDF with all new kinds

### Toolbar (§15)
- [x] Customizable: reorder, hide/show, reset, bottom/left/right position
- [x] Fountain pen added to the default toolbar

### Persistence (§17)
- [x] Document format **v3**: tilt + timestamps per point, stroke
      timestamp/groupId/pressureResponse, rich text fields, shape fill/
      rotation/vertices, per-page templates, canvas mode — all
      **backward compatible** (v1/v2 files load with defaults + migrate)
- [x] Vector strokes remain the primary representation — never full-page
      bitmaps; atomic writes; salvage recovery
- [x] Autosave (2 s debounce + 30 s periodic + final save on exit) verified
- [x] Changes survive page switches, canvas-mode switches, process death

### Performance (§16)
- [x] Uniform-grid **spatial index** (broad-phase for erase/lasso/culling)
- [x] Viewport culling by bounding boxes in both canvas modes
- [x] Zero allocations on the draw hot path; path/layout/bitmap caches
- [x] Historical input replay at full hardware rate without frame drops

## Part 4 (v1.4.0) — Final integration, QA & release

### Integration (the headline work)
- [x] Part 2 branch merged with Part 3's main: 20 overlapping files,
      14 content conflicts resolved semantically — models, eraser,
      repository, export painter, menus, settings, en/ar strings
- [x] `DocumentContent` = canvasMode (Part 2) + strikeThroughs (Part 3)
      in one document — round-trip **Tested** (`MergedContentFormatTest`)
- [x] Eraser handles shapes + strikethroughs in one undoable batch
- [x] Export painter draws all 9 shape kinds — **Tested**
      (`MergedContentPaintTest`, reopen-verified)
- [x] Room schema v3 unchanged (identical MIGRATION_1_2 on both lines);
      exported schemas 2.json/3.json committed
- [x] All navigation paths verified (home/library/folders/settings/editor
      + ACTION_VIEW PDF import)

### Security & privacy (audit results)
- [x] No INTERNET permission (verified in manifest)
- [x] No cloud SDKs, no AI, no accounts, no analytics
- [x] `allowBackup=false` + full data-exclusion rules (cloud + transfer)
- [x] Committed release keystore + passwords REMOVED from tree;
      gitignored; env-var/secret-based signing (see RELEASE.md — the old
      key remains in git history and must never sign a public release)
- [x] No secrets in CI; keystore optionally restored from GitHub secrets
- [x] No document contents in logs (ids and error messages only)

### Release preparation
- [x] Version 1.4.0 / versionCode 5; app icon, label, package stable
- [x] R8 + resource shrinking enabled; debug/release variants
      (.debug suffix)
- [x] APK + AAB build **verified locally**; CI builds both as artifacts
- [x] Secure signing instructions (RELEASE.md)

### Verified build & test results
- [x] assembleDebug / assembleDebugAndroidTest / assembleRelease /
      bundleRelease: BUILD SUCCESSFUL (JDK 17, SDK 35)
- [x] 98 unit tests, 0 failures (14 classes) — see TESTING.md
- [x] apksigner verify on the release APK (debug-signed fallback)

## Known defects & limitations (honest list)

1. **Arabic text exported to PDF** is WinAnsi-safe approximated (PDFBox
   Type1 fonts cannot shape Arabic) — proper export needs an embedded
   Arabic font + layout engine.
2. **No physical-device QA** has been performed in this environment:
   stylus pressure accuracy, palm rejection against real palms, input
   latency and long-writing-session behavior are unverified. No
   handwriting-performance claims are made.
3. **pdfium cannot load under JVM Robolectric** — export unit tests
   verify through the pdfbox reload path; the on-device pipeline
   re-verifies every export with pdfium.
4. Scanned/image-only PDFs have no searchable text — no OCR (deliberate,
   offline-only; never claimed).
5. Trash/undo for document deletions is absent (confirmation dialogs
   instead); deleted documents are unrecoverable after confirmation.
6. Page reordering uses move up/down actions, not drag gestures.
7. Markup-annotation export opacity is approximated by colour blending.
8. The exposed pre-Part-4 signing key (in git history) must be replaced
   before any public release — see RELEASE.md security notice.
9. Restore-into-independent-install is automated-test covered but not
   device-verified in this environment.
10. `versionCode` gaps (3 → 5) reflect the abandoned 1.2.0 numbering
    collision when Parts 2 and 3 were developed in parallel.
