# Nanote — Testing

## Suites

| Suite | Module | Runner | Scope |
|---|---|---|---|
| `DocumentSorterTest` | `:core:model` | JVM | locale-aware sorting |
| `DocumentModelPart2Test` | `:core:model` | JVM | command pattern, undo/redo, pages, grouping, canvas mode |
| `StrokeOpsTest` | `:core:model` | JVM | partial erase, transforms, hit tests, smoothing |
| `ShapeRecognitionTest` | `:core:model` | JVM | deterministic recognition incl. scribble rejection |
| `Part3ModelTest` | `:core:model` | JVM | strikethrough model round-trips + command semantics |
| `DocumentFormatTest` | `:core:storage` | JVM | `.nanote` round-trip, truncated-file recovery |
| `DocumentFormatV3Test` | `:core:storage` | JVM | v3 fields round-trip, v2 backward compatibility |
| `MergedContentFormatTest` | `:core:storage` | JVM | **Part 4**: Part 2 + Part 3 content in one file; legacy loads |
| `BackupFormatTest` | `:core:storage` | JVM | backup round-trip, future-version rejection |
| `MigrationTest` | `:core:database` | Robolectric | full v1→v2→v3 migration chain, data preservation |
| `DocumentRepositoryImplTest` | `:core:data` | Robolectric | folders/nesting/cycles, filters, batches, tags, bookmarks, search history |
| `ElementRendererTest` | `:core:graphics` | Robolectric | per-tool render smoke tests |
| `PdfPageOpsTest` | `:core:pdf` | Robolectric | delete/duplicate/reorder/rotate/extract/insert/merge verified by reopening results |
| `MergedContentPaintTest` | `:core:pdf` | Robolectric | **Part 4**: merged content paints + reopens; polygon paths |
| `AppLaunchTest`, `EditorFlowTest` | `:app` | Instrumented | launch/branding + editor flow (compile-verified; run on device) |

## Running

```bash
# Full unit suite (what CI runs)
./gradlew testDebugUnitTest :core:model:test :core:storage:test --continue

# Compile instrumented tests (execution requires a device/emulator)
./gradlew :app:assembleDebugAndroidTest

# On a connected device
./gradlew :app:connectedDebugAndroidTest
```

## Verified results (Part 4 integration, 2026-11-15)

Environment: JDK 17 (Temurin 17.0.21), Android SDK 35, Gradle 8.9, AGP
8.5.2, Kotlin 2.0.21, 2-core sandbox.

| Check | Result |
|---|---|
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL** — `app-debug.apk` (74.8 MB) |
| `./gradlew :app:assembleDebugAndroidTest` | **BUILD SUCCESSFUL** |
| `./gradlew assembleRelease` | **BUILD SUCCESSFUL** — `app-release.apk` (54.3 MB, R8, debug-signed fallback) |
| `./gradlew bundleRelease` | **BUILD SUCCESSFUL** — `app-release.aab` (32.1 MB) |
| `apksigner verify app-release.apk` | Signature valid (Android Debug cert — the designed no-secrets fallback) |
| Unit suite | **98 tests, 0 failures, 0 errors, 0 skipped** across 14 classes |

GitHub Actions CI mirrors the build + unit-test + lint steps on every push
(see `.github/workflows/build.yml`).

## Environment limitations — reported honestly

1. **No physical Android tablet** in this environment. Everything stylus-
   related (pressure accuracy on real hardware, palm rejection against a
   real palm, input latency in long sessions) is **design- and unit-tested
   only** and must be device-verified before any performance claims.
2. **No device/emulator execution** — instrumented tests compile in CI but
   have not been executed here.
3. **pdfium's native library cannot load inside JVM Robolectric**, so the
   unit-level export tests verify through the independent pdfbox reload
   path (parse + operators + geometry). The production pipeline itself
   re-opens every export with pdfium on-device (`PdfVerifier`).
4. **Macrobenchmark / Baseline Profiles / Android Profiler measurements
   were not run** — they require a device. The performance work shipped is
   architectural (spatial indexing, viewport culling, caches, lazy PDF
   rendering, batched DB transactions), not measured tuning.

## Device QA checklist (before release)

1. **Handwriting**: stylus input, pressure, drawing accuracy, palm
   rejection, pen thickness, colors, highlighter readability, eraser
   accuracy (all three modes), lasso, undo/redo, transforms, zoom/pan,
   a 20+ minute writing session (watch for dropped strokes).
2. **PDF**: text-based, image-based, large (100+ page), mixed page sizes,
   rotated pages; annotation placement survives zoom/rotate/reopen; export
   re-opens in an independent viewer (Adobe/Chrome) with correct ordering,
   text, images, annotation alignment and page dimensions; page ops
   (reorder/merge/extract) then re-export.
3. **Data integrity**: create notebook → write pages → images → text →
   shapes → import & annotate a PDF → wait for autosave → force-stop →
   reopen; also device restart, mid-save process death, failed export
   (full disk), duplicate imports, cache cleanup, backup → restore into a
   fresh install.
4. **i18n/a11y**: Arabic RTL layout + mixed AR/EN text boxes and titles,
   large font scale, TalkBack labels, touch-target sizes.
