# Nanote — Notes + PDF Annotation for Android

<p align="center"><b>v1.0.0</b> · Kotlin · Jetpack Compose (Material 3) · MVVM + Hilt · Room</p>

Nanote is a premium note-taking app with an infinite-canvas editor and a
high-performance PDF reader/annotator, inspired by the workflows of Notewise and
GoodNotes with an independent **Premium Blue** visual identity.

* **Language:** Kotlin 2.0 (single module, `com.nanote.app`)
* **UI:** Jetpack Compose + Material 3 + a custom high-performance `View` canvas
* **DI / data:** Hilt 2.52, Room 2.6 (metadata index), DataStore-free settings (SharedPreferences + kotlinx-serialization)
* **minSdk 26 · targetSdk 35 · full Arabic (RTL) + English · light/dark + Material You**

---

## Feature Map

| Area | What's inside |
|---|---|
| PDF engine | pdfium-android (all pages guaranteed, serial native thread, retry-once, anti-gray-page rules) |
| Text layer | PdfBox-Android lazy word extraction with top-left-origin boxes |
| Text selection | Long-press with finger, drag handles, context menu: Copy / Translate / Search web / Highlight / Underline / Select text / Select all / **Ask AI** |
| Ink tools | Pen (pressure, variable-width smoothed outlines), Pencil, Brush, Highlighter (alpha-capped, instant settings), Eraser (pixel-split / stroke / object) |
| Shapes | Line, Arrow, Rect, Circle, Triangle |
| Insert | Text boxes, Sticky notes, Images (photo picker + camera), PDF import |
| Selection | Lasso (freehand), Object select (tap, move, scale, duplicate, delete), Laser pointer (fading trail, not persisted) |
| Canvas | Infinite world coordinates, 0.5x–10x pinch zoom, double-tap zoom, hand tool, palm rejection (stylus draws / finger navigates) |
| Workspace | Split screen with draggable divider, two fully independent documents (own zoom/tools/undo) |
| History | Command-pattern Undo/Redo, 200 steps, per document |
| Persistence | Atomic `.nanote` JSON format, versioned with forward migrations + partial recovery, autosave (2 s debounce + 30 s periodic + on-exit) |
| Export | Flatten annotations to a new PDF, or export the project file |
| Toolbar | Notewise-style floating pill, live settings popovers, drag-reorder customization, position (bottom/left/right), presets, reset-to-default |
| Theming | Premium Blue design tokens (#2563EB family), complete dark theme, dynamic color option |

---

## Why pdfium-android (PDF engine decision)

The system `android.graphics.pdf.PdfRenderer` only ever rendered the first page
in earlier prototypes (remaining pages stayed gray) and has no text-layer API.
Nanote therefore uses:

* **`com.github.barteksc:pdfium-android:1.9.0`** — raster engine. Every page is
  explicitly `openPage(index)`-ed before rendering; all native calls are funneled
  through one serial thread + mutex (pdfium is not thread-safe); failures are
  logged, retried exactly once, and surfaced in-page with a retry chip.
* **`com.tom-roush:pdfbox-android:2.0.27.0`** — text layer. Words are extracted
  lazily per page with top-left-origin bounding boxes in PDF-point space, the
  exact space pdfium renders into, so selection rects map 1:1 onto the canvas.

### Performance architecture (why it doesn't lag)

1. **Zoom gestures never re-render.** Pinch/double-tap only mutate the view
   matrix and redraw cached bitmaps. After the gesture settles (~160 ms) pages
   re-render **only if** their discrete resolution bucket changed.
2. **Discrete resolution buckets.** Render scale is quantized to half-octave
   steps (`2^(bucket/2)`), so small zoom changes reuse the cached bitmap.
3. **Adaptive LRU cache.** `docId:page:bucket` keys, budget = clamp(heap/8,
   24 MB, 64 MB), RGB_565 pages, per-document eviction (no cache crossing when
   alternating files).
4. **Fast first paint.** Opening a document reads page sizes only; page 1 is
   requested immediately at high priority, neighbors prefetched, far jobs
   cancelled on fast scroll.
5. **Zero-allocation draw loop.** Preallocated paints, per-stroke path caches
   (WeakHashMap → replaced elements invalidate themselves), bbox culling, live
   stroke built into one reused `Path`.
6. **No ANRs.** Every pdfium/pdfbox/file operation runs on background
   dispatchers; the single native thread is named `nanote-pdfium`.
7. **Vertical continuous layout.** Pages stack in one vertical list (fit-width,
   centered) — page turns can never shift content left/right.

---

## Building

Requirements: **JDK 17**, Android SDK (platform 35 + build-tools).

```bash
# 1. Clone
git clone https://github.com/ahmedhaidr999-droid/Nanote.git && cd Nanote

# 2. (Optional) point to your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 3. Build a signed release APK (keystore included for testing)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The signing keystore lives in `keystore/` (test key, password `nanote2024`,
alias `nanote`) — replace it with your own for production.

To open in Android Studio: **File → Open** on the project root.

---

## Testing guide

* **Multi-page PDF:** import PDFs of 1 / 2 / 5 / 20 / 100 pages — every page
  renders (never gray), mixed portrait/landscape pages keep their own sizes.
* **Zoom & pan:** pinch quickly in/out — content follows instantly (cached
  bitmap + matrix); sharpness catches up after the gesture settles.
* **Page flipping:** vertical scroll is continuous; use the top bar arrows or
  More → page slider; no horizontal jump between pages.
* **Text selection:** long-press a word with your finger (no pen needed),
  drag the round handles, then Copy / Translate / Search / Highlight /
  Underline / Select all / Ask AI. Long-press on empty space or an image-only
  page shows nothing (silent cancel by design).
* **Highlighter:** open its popover and move the opacity slider — the change
  applies to new strokes immediately on the current page. At 100 % the ink is
  capped at ~65 % visual alpha, so text stays readable. Old strokes keep the
  settings they were drawn with; undo/redo, page changes and file reopen all
  behave correctly.
* **Split screen:** toolbar `More` → Split — drag the divider, each side has
  its own document/zoom/undo; rotate the device — both panes survive.
* **Autosave:** scribble and kill the app from recents — reopen, content is
  there (atomic writes + final save on exit).
* **Performance:** open a note with 1000+ strokes and scribble — input stays
  responsive (culling + cached paths). Watch `adb logcat -s Nanote.*` for
  render timings (`Nanote.perf.render`) and cache hit ratios.

---

## Diagnostics

All subsystems log through `com.nanote.app.util.AppLog` with the `Nanote.`
tag prefix (e.g. `Nanote.perf.render`, `Nanote.PdfEngine`, `Nanote.AutosaveManager`).

## Project layout

```
app/src/main/java/com/nanote/app/
├── core/
│   ├── model/      # serializable elements, ToolConfig, DocumentModel + commands
│   ├── pdf/        # pdfium handle, buckets/LRU cache, render manager, text layer
│   ├── canvas/     # CanvasView, CanvasState, ToolController, renderers, selection
│   ├── data/       # Room, .nanote format + migrations, repository, autosave
│   └── ai/         # OpenAI-compatible client for Ask AI
├── ui/
│   ├── theme/      # Premium Blue design system (light/dark/dynamic)
│   ├── home/       # grid library, search, folders, favorites, import/export
│   ├── editor/     # EditorScreen, PaneController, split workspace
│   │   ├── toolbar/  # Notewise-style toolbar + popovers + customization
│   │   └── menus/    # text context menu, zoom, more, AI sheet
│   ├── settings/   # appearance, pen, AI provider, storage, about
│   └── common/     # shared components
└── di/             # Hilt modules
```

## License

Provided as-is for evaluation and testing.
