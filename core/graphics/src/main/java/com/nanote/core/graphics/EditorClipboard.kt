package com.nanote.core.graphics

import com.nanote.core.model.ImageElement
import com.nanote.core.model.ShapeElement
import com.nanote.core.model.Stroke
import com.nanote.core.model.TextElement

/**
 * Process-wide object clipboard (§8 copy / cut / paste).
 *
 * Holds vector elements — NOT pixels — so pasted ink stays editable.
 * Image paths are relative to the source document; pasting into a different
 * document drops the images (files are not copied across documents yet).
 */
object EditorClipboard {

    data class Elements(
        val docId: String,
        val strokes: List<Stroke>,
        val texts: List<TextElement>,
        val images: List<ImageElement>,
        val shapes: List<ShapeElement>
    ) {
        val isEmpty: Boolean get() = strokes.isEmpty() && texts.isEmpty() && images.isEmpty() && shapes.isEmpty()
    }

    @Volatile
    var content: Elements? = null

    fun copy(e: Elements) {
        content = if (e.isEmpty) null else e
    }

    fun clear() {
        content = null
    }

    val hasContent: Boolean get() = content?.isEmpty == false
}
