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

## PDF / ink engine (preserved from v1.0.0)

| Library | Version | License | Role |
|---|---|---|---|
| com.github.barteksc:pdfium-android | 1.9.0 | Apache-2.0 | PDF raster (all pages guaranteed, serial native thread) |
| com.tom-roush:pdfbox-android | 2.0.27.0 | Apache-2.0 | Text layer extraction for selection |
| androidx.documentfile | 1.0.1 | Apache-2.0 | SAF document names for imports |

## Testing

| Library | Version | Role |
|---|---|---|
| JUnit | 4.13.2 | Test runner + assertions |
| Robolectric | 4.16.1 | Room + repository tests on the JVM (`@Config(sdk=[34])`) |
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
  for CI stability (an aliyun 502 poisoned a whole CI run otherwise).
