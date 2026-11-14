package com.nanote.app.core.data.format

import com.nanote.app.core.model.CURRENT_FORMAT_VERSION
import com.nanote.app.core.model.DocumentContent
import com.nanote.app.util.AppLog

/**
 * Sequential on-load format migrations, applied after [DocumentFormat.load]
 * and before a [com.nanote.app.core.model.DocumentModel] is handed out.
 *
 * Version history:
 *  - **v1** — pages / strokes / texts only.
 *  - **v2** — adds shapes, images, highlight annotations and underline
 *    annotations. Documents written by v1 simply lacked those fields, and
 *    kotlinx-serialization already defaults them to empty collections while
 *    decoding — so v1 → v2 is a pure version bump.
 */
object DocumentMigrations {

    private const val TAG = "DocumentMigrations"

    /** Brings [content] up to [CURRENT_FORMAT_VERSION], step by step. */
    fun migrate(content: DocumentContent): DocumentContent {
        var result = content

        // ---- v1 -> v2 -----------------------------------------------------
        if (result.version < 2) {
            AppLog.i(TAG, "migrating document format v1 -> v2")
            result = result.copy(
                version = 2,
                // v1 had no shape/image/annotation fields; the decoder's
                // defaults guarantee non-null (empty) collections already,
                // so there is nothing to repair besides the version.
                pages = result.pages,
                strokes = result.strokes,
                shapes = result.shapes,
                texts = result.texts,
                images = result.images,
                highlights = result.highlights,
                underlines = result.underlines
            )
        }

        // ---- v2 -> v3 (future steps plug in here) --------------------------
        // if (result.version < 3) {
        //     AppLog.i(TAG, "migrating document format v2 -> v3")
        //     result = ...
        // }

        // Safety net: never hand out a document older than the current format.
        if (result.version < CURRENT_FORMAT_VERSION) {
            result = result.copy(version = CURRENT_FORMAT_VERSION)
        }
        return result
    }
}
