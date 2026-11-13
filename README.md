# Nanote

**Nanote** is a fast, local-first handwritten-notes and PDF-annotation app for
Android phones, tablets, foldables and Chromebooks. Low-latency writing with a
stylus or finger, notebooks and folders, paper templates, shapes, lasso,
images and text on a spacious canvas, PDF import and annotation with text
selection, split workspace, infinite canvas, high-quality PDF/PNG export,
favorites, quick switching between recent notes, links, and a laser pointer
for presenting.

**العربية** — **نانوت** تطبيقٌ سريع لتدوين الملاحظات بخط اليد والتعليق على
ملفات PDF على أجهزة أندرويد — هواتف وأجهزة لوحية وأجهزة قابلة للطي
وChromebook — ويعمل محليًا بالكامل. يوفّر كتابةً فائقة الاستجابة بالقلم أو
بالإصبع، ودفاتر ومجلدات، وقوالب ورقية، وأشكالًا، وأداة تحديد (لاسو)، وصورًا
ونصوصًا على لوحة رحبة، واستيراد ملفات PDF والتعليق عليها وتحديد نصّها، ومساحة
عمل مقسومة، وصفحات لا نهائية، وتصديرًا عالي الجودة إلى PDF وPNG، ومفضلة،
ومؤشرًا ليزريًا للعرض والتقديم.

**v2.0.0 is a major update**: a brand-new PDF rendering engine (the
gray-pages bug is fixed at the root), text selection on PDF pages
(copy / translate / search / highlight / underline), a highlighter-opacity
slider, a split workspace, infinite-canvas pages, hardened autosave and
undo/redo, and a versioned, damage-tolerant file format.

**الإصدار 2.0.0 تحديثٌ كبير**: محرك PDF جديد يقضي على مشكلة «الصفحات الرمادية»
من جذرها، وتحديد نص صفحات PDF (نسخ / ترجمة / بحث / تظليل / تسطير)، ومنزلق
شفافية قلم التظليل، ومساحة عمل مقسومة، وصفحات لا نهائية، وحفظ تلقائي
وتراجع/إعادة أكثر متانة، وصيغة ملفات محدّدة الإصدار تتّسامح مع التلف.

**Offline forever.** No cloud, no accounts, no sync, no analytics, no AI.
The merged manifest requests **zero permissions** — enforced at build time by
Gradle tasks (`checkManifestNoInternet`, `scanApkNoInternet{Debug,Release}`
scan the packaged APK bytes) and by a Robolectric test over the merged
manifest. Backup/Restore is a ZIP file through the Storage Access Framework,
and Android Auto-Backup is fully disabled. **دون اتصال نهائيًا**: لا سحابة، ولا
حسابات، ولا مزامنة، ولا تحليلات، ولا صلاحيات إطلاقًا.

---

## Install & updates · التثبيت والتحديث

**English** — grab the latest signed APK from
[**Releases**](https://github.com/ahmedhaidr999-droid/Nanote/releases/latest):
download **`Nanote-v2.0.0-release.apk`**, open it and confirm **Update** (or
**Install** for the first one). Every release is signed with the same key and
carries a strictly increasing `versionCode`, so **v2.0.0 installs right over
v1.4.0 — nothing is uninstalled and all notes are kept**: the Room database
and every ink file migrate automatically on first launch. Inside the app:
*Settings → About → Check for updates* opens this page in one tap. The app
itself has zero permissions and never touches the network; only the browser
does the download. Requires Android 8.0+. Never install the `-debug` CI
artifacts — those are signed with a temporary key and will never update in
place.

**العربية** — نزّل أحدث نسخة موقّعة من صفحة
[**الإصدارات**](https://github.com/ahmedhaidr999-droid/Nanote/releases/latest):
حمّل ملف **`Nanote-v2.0.0-release.apk`** وافتحه ثم اضغط **تحديث** (أو **تثبيت**
لأول مرة). كل إصدار موقّع بنفس المفتاح وبرقم بناء متزايد، لذلك **يُثبَّت
الإصدار 2.0.0 فوق النسخة 1.4.0 مباشرة — دون حذفها — وتبقى كل ملاحظاتك كما
هي**: تُرحَّل قاعدة البيانات وجميع ملفات الحبر تلقائيًا عند أول تشغيل. داخل
التطبيق: *الإعدادات ← حول ← التحقق من التحديثات* يفتح هذه الصفحة بضغطة واحدة.
التطبيق نفسه بلا أي صلاحيات ولا يتصل بالإنترنت نهائيًا؛ التنزيل يتم في المتصفح
فقط. يتطلب Android 8.0 أو أحدث. لا تثبّت نسخ `-debug` من ملفات CI المؤقتة —
لن تُثبَّت التحديثات فوقها أبدًا.

---

## What's new in v2.0.0 · جديد في هذا الإصدار

**English**

1. **New PDF engine — the gray-pages bug is gone.** The old engine was built
   on Android's `android.graphics.pdf.PdfRenderer`, the source of the
   notorious bug where only the first page rendered and the rest appeared
   gray/blank. v2.0.0 replaces it with **pdfium**
   (`com.github.mhiew:pdfium-android:1.9.2` — a maintained fork of barteksc's
   PdfiumAndroid, on Maven Central), which supports many simultaneously open
   pages: every page is opened via `openPage(index)` with its own correct
   index, every time. Text extraction moved to **PdfBox-Android**
   (`com.tom-roush:pdfbox-android:2.0.27.0`), which yields per-word bounding
   boxes — the foundation of the new text selection.
2. **Anti-gray-pages hardening.** Ten structural guarantees (next section)
   make a silent gray tile impossible by construction — a failed tile always
   surfaces a visible *"Couldn't render — tap to retry"* chip that actually
   retries.
3. **Text selection on PDF pages.** Long-press (or press-drag) on real PDF
   text starts a selection with two draggable handles at word granularity. A
   contextual 5-action menu appears anchored to the selection (auto-clamped
   to stay on screen and above the keyboard): **Copy** (clipboard + toast),
   **Translate** (Google Translate via intent/browser), **Search web**
   (browser search), **Highlight** (a deletable highlighter band over the
   selected lines — undoable) and **Underline** (an underline stroke under
   each selected line — undoable). Strict rule: pressing on empty areas with
   no text never shows the menu and silently cancels.
4. **Highlighter opacity slider.** 0–100%, default 40%
   (`alpha = round(percent × 255 / 100)`). The setting applies to **new**
   strokes only — each stroke serializes its own alpha (InkFormat v2). The
   highlighter now composites with PorterDuff `SRC_OVER`, so text shows
   through and overlapping strokes stack naturally; old v1 strokes keep
   rendering exactly as before (35% legacy).
5. **Split workspace.** Two documents side by side with a draggable divider;
   each pane is fully independent — its own file, zoom, tool and undo stack —
   and the state survives rotation. Toggle button in the editor top bar.
6. **Autosave, hardened.** Saves run 2 seconds after the last edit, every 30
   seconds, on app pause/stop and on document close — all as atomic writes
   with `.bak` rotation (crash-safe). The autosave-delay setting is now
   actually honored (default 2000 ms).
7. **Bigger undo/redo.** The per-document notebook history now covers page
   operations too — add/delete/duplicate/reorder page, paper and size
   changes — up to 200 steps, in addition to the existing 300-step per-page
   ink history (draw, erase whole + partial, lasso move/scale/rotate/recolor,
   shape tool, hold-to-snap, text/image/link objects).
8. **Infinite canvas pages.** A new "Infinite" page size: pages auto-grow
   when ink approaches the edge, with free panning and slack. Available in
   page settings and new-notebook creation.
9. **Zoom.** Pinch range is now 0.5×–10×; double-tap doubles the current zoom
   about the tap point (toggling back to fit when already at max).
10. **File format v2.** Strokes use the binary InkFormat, now versioned **v2**
    (adds a per-stroke alpha byte) with a v1→v2 migration decoder. Decoding
    is per-stroke fault-tolerant: undecodable strokes are skipped and
    counted, and the user is informed *"Document partially restored: N
    damaged items were skipped"* instead of the whole page failing. The Room
    database migrated v1→v2 (adds a `pages.infinite` column) with a real
    `ALTER TABLE` migration. **Every file created by any previous version
    still opens.**
11. **Flattened PDF export.** Export the whole notebook as a flattened PDF:
    each PDF-backed page is rasterized at 3× with annotations composited on
    top (flattened into the final image). `.nanote` project export and
    PNG/share remain.
12. **Better thumbnails.** Thumbnails now include the PDF page background and
    highlighter strokes (previously paper-only).
13. **Everything from v1.4 keeps working.** Notebooks, folders, tags,
    favorites and trash; FTS search (Arabic-capable) over typed text; paper
    templates and page sizes; lasso; laser pointer; shape recognition
    (hold-to-snap); stylus palm rejection, barrel-button actions and the
    stylus database; biometric lock; zero permissions / fully offline;
    backup/restore ZIP; install-over updates via a strictly increasing
    `versionCode` and a fixed signing key.

**العربية**

1. **محرك PDF جديد — زوال مشكلة «الصفحات الرمادية».** استُبدل المحرك القائم
   على `PdfRenderer` بمحرك **pdfium** (`com.github.mhiew:pdfium-android:1.9.2`)
   الذي يدعم فتح صفحات كثيرة في الوقت نفسه، إذ تُفتح كل صفحة عبر
   `openPage(index)` بفهرسها الصحيح دائمًا؛ مع **PdfBox-Android**
   (`com.tom-roush:pdfbox-android:2.0.27.0`) لاستخراج النص بصندوق إحاطة لكل
   كلمة — وهو أساس تحديد النص الجديد.
2. **تحصينات ضد الصفحات الرمادية.** عشر ضمانات بنيوية (القسم التالي) تجعل
   البلاطة الرمادية الصامتة مستحيلة؛ وأي فشل في العرض يظهر كشريحة «تعذّر
   العرض — انقر لإعادة المحاولة» تُعيد المحاولة فعلًا عند النقر.
3. **تحديد النص في صفحات PDF.** الضغط المطوّل (أو الضغط والسحب) فوق نص PDF
   حقيقي يبدأ تحديدًا بمقبضين قابلين للسحب بدقة الكلمة، وتظهر قائمة سياقية من
   خمسة إجراءات مثبّتة عند التحديد (وتُضبَط تلقائيًا لتبقى داخل الشاشة وفوق
   لوحة المفاتيح): **نسخ** (إلى الحافظة مع إشعار)، **ترجمة** (عبر Google
   Translate باستخدام intent النظام أو المتصفح)، **بحث في الويب**، **تظليل**
   (شريط قابل للحذف فوق السطور المحددة — قابل للتراجع)، **تسطير** (خط أسفل كل
   سطر محدد — قابل للتراجع). الضغط على مناطق فارغة بلا نص لا يُظهر القائمة
   أبدًا ويُلغي التحديد بصمت.
4. **منزلق شفافية قلم التظليل.** من 0 إلى 100٪ (الافتراضي 40٪؛
   ألفا = تقريب(النسبة × 255 ÷ 100)). يُطبَّق على الضربات الجديدة فقط، إذ
   يخزّن كل ضربة قيمة ألفا الخاصة به (InkFormat v2)، ويمزج التظليل الآن بوضع
   SRC_OVER فيظهر النص من خلاله وتتراكب الضربات طبيعيًا؛ وضربات v1 القديمة
   تُعرض كما كانت تمامًا (شفافية قديمة 35٪).
5. **مساحة عمل مقسومة.** مستندان جنبًا إلى جنب مع فاصل قابل للسحب؛ كل لوحة
   مستقلة تمامًا: ملفها وتكبيرها وأداتها ومكدّس تراجعها، وتبقى الحالة بعد
   تدوير الجهاز. زر التبديل في الشريط العلوي للمحرر.
6. **حفظ تلقائي أمتن.** حفظ بعد ثانيتين من آخر تعديل + كل 30 ثانية + عند
   إيقاف التطبيق + عند إغلاق المستند؛ كتابات ذرّية مع تدوير `.bak` (آمن ضد
   الانهيار)، وأخيرًا صار إعداد تأخير الحفظ التلقائي يُحترم فعلًا (الافتراضي
   2000 ملّي ثانية).
7. **تراجع/إعادة أوسع.** يغطي سجلّ الدفتر الآن عمليات الصفحات أيضًا
   (إضافة/حذف/تكرار/إعادة ترتيب الصفحات، وتغيير الورق والمقاس) حتى 200 خطوة،
   إضافة إلى سجل الحبر المعروف لكل صفحة (300 خطوة): الرسم، والممحاة الكلية
   والجزئية، وتحريك/تحجيم/تدوير/تلوين تحديد اللاسو، وأداة الأشكال، والالتقاط
   بالضغط المطوّل، وكائنات النص/الصورة/الرابط.
8. **صفحات لا نهائية.** مقاس صفحة جديد «Infinite»: تنمو الصفحة تلقائيًا عند
   اقتراب الحبر من الحافة، مع تحريك حرّ مرن. متاح في إعدادات الصفحة وعند إنشاء
   دفتر جديد.
9. **تكبير.** مدى القرص الآن 0.5×–10×، والنقر المزدوج يضاعف التكبير الحالي
   حول نقطة النقر (ويعود إلى وضع الاحتواء عند بلوغ الحد الأقصى).
10. **صيغة الملفات v2.** الحبر بصيغة InkFormat الثنائية، وهي الآن محدّدة
    الإصدار **v2** (تضيف بايت ألفا لكل ضربة) مع مفكّك ترحيل v1→v2، وفكّ ترميز
    متسامح لكل ضربة على حدة: تُتخطى الضربات غير القابلة للفك وتُعدّ، ويُبلَّغ
    المستخدم «استُعيد المستند جزئيًا: تم تخطّي N عنصرًا تالفًا» بدل فشل الصفحة
    كلها. ورُحِّلت قاعدة Room من v1 إلى v2 (عمود `pages.infinite`) بترحيل
    `ALTER TABLE` حقيقي. **كل ملف أنشأه أي إصدار سابق يظل يفتح.**
11. **تصدير PDF مُسطَّح.** صدِّر الدفتر كاملًا كملف PDF مُسطَّح: كل صفحة
    مدعومة بـ PDF تُرسم بدقة 3× مع دمج التعليقات فوقها ضمن الصورة النهائية؛
    ويبقى تصدير مشروع `.nanote` والمشاركة بصيغة PNG كما هو.
12. **صور مصغّرة أدق.** تتضمن المصغّرات الآن خلفية صفحة PDF وضربات التظليل
    (كانت سابقًا تُظهر الورق فقط).
13. **كل شيء من v1.4 يظل يعمل.** الدفاتر والمجلدات والوسوم والمفضلة
    والمهملات، والبحث النصي الكامل (يدعم العربية) على النصوص المكتوبة،
    والقوالب الورقية والمقاسات، واللاسو، والمؤشر الليزري، والتعرف على الأشكال
    (الالتقاط بالمطوّل)، وتجاهل راحة اليد مع أزرار القلم الجانبية وقاعدة
    بيانات الأقلام، والقفل البيومتري، وصلاحيات صفرية/عمل دون اتصال كامل،
    والنسخ الاحتياطي/الاستعادة ZIP، والتحديث فوق التثبيت عبر رقم بناء متزايد
    بدقة ومفتاح توقيع ثابت.

---

## Why the PDF engine was replaced · لماذا استُبدل محرك PDF

Up to v1.4, PDF pages were drawn with Android's built-in
`android.graphics.pdf.PdfRenderer` — the engine behind the most reported bug
in Nanote's history: **only the first page rendered; every other page came up
gray/blank.** Three independent root causes:

- **One open page at a time.** `PdfRenderer` allows exactly ONE open page.
  The tile pipeline renders from multiple threads, so pages went through
  constant `openPage`/`closePage` churn under contention — tiles could render
  the wrong page or nothing at all. The first page (opened first, kept open)
  looked fine; the rest came up gray.
- **A poisoned pending-render set.** An uncaught exception in one tile-render
  worker permanently poisoned the pending-render bookkeeping, so the affected
  tiles were never rendered again — gray until the document was reopened.
- **Device-specific quirks.** On some devices `PdfRenderer` renders certain
  pages blank outright (JPX/JPEG-2000 images, CMYK color spaces) — no amount
  of retrying can fix a blank bitmap the platform hands us.

These are structural problems with the platform renderer, not bugs that could
be patched around. v2.0.0 swaps the engine end-to-end:

- **pdfium** — `com.github.mhiew:pdfium-android:1.9.2` (maintained fork of
  barteksc's PdfiumAndroid, on Maven Central). Draws the actual page bitmaps
  and supports many simultaneously open pages: every page is opened with
  `openPage(index)` and its own correct index, every time.
- **PdfBox-Android** — `com.tom-roush:pdfbox-android:2.0.27.0`. Extracts the
  text layer with per-word bounding boxes, which powers text selection.

**The ten anti-gray-pages guarantees** (all enforced in code; verified by the
manual test guide below):

1. The document is opened **once per session** and closed on exit.
2. Every page is opened via `openPage(index)` with **its own correct index** —
   no code path uses index 0 always.
3. Total strip height = **sum of the real per-page heights + gaps** — never
   `first-page-height × count`.
4. `pageCount` is validated `> 0` before any rendering happens.
5. All pdfium calls are serialized through **one dedicated single-thread
   executor**.
6. Every tile render is wrapped in try/catch with **one automatic retry**;
   failures are recorded and surfaced in-page as a *"Couldn't render — tap to
   retry"* chip — never a silent gray background — and tapping retries.
7. Tile cache keys include the **zoom bucket + tile coordinates + per-page
   cache isolation**, so pages can never bleed into each other.
8. Lazy tile rendering extends **one tile-ring beyond the viewport**.
9. Async render jobs are **never cancelled after the first page completes**.
10. Bitmaps are **never recycled while referenced on screen**.

**بالعربية** — حتى الإصدار 1.4 كانت صفحات PDF تُرسم عبر `PdfRenderer` المدمج في
أندرويد، وهو مصدر أشهر أخطاء التطبيق: لا تُعرض إلا الصفحة الأولى وتبقى البقية
رمادية/فارغة. الأسباب الجذرية ثلاثة: (١) السماح بفتح صفحة واحدة فقط في كل مرة،
فأدّى التقلب المستمر بين openPage/closePage تحت تزاحم الخيوط إلى بلاطات خاطئة
أو فاشلة؛ (٢) استثناء غير معالَج في عامل عرض البلاطات «يسمّم» مجموعة العرض
المعلّق نهائيًا فلا تُعاد محاولة تلك البلاطات أبدًا؛ (٣) غرابات خاصة ببعض
الأجهزة تجعل صفحات JPX/CMYK تُرسم فارغة. المشكلة بنيوية في محرك المنصة ولا
تُرقَّع، لذا استُبدل كليًا بـ pdfium (رسم يدعم فتح صفحات عديدة معًا بفهرس
صحيح لكل صفحة) وPdfBox-Android (استخراج النص بصناديق إحاطة لكل كلمة)، مع عشر
ضمانات بنيوية (أعلاه) تجعل البلاطة الرمادية الصامتة مستحيلة.

---

## Architecture · البنية المعمارية

Kotlin 2.0. Jetpack Compose (Material 3) drives the UI, while the ink surface
itself is a classic `View`/`Canvas` for maximum performance. MVVM +
repository with unidirectional data flow — one ViewModel + one immutable
UiState per screen. Hilt for DI, Room for metadata only (strokes live in
files), coroutines + Flow everywhere, single-activity Navigation Compose.

```
app/src/main/java/com/nanote/
  NanoteApp.kt  MainActivity.kt        # single activity, trash purge at start
  core/Brand.kt  StylusRegistry.kt     # single place to rebrand; stylus database
  domain/model/                        # Notebook, Folder, Page, PageObject (serializable)
  data/
    db/                                # Room v2: entities, DAOs, FTS4 (unicode61) over text boxes
    files/                             # InkFormat v2 blob store, atomic writes + .bak fallback,
                                       # SettingsStore (DataStore), NotebookArchive (.nanote),
                                       # LibraryArchive (full backup)
    repo/NotebookRepository.kt         # single source of truth; CRUD, trash, tags, FTS, backup
  ink/
    engine/                            # PURE Kotlin (unit-testable on the JVM, zero android.*):
                                       # Mat3/Bounds, immutable Stroke (STRIDE=4 pts),
                                       # pens/specs/palettes/papers, OneEuro smoothing
                                       # (ULTRA/HIGH/SMOOTH latency presets), width LUT +
                                       # variable-width outlines, RDP simplification,
                                       # SpatialGrid hit tests, erasers (whole-stroke + pixel
                                       # split), lasso selection, shape snap (geometry only),
                                       # History (300-step ink + 200-step page ops), PageDoc,
                                       # PageStripLayout (real per-page heights + gaps,
                                       # infinite-canvas growth), ViewTransform, InputRouter,
                                       # InkFormat v1→v2 decoder, ToolSpec
    render/                            # android.graphics layer: StrokePainter (SRC_OVER
                                       # highlighter compositing), PaperRenderer, InkTileCache
                                       # (512px async tiles, zoom buckets, per-page isolation,
                                       # one tile-ring beyond viewport), InkCanvasView
                                       # (custom View), PdfPageProvider (pdfium behind ONE
                                       # single-thread executor, retry chip on failure),
                                       # PdfBox word boxes + selection handles, ObjectPainter,
                                       # ThumbRenderer (incl. PDF background + highlighter)
    input/GestureController.kt         # zero-alloc pinch/pan math (0.5×–10×, double-tap zoom)
  export/PageExporter.kt               # vector-ink PDF + flattened 3× PDF + PNG/JPEG
                                       # at 72/150/300 dpi
  ui/                                  # Compose M3: library, editor (split workspace, text
                                       # selection menu, opacity slider), settings, components
```

**Ink pipeline.** Page space is PDF points (A4 = 595×842); points are packed
`x, y, pressure, tMs` (STRIDE 4) into immutable strokes. Touch events go
through unbuffered dispatch with historical samples first, are routed by the
pure `InputRouter` (stylus/finger/palm/mouse rules incl. barrel-button
actions and hover rejection), smoothed by a 1-euro filter (ULTRA/HIGH/SMOOTH
latency presets), and rendered as filled variable-width outlines with
midpoint-quad smoothing plus round caps. Committed ink is cached in 512-px
tiles (async render thread, one bucket per 1.41× zoom step, per-page cache
isolation, one tile-ring beyond the viewport); the live stroke draws on top
with a frozen-prefix + short-tail scheme. Nothing allocates in `onDraw` or
the touch loop.

**Storage.** Room holds metadata only; strokes live in
`filesDir/notebooks/<nb>/pages/<page>.ink` (deflated InkFormat v2 binary),
objects in a JSON sidecar, assets in `assets/`, thumbnails in
`filesDir/thumbs/<page>.webp` (regenerated after each save). Every write is
atomic (`tmp → fsync → .bak → rename`); loads fall back to the `.bak` when
the main file is corrupt.

**Localization.** English + Arabic with full RTL mirroring (the page canvas
itself is never mirrored). Per-app language switching lives in Settings
(`AppCompatDelegate.setApplicationLocales`, auto-persisted).

---

## File format & compatibility · صيغة الملفات والتوافق

```
filesDir/
  notebooks/<nb>/
    pages/<page>.ink    # strokes — deflated binary, InkFormat (versioned, now v2)
    objects sidecar     # text/image/link objects (JSON)
    assets/             # images, imported PDFs
  thumbs/<page>.webp    # thumbnails (now incl. PDF background + highlighter)
```

- **InkFormat v1→v2.** v2 adds one alpha byte per stroke (the
  highlighter-opacity setting). v1 files are migrated transparently on load
  by the v1→v2 decoder — no user action, no conversion step.
- **Partial recovery.** Decoding is per-stroke fault-tolerant: an
  undecodable stroke is skipped and counted, and the user is informed
  *"Document partially restored: N damaged items were skipped"* — one
  damaged stroke can never take the whole page down.
- **Crash-safe writes.** Every save is `tmp → fsync → .bak → rename` with
  `.bak` rotation; loads fall back to the `.bak` when the main file is
  corrupt. Autosave triggers: 2 s after the last edit, every 30 s, on app
  pause/stop, and on document close.
- **Room v1→v2.** A real `ALTER TABLE` migration adds the `pages.infinite`
  column (infinite-canvas pages); existing notebooks and notes keep opening
  untouched.
- **`.nanote` project archive.** A ZIP of one notebook — pages, objects,
  assets and metadata — for sharing; import on id collision always keeps
  both.
- **Library backup ZIP.** Full backup/restore of the whole library through
  the Storage Access Framework.

**Compatibility promise:** every file created by any previous version of
Nanote — v1 `.ink` files, `.nanote` archives and backup ZIPs — still opens in
v2.0.0.

---

## Build & run · البناء والتشغيل

Requirements: **JDK 17+**, **Android SDK** (platform 35, build-tools 35.0.0).

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (signed when the env vars below are set)
./gradlew testDebugUnitTest      # unit tests (pure JVM + Robolectric)
./gradlew lintDebug              # lint
adb install app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` must point to the SDK (`sdk.dir=...`) — Android Studio
creates it automatically.

**Release signing** is environment-driven, never committed: set
`ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD` to get a signed release; without them the release
APK stays unsigned. CI decodes the keystore from the repository's
**Actions secrets** (`ANDROID_KEYSTORE_BASE64`) and exports the same four
variables.

**What CI does** (`.github/workflows/android.yml`):

- Derives the version **from the tag only**: `vX.Y.Z` → `versionName X.Y.Z`
  and `versionCode = X*10000 + Y*100 + Z` (e.g. `v2.0.0` → `20000`) —
  strictly increasing, so every release installs right over the previous one.
- Rejects any tag that is not strictly newer than the latest release.
- Verifies the release APK with `apksigner verify --print-certs` and requires
  the `CN=Nanote` certificate.
- Publishes only the signed `Nanote-v…-release.apk` to the GitHub Release;
  `-debug` artifacts are signed with an ephemeral per-run key and are never
  installable over a release.

**Cutting a release (maintainer).** Versioning is fully automated — never
hand-edit `versionCode`. Push your changes to `main`, then tag:

```bash
git tag v2.0.0
git push origin v2.0.0
```

CI builds, tests, signs, verifies and publishes the release. Keep the Actions
secrets intact — if the signing key is lost or changed, updates stop
installing over existing installations.

---

## Manual test guide · دليل الاختبار اليدوي

**English** — the on-device checklist for v2.0.0:

1. Open a 20+ page PDF and scroll to the last page — **every page renders**.
2. Repeat with 1-, 2-, 5- and 100-page files, and with a mixed
   portrait/landscape file.
3. Annotate with pen + highlighter on several pages, close the document,
   reopen it — **annotations persist**.
4. Long-press real PDF text — the **5-action menu appears over text, never
   over empty areas**.
5. Change the highlighter opacity mid-session — **only new strokes are
   affected**.
6. **Undo/redo 20 times consecutively** without issues.
7. Enable **split view with two files, rotate the device** — state is
   preserved in both panes.
8. **No gray page in any scenario.**

If the *"Couldn't render — tap to retry"* chip ever appears, tap it — the
tile re-renders. If it persists with a specific file, please attach that file
to a bug report.

**العربية** — قائمة التحقق على الجهاز للإصدار 2.0.0:

1. افتح ملف PDF من 20 صفحة أو أكثر ومرّر إلى الصفحة الأخيرة — **يجب أن تُعرض
   كل صفحة**.
2. كرّر ذلك مع ملفات من 1 و2 و5 و100 صفحة، ومع ملف يخلط الاتجاهين الرأسي
   والأفقي.
3. علّم بالقلم وقلم التظليل على عدة صفحات، أغلق المستند ثم أعد فتحه — **تبقى
   التعليقات محفوظة**.
4. اضغط مطولًا على نص PDF حقيقي — **تظهر قائمة الإجراءات الخمسة فوق النص، ولا
   تظهر أبدًا فوق المناطق الفارغة**.
5. غيّر شفافية قلم التظليل أثناء الجلسة — **تتأثر الضربات الجديدة فقط**.
6. **تراجع/أعد 20 مرة متتالية** دون أي مشكلة.
7. فعّل **العرض المقسوم بملفين وأدرِ الجهاز** — تبقى الحالة محفوظة في
   اللوحتين.
8. **لا صفحة رمادية في أي سيناريو**.

وإذا ظهرت شريحة «تعذّر العرض — انقر لإعادة المحاولة» فانقر عليها لإعادة رسم
البلاطة؛ وإن تكررت مع ملف بعينه فأرفق الملف في تقرير الخطأ.

---

## Privacy · الخصوصية

**Zero permissions, offline forever.** No cloud, no accounts, no sync, no
analytics, no AI, no crash reporters, no Firebase/Play Services. The
*Translate* and *Search web* actions hand the selected text to your browser
or Google Translate via an Android intent — the app itself never opens a
socket.

- [x] No `INTERNET` (nor any permission) in the merged manifest — Gradle
      tasks scan the packaged APK bytes; a unit test asserts it too.
- [x] `allowBackup=false` + full cloud-backup and D2D transfer exclusions.
- [x] Storage access only through SAF + the system photo picker.
- [x] Optional app lock (BiometricPrompt + device credential) with
      FLAG_SECURE.
- [x] Trash with restore + 30-day auto-purge; destructive actions confirm.
- [x] Every string localized EN/AR.
