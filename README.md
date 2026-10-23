# Nanote

**Nanote** is a fast, local-first handwritten-notes and PDF-annotation app for
Android phones, tablets, foldables and Chromebooks. Low-latency writing with a
stylus or finger, notebooks and folders, paper templates, shapes, lasso,
images and text on a spacious canvas, PDF import and annotation, high-quality
PDF/PNG export, favorites, quick switching between recent notes, links, and a
laser pointer for presenting.

**Offline forever.** No cloud, no accounts, no sync, no analytics, no AI.
The merged manifest requests **zero permissions** — enforced at build time by
Gradle tasks (`checkManifestNoInternet`, `scanApkNoInternet{Debug,Release}` scan
the packaged APK bytes) and by a Robolectric test over the merged manifest.
Backup/Restore is a ZIP file through the Storage Access Framework, and Android
Auto-Backup is fully disabled.

---

## Build & run

Requirements: **JDK 17+**, **Android SDK** (platform 35, build-tools 35.0.0).

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # R8-minified release (unsigned)
./gradlew testDebugUnitTest      # 83 unit tests (pure JVM + Robolectric)
./gradlew lintDebug              # lint
adb install app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` must point to the SDK (`sdk.dir=...`) — Android Studio
creates it automatically.

Signing for release: add a `signingConfigs.release` block (keystore path,
passwords) in `app/build.gradle.kts`.

## Renaming the app

All branding is centralized in three places:

| What | Where |
|---|---|
| App label (all languages) | `app/src/main/res/values/strings.xml` → `app_name` |
| Application id / package | `app/build.gradle.kts` → `defaultConfig.applicationId`, `namespace` |
| In-code brand constants | `app/src/main/java/com/nanote/core/Brand.kt` |
| Launcher mark | `app/src/main/res/drawable/ic_launcher_foreground.xml` |

After renaming the application id, move the packages under
`app/src/main/java/com/nanote` accordingly (or keep the package — the id and
the namespace are what users see).

## Architecture

MVVM with unidirectional data flow, one ViewModel + one immutable UiState per
screen. Coroutines + Flow everywhere, Hilt for DI, Room for metadata, plain
files for ink.

```
app/src/main/java/com/nanote/
  NanoteApp.kt  MainActivity.kt        # single activity, trash purge at start
  core/Brand.kt                        # single place to rebrand
  domain/model/                        # Notebook, Folder, Page, PageObject (serializable)
  data/
    db/                                # Room: entities, DAOs, FTS4 (unicode61) over text boxes
    files/                             # InkFormat blob store, atomic writes + .bak fallback,
                                       # SettingsStore (DataStore), NotebookArchive (.nanote),
                                       # LibraryArchive (full backup)
    repo/NotebookRepository.kt          # single source of truth; CRUD, trash, tags, FTS, backup
  ink/
    engine/                            # PURE Kotlin (unit-testable on the JVM, zero android.*):
      Mat3/Bounds, Stroke (STRIDE=4 pts), pens/specs/palettes/papers,
      OneEuro smoothing, width LUT, outline points, RDP, SpatialGrid,
      erasers (whole-stroke + pixel split), lasso selection, shape snap
      (geometry only), History (commands), PageDoc, ViewTransform, InputRouter,
      InkFormat (deflated binary), shape builders, ToolSpec
    render/                            # android.graphics layer: StrokePainter, PaperRenderer,
                                       # InkTileCache (512px async tiles, zoom buckets, dirty-region
                                       # revision), InkCanvasView (custom View), PdfPageProvider,
                                       # ObjectPainter, ThumbRenderer
    input/GestureController.kt         # zero-alloc pinch/pan math
  export/PageExporter.kt               # vector-ink PDF + PNG/JPEG at 72/150/300 dpi
  ui/                                   # Compose M3: library, editor, settings, components
```

**Ink pipeline.** Page space is PDF points (A4 = 595×842); points are packed
`x, y, pressure, tMs` (STRIDE 4). Touch events go through unbuffered dispatch
with historical samples first, are routed by the pure `InputRouter`
(stylus/finger/palm/mouse rules incl. barrel-button erase and hover
rejection), smoothed by a 1-euro filter, and rendered as filled outlines with
midpoint-quad smoothing plus round caps. Committed ink is cached in 512-px
tiles (async render thread, re-rendered per 1.41× zoom step, invalidated by
dirty region + revision); the live stroke draws on top with a frozen-prefix +
short-tail path scheme. Nothing allocates in `onDraw` or the touch loop.

**Storage.** Room holds metadata only; strokes live in
`filesDir/notebooks/<nb>/pages/<page>.ink` (deflated binary), objects in a
JSON sidecar, assets in `assets/`, thumbnails in `filesDir/thumbs/<page>.webp`
(regenerated after each save). Every write is atomic (`tmp → fsync → .bak →
rename`); loads fall back to the `.bak` when the main file is corrupt.

## Testing

83 tests, no emulator needed:

- Pure JVM: InkFormat round-trip, RDP, point-in-polygon, both erasers, History
  semantics, table-driven shape recognition, ViewTransform round-trip,
  InputRouter palm-rejection table, PageDoc dirty tracking, OneEuro, width
  LUT + outline geometry, atomic PageStore save/load with .bak fallback.
- Robolectric: merged manifest requests **no permissions** + allowBackup=false;
  the spec's key flow *create note → draw → undo → restart app → identical ink*
  at repository level; trash/purge; FTS in English **and Arabic**; `.nanote`
  archive round-trip; whole-library backup round-trip.
- Compose UI (Robolectric-backed): the tool palette renders every tool with
  TalkBack descriptions and tap-selects tools.

## Localization

English + Arabic with full RTL mirroring (the page canvas itself is never
mirrored). Per-app language switching lives in Settings
(`AppCompatDelegate.setApplicationLocales`, auto-persisted).

## Assumptions

Recorded during implementation, per the spec's instructions:

1. App renamed **InkNote → Nanote**, package `com.nanote` (user instruction);
   `.inknote` file extension became `.nanote`.
2. The engine is 100% pure Kotlin (own `Mat3`/`Bounds` instead of
   `android.graphics.Matrix/RectF`) so it runs in plain JVM tests; the render
   layer converts values 1:1 (same row-major layout).
5. Shape-snap commits the ideal shape as a **single** AddStrokes command
   (undo removes the whole shape; the spec's "replace raw with ideal" behavior
   is preserved visually without a double history entry).
3. Room FTS uses standalone FTS4 + unicode61; rowids are assigned 1-based
   (rowid 0 does not index) and multi-word search ORs the per-term matches.
4. PDF pages render per-tile through `PdfRenderer` with a transform+clip (the
   renderer is thread-confined to the tile thread; imports open their own
   instance).
5. `.nanote` import on id collision always keeps both (fresh id); library
   restore lets the user choose keep-both or skip.
6. Deferred stretch items (spec §3 X / §10 M7): audio-synced strokes, the
   zoom-writing window and the ruler. Also deferred: `androidx.ink`
   (alpha at spec time), motion prediction library (input is already
   unbuffered), baseline profiles (need a device run), and a full
   drag-reorder gesture for the tool palette (long-press left/right reorder
   ships instead).
7. The stylus-barrel "lasso" setting and palm-sensitivity range are stored but
   the barrel currently always erases (single action) — the setting UI keeps
   the future toggle surface.
8. Text objects render with StaticLayout (RTL-safe); repositioning is by
   drag-anywhere-over-text; no rich formatting in v1.

## Privacy checklist (self-review)

- [x] No `INTERNET` (nor any permission) in the merged manifest — Gradle task
      scans packaged APK bytes in UTF-8/UTF-16; unit test asserts it too.
- [x] No accounts, no analytics, no Firebase/Play Services, no crash reporters.
- [x] `allowBackup=false` + full cloud-backup and D2D transfer exclusions.
- [x] Storage access only through SAF + the system photo picker.
- [x] Optional app lock (BiometricPrompt + device credential) with FLAG_SECURE.
- [x] Trash with restore + 30-day auto-purge; destructive actions confirm.
- [x] No leftover TODO/pseudo-code; every string localized EN/AR.
