# Nanote — Technology Stack

All versions are pinned in `gradle/libs.versions.toml` (Gradle Version
Catalog). The stack was inherited from the audited v1.0.0 build (CI green)
and extended only with verified, compatible additions.

## Build system

| Tool | Version | Notes |
|---|---|---|
| Gradle | 8.9 | Wrapper committed (`gradlew`); CI runs `./gradlew` |
| Android Gradle Plugin | 8.5.2 | Requires JDK 17 |
| Kotlin | 2.0.21 | Compose compiler via `org.jetbrains.kotlin.plugin.compose` |
| KSP | 2.0.21-1.0.26 | Room + Hilt annotation processing |
| Hilt | 2.52 | DI: `@HiltViewModel`, `hilt-navigation-compose` 1.2.0 |
| Gradle Version Catalog | — | `gradle/libs.versions.toml` |

## Android

| Library | Version | Role |
|---|---|---|
| compileSdk / targetSdk | 35 | Android 15 |
| minSdk | 26 | Android 8.0 (java.time without desugaring) |
| androidx.core:core-ktx | 1.13.1 | Core KTX |
| androidx.core:core-splashscreen | 1.0.1 | Splash screen API |
| androidx.appcompat | 1.7.0 | AppCompatActivity + per-app locales (`AppCompatDelegate`) |
| androidx.activity:activity-compose | 1.9.3 | `rememberLauncherForActivityResult`, `enableEdgeToEdge`-era activity APIs |
| androidx.navigation:navigation-compose | 2.8.3 | String-arg routes via `NanoteDestinations` |
| androidx.lifecycle (runtime/viewmodel/runtime-compose) | 2.8.6 | `collectAsStateWithLifecycle` |

## UI — Jetpack Compose

| Library | Version | Role |
|---|---|---|
| Compose BOM | 2024.10.01 | Aligns all Compose artifacts |
| material3 | (BOM) 1.3.0 | Material 3 components |
| material3-window-size-class | 1.3.0 | `WindowSizeClass` adaptive navigation |
| material-icons-extended | (BOM) | Notebook icon set, nav icons |
| animation | (BOM) | Selection-mode transitions |

## Data

| Library | Version | Role |
|---|---|---|
| androidx.room (runtime/ktx/compiler) | 2.6.1 | Document/folder/activity index; schemas exported to `core/database/schemas` |
| androidx.datastore:datastore-preferences | 1.1.1 | User preferences (theme, accent, language, defaults) |
| kotlinx-serialization-json | 1.7.3 | `.nanote` document format + editor settings |
| kotlinx-coroutines-android | 1.9.0 | Flows, dispatchers |

## Ink engine (Part 2, custom vector canvas)

**Decision — Jetpack Ink vs. custom Canvas engine.** The Part 2 spec names
Jetpack Ink (`androidx.ink`) as the primary candidate, with custom rendering
allowed "when technically necessary". Verified against Google's Maven
metadata: `androidx.ink:ink` has **no stable release** (latest is
`1.0.0-alpha05`, alpha quality). Beyond stability, Ink's API surface
(wet-stroke rendering via `InProgressStrokesView` + `InProgressStrokeId`)
does not provide:

- rendering of **persisted custom vector strokes** with per-tool styles
  (fountain pressure × velocity, brush taper, pencil grain),
- partial erase with geometry preservation,
- selection transforms (move/resize/rotate) over stored strokes,
- our `.nanote` v3 serialization.

A custom `android.graphics.Canvas` engine (inherited from v1.0.0 and rebuilt
for Part 2) satisfies every requirement, keeps full offline determinism, and
preserves the existing document format. The separation of concerns demanded
by the spec is maintained: input handling (`CanvasView` / `ToolController`),
stroke data (`core/model`), rendering (`ElementRenderer` / `PageBackgrounds`),
editing (`DocumentModel` commands), persistence (`DocumentFormat`).

| Component | Location | Role |
|---|---|---|
| CanvasView | `core/graphics` | Surface + gesture/stylus routing, historical event replay, overlays |
| ToolController | `core/graphics` | Input → commands (pens, eraser, selection, clipboard, groups) |
| ElementRenderer | `core/graphics` | Per-tool stroke engines, rich text (StaticLayout + bidi), shapes, images |
| PageBackgrounds | `core/graphics` | 12 template painters + infinite dot grid |
| SpatialIndex | `core/graphics` | Uniform-grid broad-phase (erase / lasso / culling) |
| StrokeOps | `core:model` | Pure-Kotlin erase split, transforms, geometric hit tests, smoothing |
| ShapeRecognition | `core:model` | Deterministic geometry-only recognition (no AI) |
| DocumentModel | `core:model` | Command-pattern undo/redo (200 steps), page ops, groups |
| DocumentFormat | `core/storage` | Atomic JSON `.nanote` v3 + salvage reader + migrations |

## PDF engine (preserved from v1.0.0)

| Library | Version | License | Role |
|---|---|---|---|
| com.github.barteksc:pdfium-android | 1.9.0 | Apache-2.0 | PDF raster (all pages guaranteed, serial native thread) |
| com.tom-roush:pdfbox-android | 2.0.27.0 | Apache-2.0 | Text layer extraction for selection |
| androidx.documentfile | 1.0.1 | Apache-2.0 | SAF document names for imports |

## Testing

| Library | Version | Role |
|---|---|---|
| JUnit | 4.13.2 | Test runner + assertions |
| Robolectric | 4.16.1 | Room + repository + renderer tests on the JVM (`@Config(sdk=[34])`) |
| androidx.test:core-ktx | 1.6.1 | `ApplicationProvider` |
| androidx.test.ext:junit | 1.2.1 | Instrumented runner |
| espresso-core | 3.6.1 | Instrumented assertions |
| compose ui-test-junit4 | (BOM) | Compose smoke tests |

## Removed in Part 1 (offline mandate)

* ~~`com.squareup.okhttp3:okhttp`~~ — AI client transport, no longer needed
* ~~`android.permission.INTERNET`~~ — the app has no network features

## Repository conventions

* Repositories own `Dispatchers.IO` switching; ViewModels stay on `viewModelScope`.
* Version bumps are deliberate: the inherited set is CI-proven; anything new
  was verified against Google/Maven metadata before pinning.
* Mirrors: `maven.aliyun.com` repositories are kept as **fallbacks after** google()/mavenCentral()
  so builds also work from restricted networks; official repositories come first

## Testing stack (as verified in Part 4)

| Tool | Role |
|---|---|
| JUnit 4 | all unit suites |
| Robolectric 4.13 | Android-context tests (repository, migrations, PDF ops, renderers) — runs on JVM |
| kotlinx-coroutines-test | suspend function tests |
| androidx.test (core-ktx / junit / espresso) | instrumented tests (compile-verified in CI, run on device) |
| Temurin JDK 17 | build JVM (matches CI) |

Part 4 verification environment: JDK 17.0.21, Android SDK 35 (build-tools
35.0.0), Gradle 8.9 — `assembleDebug`, `assembleRelease`, `bundleRelease`
and the full 98-test unit suite all green.
