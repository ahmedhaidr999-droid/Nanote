# Nanote — Handwriting Engine (Part 2, integrated in Part 4)

The handwriting engine is a **custom vector canvas** built directly on
Android's `View` + `Canvas` APIs — no third-party ink SDK. Everything a
stroke is stays vector data end-to-end: capture, editing, selection,
transforms, clipboard, persistence and export. Pages are never rasterized
into the document representation.

## Five-layer separation

| Layer | Module | Responsibility |
|---|---|---|
| Capture | `core/graphics/CanvasView.kt` | MotionEvent stream (stylus pressure/tilt/timestamps, historical replay), palm rejection, gesture routing |
| Input logic | `core/graphics/ToolController.kt` | Tool state machines: pens, erasers, lasso, shapes, text triggers, clipboard, grouping |
| Document | `core/model/DocumentModel.kt` | Command-pattern mutations with undo/redo (200 steps) + revision StateFlow |
| Geometry | `core/model/StrokeOps.kt`, `ShapeRecognition.kt` | Pure-Kotlin, JVM-testable stroke mathematics |
| Rendering | `core/graphics/ElementRenderer.kt`, `PageBackgrounds.kt` | Per-tool ink engines, text layout, shape paths, 12 templates, caches |

## Pen engines

Five distinct engines, configured per tool (color, thickness, opacity,
pressure response, smoothing):

- **Ballpoint** — constant width with subtle speed variation
- **Fountain** — width driven by pressure × velocity
- **Brush** — strong pressure taper
- **Pencil** — seeded graphite grain (deterministic per stroke)
- **Highlighter** — wide, flat, alpha-capped so underlying text stays readable

The global **pressure response curve** (linear / soft / firm) is applied at
capture time and **baked into the stroke's point pressures** — changing the
setting later never re-renders already-written ink.

## Input capture

- Stylus detection (`MotionEvent.TOOL_TYPE_STYLUS`), pressure and tilt
  (where the hardware reports it), per-point timestamps
- **Historical event replay**: batched high-frequency samples between
  frames are replayed individually, not collapsed
- Exponential smoothing during capture + a commit-time pass
- **Palm rejection**: configurable finger behavior (scroll / draw / ignore),
  a hard palm guard while a stylus is active, hardware eraser tip and
  barrel button mapped to the eraser

## Eraser — without rasterizing

- **Stroke erase** — removes whole strokes
- **Object erase** — removes any element (text, image, shape, annotation)
- **Partial erase** — splits strokes at capsule-boundary intersections:
  the remaining geometry (coordinates, pressure, tilt, timestamps) is
  preserved bit-exactly. `StrokeOps.splitStrokeAgainstCapsule` interpolates
  the intersection points so cut ends keep their pressure values.

Part 4 integration note: the eraser's undo batching now commits **shapes**
(Part 2 element) and **strikethrough annotations** (Part 3 marks) in the
same single undoable step.

## Selection & transforms

- **Lasso** (freeform polygon) and **rectangle** selection using real
  geometry — segment–polygon intersection and Liang–Barsky clipping, not
  bounding-box approximations
- Single-object tap selection, group semantics (group / ungroup / duplicate)
- **Move, resize (4 corner handles), rotate (dedicated handle)** with live
  ghost preview; strokes scale (including width) and rotate as vectors
- Copy / cut / paste through a process-wide **vector clipboard**
  (`EditorClipboard`) — works across documents and split-screen panes

## Canvas modes

- **PAGED** — fixed-size pages (A4/A5/Letter/Legal, portrait/landscape per
  page) with the 12 templates below
- **INFINITE** — unbounded surface (per document, undoable, persisted);
  dot-grid background, pan clamped to content bounds, safe numeric limits

Switching is a command (undoable) via the editor's More menu; the mode
round-trips through the `.nanote` v3 format.

## Page templates (12)

Blank, ruled, college ruled, wide ruled, grid, small grid, dotted, Cornell,
music staff, planner, to-do, custom color — drawn by `PageBackgrounds`
under the ink layer. Changing a template **never touches handwriting**
(backgrounds are painted, not part of stroke data).

## Text, images, shapes

- **Rich text**: 6 font families, size, bold/italic/underline/strikethrough,
  text + background color, bidi-aware alignment (start/center/end),
  bulleted/numbered lists, rotation — Arabic and mixed-direction text laid
  out through Android's bidi-aware `StaticLayout`
- **Images**: SAF picker + camera capture, sampled decoding, LRU bitmap
  cache, full transform set
- **Shapes**: 9 kinds (line, arrow, double arrow, rect, rounded rect,
  circle, ellipse, triangle, polygon) with fill, opacity and angle snapping,
  plus **deterministic shape recognition** from handwriting (no AI —
  geometric heuristics with scribble rejection)

Part 4 integration note: the **export painter** (`core/pdf/PdfContentPainter`)
now draws all nine shape kinds (the four kinds added by Part 2 were ported
into the Part 3 export architecture during the merge and are covered by
`MergedContentPaintTest`).

## Performance design

- Uniform-grid **spatial index** broad-phase for hit testing and culling
- Viewport culling in both canvas modes
- Zero allocations on the draw hot path (cached `Paint`/`Path` objects)
- Page-rail thumbnails rendered off the UI thread (`ThumbnailRenderer`)

## Persistence

Strokes are serialized in the versioned `.nanote` JSON (v3) — see
`STORAGE_AND_BACKUP.md`. Autosave is debounce-based (configurable) with a
guaranteed final save on editor exit; the editor shows a save-status chip
(Saving… / Saved / Error).

## Verification status

- **Unit-tested (JVM)**: partial-erase geometry, transforms, hit tests,
  smoothing, pressure curves, shape recognition (incl. scribble rejection),
  page commands with undo/redo, grouping, composites, format v3 round-trip
  and v2 compatibility, Arabic/mixed bidi layout, per-tool render smoke
  tests (`DocumentModelPart2Test`, `StrokeOpsTest`, `ShapeRecognitionTest`,
  `ElementRendererTest` under Robolectric)
- **Build-verified**: the merged engine compiles and the full suite passes
  (98 unit tests green in the Part 4 integration run)
- **Physical-device QA** (pressure curves on real styli, palm rejection
  against real palms, long-session latency): **not performed in this
  environment** — see `TESTING.md` for the device test plan. No perfect
  handwriting performance is claimed without it.
