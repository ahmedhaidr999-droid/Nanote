# Nanote — Architecture (Part 1)

## Overview

Nanote follows **MVVM + Clean Architecture** principles in a modular Gradle
project. The UI never touches the database directly; ViewModels expose
`StateFlow`s backed by repositories, repositories own Room (metadata index)
and the file system (document content), and a single-activity Compose shell
hosts all features.

```
┌─────────────────────────── app (shell, DI, navigation) ───────────────────────┐
│  MainActivity · WindowSizeClass adaptive rail/bottom-bar · NavHost · Hilt     │
└──────┬───────────────┬──────────────┬───────────────┬───────────────┬────────┘
       │               │              │               │               │
  feature:home   feature:library  feature:folders  feature:settings  feature:editor
  (dashboard)    (browser+share   (nested folder   (preferences)    (v1.0.0 canvas
                  DocumentBrowser  browser)                          editor, kept)
                  state holder)
       │               │              │               │               │
       └───────┬───────┴──────┬───────┴───────┬───────┘               │
               │              │               │                       │
        feature:notebooks   core:design   core:graphics + core:pdf
        (notebook form)     (theme + dumb  (ink engine + pdfium/pdfbox,
                             components)    Part 2/4 foundations)
                                    │
                                    ▼
                          core:data (repositories)
                     DocumentRepository · UserPreferencesRepository
                                    │
                    ┌───────────────┼──────────────────┐
                    ▼               ▼                  ▼
              core:database    core:storage       SettingsStore
              (Room index,     (.nanote format,   (editor runtime prefs,
               v1→v2 migration) atomic saves)      legacy v1 keys)
```

## Modules and responsibilities

| Module | Responsibility |
|---|---|
| `:app` | Single activity, adaptive navigation shell (rail on expanded widths, bottom bar on compact), NavHost, Hilt DI graph, splash theme, launcher resources |
| `:core:common` | `AppLog`, `DispatcherProvider`, `@ApplicationScope` qualifier |
| `:core:model` | Pure domain models: `DocumentMeta`, `Folder`, cover/icon/template/page enums, `UserPreferences`, `DocumentSorter` (locale-aware, unit-tested) |
| `:core:navigation` | `NanoteDestinations` route constants — the single source of truth shared by the shell and every feature |
| `:core:design` | NanoteTheme (light/dark/dynamic + 7 accents), typography, `Dimens`, shared dumb components (document cards, folder picker, sort menu, page-template patterns), common localized strings |
| `:core:database` | Room: `notes` (document index), `folders` (nested via `parentId`), `recent_activity`; DAOs with Flow queries + a dynamic `@RawQuery` filter; `MIGRATION_1_2` |
| `:core:storage` | The versioned `.nanote` JSON format with forward migrations and partial-recovery loading |
| `:core:data` | `DocumentRepository` (library queries, notebook management, nested-folder tree/cycle logic, batch ops, custom order, activity log, import/export, thumbnails) + `UserPreferencesRepository` (Preferences DataStore, with a one-time import of v1.0 appearance keys) |
| `:core:graphics` | The ink canvas engine (CanvasView, element renderer, selection, tool controller) — preserved from v1.0.0, ready for the Part 2 handwriting engine |
| `:core:pdf` | pdfium raster engine (serial native thread, resolution buckets, adaptive LRU cache) + pdfbox text layer |
| `:feature:home` | Dashboard: search, quick actions, recents, favorites, notebooks, folders, activity feed |
| `:feature:library` | `DocumentBrowser` reusable state holder (filter/search/sort/view/selection) + the library screen, context action sheets, metadata sheet |
| `:feature:notebooks` | The notebook form (cover colour, icon, template, orientation, size) reused by home, library and settings |
| `:feature:folders` | Nested folder browser with breadcrumbs and folder management |
| `:feature:settings` | Appearance, library defaults, notebook defaults, pen behaviour, storage, about |
| `:feature:editor` | The full v1.0.0 editor (split workspace, toolbar, autosave) with the AI features removed for the offline mandate |

Dependency rule: features → core layers only; `:app` is the sole module that
knows about every feature. There are no dependency cycles.

## Key decisions

1. **Room is an index, files are the truth.** The `notes` table stores
   searchable metadata; document content lives in
   `filesDir/documents/<id>/doc.nanote` (atomic writes, versioned format).
   This keeps lists instant and content portable.
2. **Unified document model.** Notebooks and imported PDFs share one
   `notes` table (`kind` discriminator) so favorites, folders, search,
   activity and batch operations behave identically for both.
3. **Folder deletion lifts children.** Deleting a folder never deletes
   documents: contents move to the parent (root at top level). Destructive
   operations always run through confirmation dialogs.
4. **Cycle-safe folder moves.** `moveFolder` walks the subtree and refuses
   moves that would create a cycle; the UI additionally disables the
   folder's own subtree in the picker.
5. **Favorites are a column, activity is a table.** `favorite` on the row
   powers filtering; `recent_activity` is an append-only log (pruned to the
   latest 100, cascade-deleted with its document) that powers the home feed.
6. **Dynamic filtering with bound args.** Library perspectives are served
   by a `@RawQuery` whose WHERE clause is assembled by the repository with
   positional arguments only (including LIKE escaping) — no string
   interpolation of user input.
7. **Two settings stores, one migration path.** v1.0.0 kept everything in
   SharedPreferences; Part 1 moves user preferences to DataStore (with a
   one-time key import) while the editor's tool/toolbar runtime config
   stays in `SettingsStore` until Part 2 consolidates it. This avoided
   destabilising the working editor.
8. **Sorting is pure Kotlin.** `DocumentSorter` (locale-aware `Collator`
   for alphabetical) is a plain object, unit-tested, applied after the SQL
   filter so all perspectives share identical ordering semantics.
9. **Offline mandate.** `AiService`, the Ask-AI sheet, translate/web-search
   actions, OkHttp and the `INTERNET` permission were removed. The manifest
   now requests only `CAMERA` (insert photos) and `VIBRATE` (pen haptics).
10. **The editor is preserved, not placeholder'd.** The Part 1 spec asked
    for an editor placeholder, but a working editor already existed in
    v1.0.0; destroying it would violate "preserve existing working
    functionality". The editor route is fully integrated into the new
    navigation, and Part 2 will upgrade the engine in place.

## Data flow example (create notebook)

```
NotebookFormDialog (feature:notebooks)
  → LibraryViewModel.createNotebook(request)          (feature:library)
    → DocumentRepository.createNotebook(request)       (core:data)
      → DocumentFormat.save(doc.nanote)                (core:storage)
      → NoteDao.upsert(row with cover/template/…)      (core:database)
      → RecentActivityDao.insert(CREATED)
  ← Room invalidation flows re-emit → UI recomposes
```

## Testing strategy

* **Pure JVM:** `DocumentSorterTest`, `DocumentFormatTest` (round-trip +
  truncated-file recovery).
* **Robolectric:** `MigrationTest` (hand-built v1 database upgraded and
  validated by Room), `DocumentRepositoryImplTest` (nesting, counts,
  cycles, filters, batches, custom order, activity cascade).
* **Instrumented (compiled in CI, run on device):** `AppLaunchTest` launch
  + branding smoke checks.
* **CI:** debug build of all modules, unit tests, `:app:lintDebug`,
  `assembleDebugAndroidTest`, signed release APK (see
  `.github/workflows/build.yml`).
