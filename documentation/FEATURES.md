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

## What Part 1 does NOT include (honest scope)

- Handwriting engine **rework** — Part 2 (current engine preserved as-is)
- PDF features beyond v1.0.0 — Part 4
- Drag-and-drop reorder gestures (custom order is via move up/down actions)
- Phone-specific layouts beyond the compact bottom bar
- Trash/undo for deletions (confirmations instead)

See `ROADMAP.md` for the full plan.
