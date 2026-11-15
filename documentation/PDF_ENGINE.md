# Nanote — PDF Engine

This document describes the PDF subsystem as of **Part 3** (v1.2.0):
rendering, text extraction, annotation, page operations and export —
including the exact libraries used, the coordinate systems, and the
honest limitations.

---

## 1. Architecture — five separated responsibilities

| Responsibility | Module | Engine | Files |
|---|---|---|---|
| Rendering | `:core:pdf` | **pdfium-android 1.9.0** (`com.github.barteksc`) | `PdfiumCoreShim`, `PdfDocumentHandle`, `PageRenderManager`, `PageBitmapCache` |
| Text extraction | `:core:pdf` | **pdfbox-android 2.0.27.0** (`com.tom.roush`) | `PdfTextExtractor`, `WordCollector`, `PdfTextIndexer` |
| Annotations | `:core:pdf` + `:core:graphics` | Nanote vector model (own code) | `PdfContentPainter`, `PdfMarkupAnnotator`, `ToolController` |
| Page operations | `:core:pdf` | pdfbox-android | `PdfPageOps` |
| Exporting | `:core:pdf` + `:core:data` | pdfium (verify) + pdfbox (write) | `PdfVerifier`, `DocumentRepositoryImpl.exportDocument` |

Rules:

* No library API is used without verification against the published
  artifact — the pdfium shim documents the exact native calls it wraps.
* Rendering and writing NEVER share a document handle: pdfium renders
  read-only, pdfbox writes to temp files that atomically replace the
  working copy.
* Every export is verified by **reopening it with pdfium** (a different
  engine than the writer) before it is reported as successful.

## 2. File layout per imported PDF

```
documents/<docId>/original.pdf   the imported source file — NEVER modified
documents/<docId>/edited.pdf     working copy, created by the first page operation
documents/<docId>/doc.nanote     vector annotations (ink, shapes, text, marks)
```

`original.pdf` is written once at import (SAF stream copy) and never
touched again. Page operations (delete/duplicate/reorder/rotate/insert)
write `edited.pdf`; the editor, exports and the text index all prefer
`edited.pdf` when it exists. "Export original" always exports the working
document as-is; nothing ever overwrites the imported file.

## 3. Coordinate systems

* **World/page space** — PDF points, **top-left origin**, page-local.
  All annotation geometry (strokes, shapes, text boxes, images) lives here.
* **RelRect text marks** — `[x0, y0, x1, y1]` normalized to `[0..1]` of the
  page, so highlights/underlines/strikethroughs stay correct when page
  dimensions are re-read after operations like rotation.
* **PDF user space** — bottom-left origin; conversion at export time is
  `y_pdf = pageHeight − y_world` (handled centrally by `PdfContentPainter`).
* **Quad points** — emitted counter-clockwise as required by PDF 32000-1
  §12.5.6.10 (`PdfMarkupAnnotator`).

Because every annotation is stored in page-local coordinates, positions
remain correct when the user zooms, scrolls, rotates or reopens the
document, and after page reordering.

## 4. Import pipeline

1. SAF (`ACTION_OPEN_DOCUMENT`) — no broad storage permission is requested.
2. Stream-copied into `documents/<id>/original.pdf` (source preserved).
3. Page count + exact point sizes read via pdfium (`getPageWidthPoint/…`).
4. Room row created (title, page count, file size, source name).
5. Background text extraction builds the search index (`PdfTextIndexer`
   → `pdf_text_pages` table). Scanned pages without embedded text simply
   index empty — **no OCR is performed or claimed**.

Malformed files are rejected safely: import wraps the whole pipeline in
try/catch, deletes the partial directory and rethrows a user-facing error.

## 5. Viewer & annotation (recap of the shipped behaviour)

* Lazy page rendering with bitmap cache + prefetch (bounded memory —
  never all pages at once); zoom/pan never re-render mid-gesture.
* Handwriting: pen / pencil / brush / highlighter with pressure,
  plus eraser (stroke / object / pixel-split), lasso, undo/redo.
* Text selection on text-based pages: long-press word → drag handles →
  copy / highlight / underline / **strikethrough (new in Part 3)**.
* Page panel: thumbnails, bookmarks, jump-to-page, per-page operations.
* Reading position (page + zoom) is persisted per document and restored.

## 6. Page operations (`PdfPageOps`, all via pdfbox)

| Operation | Behaviour |
|---|---|
| Delete pages | removes pages; annotations on removed pages are dropped, survivors keep order |
| Duplicate page | shallow import (shared resources — valid PDF), inserted after the original |
| Reorder / move | page-tree rebuild; annotation page references follow |
| Rotate 90° | `/Rotate` incremented; page sizes re-read so the layout reflects rotation |
| Extract pages | new document containing the selected pages in the chosen order (split = two extracts) |
| Insert from PDF | picks another PDF via SAF, splices all its pages at the chosen position |
| Merge | concatenates documents |

Every operation writes to a **temp file → atomic rename**; the `.nanote`
page list is rebuilt from the new PDF and the text index is re-extracted.

## 7. Export modes (`ExportOptions`)

| Mode | What happens |
|---|---|
| `FLATTENED` | every annotation drawn into page content (ink, marks, shapes, images, text) — pixel-exact, not editable |
| `WITH_MARKUP_ANNOTATIONS` | ink/images/text flattened; highlights, underlines, strikethroughs written as **real `PDAnnotationTextMarkup`** so text stays selectable in readers that support it |
| `ORIGINAL` | byte-for-byte copy of the working document — no annotations |

Page subsets (`options.pages`) are extracted from the finished export via
`PdfPageOps.extractPages`. Exports are **never** produced by taking
screenshots — always through pdfbox content streams.

Verification (`PdfVerifier`): reopen with pdfium, check page count > 0 and
rasterize the first page; a failed verification fails the export.

## 8. Honest limitations

* **Arabic text boxes in exports**: PDFBox's built-in Type1 fonts cannot
  shape Arabic; Latin-safe characters are drawn and other characters are
  replaced (`winAnsiSafe`). Proper bidi/shaping needs an embedded
  Arabic-capable font + layout engine (future work).
* **Scanned PDFs** have no extractable text — no text selection, no text
  search, and no OCR (none is claimed).
* **Markup annotation opacity** is approximated by colour blending toward
  white (PDFBox 2.0.x annotations expose no alpha constant).
* Ink strokes are always flattened into content streams (PDF ink
  annotation objects with correct appearance streams are future work).
* `duplicatePage` shares page resources with the original — valid PDF,
  but duplicated pages are not independently editable inside the PDF file
  itself (annotations are still separate in Nanote's vector layer).

## 9. Part 4 integration changes

* `PdfContentPainter.drawShape` now draws **all nine shape kinds** — the
  Part 2 branch added double arrow, rounded rect (chamfered-corner
  approximation), ellipse and polygon (normalized vertices) to the model,
  and Part 4 ported those branches into this painter so exports match the
  canvas. Covered by `MergedContentPaintTest` (Robolectric): merged
  content (strokes + 9 shapes + strikethrough) paints into a PDF that
  reopens with exact page geometry and the expected path/stroke operators.
* The strikethrough export draws as a stroked center line through each
  marked rect (round caps), matching the on-canvas appearance.
* pdfium rasterization verification (`PdfVerifier`) still runs on-device
  in the production pipeline; under JVM Robolectric the native library
  cannot load, so unit-level export tests verify via the independent
  pdfbox reload path instead.
