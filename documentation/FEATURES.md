# Nanote — Feature Matrix

## Part 1 (this release, v1.1.0) — Foundation & document management

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

## Part 3 (this release, v1.2.0) — PDF engine, local storage, search & export

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
