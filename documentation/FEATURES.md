# Nanote — Feature Matrix

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

## What Part 1 does NOT include (honest scope)

- Handwriting engine **rework** — Part 2 (current engine preserved as-is)
- PDF features beyond v1.0.0 — Part 4
- Drag-and-drop reorder gestures (custom order is via move up/down actions)
- Phone-specific layouts beyond the compact bottom bar
- Trash/undo for deletions (confirmations instead)

See `ROADMAP.md` for the full plan.

## Part 2 (this release, v1.2.0) — Handwriting engine, canvas & notebook editor

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
