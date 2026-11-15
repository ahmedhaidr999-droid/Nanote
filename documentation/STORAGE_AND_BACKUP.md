# Nanote — Storage & Backup

How Nanote persists everything locally (Parts 1–3) and how the offline
backup/restore system works. **Nothing in this document requires a
network connection.**

---

## 1. Storage model — Room index + files

Nanote never stores document content inside a database row. Room is the
*searchable index*; files are the *content*.

### Room (schema v3)

| Table | Purpose |
|---|---|
| `notes` | document index: title, kind (NOTE/PDF), folder, favourite, page count, cover/icon/template/size, timestamps, sort order, file size, source file name |
| `folders` | nested folders |
| `recent_activity` | home feed |
| `tags` + `note_tags` | document tagging (Part 3) |
| `pdf_text_pages` | extracted text per PDF page — the local full-text search index |
| `pdf_bookmarks` | user bookmarks per imported PDF |
| `reading_positions` | last-read page + zoom per document |
| `search_history` | recent queries for suggestions |

All Part 3 tables cascade-delete with their document. Migrations
v1→v2 (Part 1) and v2→v3 (Part 3) are unit-tested and never destructive.

### Files (private app storage only)

```
filesDir/documents/<docId>/doc.nanote    versioned JSON content (atomic writes)
filesDir/documents/<docId>/original.pdf  imported source (never modified)
filesDir/documents/<docId>/edited.pdf    working copy after page operations
filesDir/documents/<docId>/images/       embedded images
filesDir/thumbnails/<docId>.png          first-page thumbnail cache
cacheDir/…                               render caches, export/restore staging
```

No path is ever logged; nothing is written to shared storage without an
explicit SAF picker.

## 2. Autosave & crash recovery

* Committed changes trigger a **debounced save** (default 2 s, adjustable
  1–10 s, can be disabled in Settings) plus a 30 s periodic safety net.
* Saves write to a temp file and **atomically rename** — a crash can never
  truncate a previously good document.
* A lenient salvage reader recovers broken arrays individually (partial
  recovery is surfaced to the user).
* The final save on editor exit runs in the application scope and is
  awaited by `flush()` — handwriting is never silently discarded, even
  with autosave disabled.
* Reading position (page + zoom) persists ~1 s after the last change.

## 3. The `.nanote-backup` archive format (v1)

A plain ZIP, fully documented and versioned:

```
manifest.json      {"formatVersion":1, "appVersion":"…", "createdAt":…,
                    "kind":"FULL|SELECTION", "entries":[{id,title,kind,pageCount,bytes}…]}
notes.json         index rows of the backed-up documents
folders.json       folder rows referenced by them (ancestors included)
tags.json          tag rows
note_tags.json     document ⇄ tag assignments
pdf_text.json      extracted-text index rows (search survives restore)
bookmarks.json     PDF bookmarks
files/<docId>/…    whole document directories (content, PDFs, images)
```

* The **reader rejects unknown future format versions**.
* Rows are plain JSON (kotlinx-serialization), decoupled from Room —
  stable across app updates and testable on the JVM.
* Round-trip, future-version rejection and missing-directory handling are
  unit-tested (`BackupFormatTest`).

## 4. Backup

Settings → Backup & restore → **Back up all documents**:

1. SAF `CreateDocument` picks the destination (default name
   `nanote-backup-YYYYMMDD-HHMM.nanote-backup`).
2. Rows + files are streamed into the archive on `Dispatchers.IO`.
3. Nothing is deleted or modified; the archive is a snapshot.

Per-notebook or selection backups are supported by the engine
(`BackupManager.createBackup(docIds…)`) — the UI ships the full backup.

## 5. Restore

1. SAF `OpenDocument` picks an archive; it is staged into the cache dir
   (untrusted input is never streamed directly into live data).
2. The manifest is read and shown as a **preview dialog** (counts +
   titles) before anything is touched.
3. On confirm: files extract to a staging directory; only then are rows
   inserted and directories moved into `documents/`.
4. **Restored documents always get new ids.** Name collisions resolve to
   `"Title (Restored)"` — nothing existing is ever overwritten, and
   favourites are not inherited.
5. Folders are recreated (with new ids), tags merge by name, the PDF text
   index and bookmarks are re-attached.

A failed restore leaves the library untouched (all writes happen after
successful extraction).

## 6. Verification status & limitations

* Backup format round-trip, migration chain, repository behaviour and PDF
  page operations are covered by unit/Robolectric tests that run in CI.
* Full end-to-end restore into an **independent device/install** was not
  executable in this development environment — restore logic is covered
  by automated tests, and the format is documented for external
  verification in Part 4 QA.
* The archive is a snapshot, not a sync: restoring never deletes current
  documents that are absent from the archive.

## 7. Part 4 integration notes

* A `.nanote` v3 file can carry **both** Part 2 content (infinite canvas
  mode, the four new shape kinds with polygon vertices) and Part 3 content
  (strikethrough marks) at once — verified field-by-field by
  `MergedContentFormatTest`, including legacy v2 files (defaults: PAGED
  canvas, no marks) and part-only files written by either development
  line.
* The Room schema is unchanged by the integration (v3); the exported
  schema JSONs (2.json, 3.json) are committed under
  `core/database/schemas/` for release hygiene.
