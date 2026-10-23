package com.nanote.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Non-ink page objects. Placement is the 9 floats of a 3x3 matrix (same
 * layout as android.graphics.Matrix.getValues) plus a z-order. Stored as a
 * JSON sidecar next to the stroke file (spec section 5).
 */
@Serializable
sealed interface PageObject {
    val id: String
    val z: Int

    /** Approximate dirty-region anchor in page points. */
    val anchorX: Float
    val anchorY: Float
}

@Serializable
@SerialName("text")
data class TextBox(
    override val id: String,
    val text: String,
    /** 3x3 matrix values: [scaleX, skewX, transX, skewY, scaleY, transY, 0, 0, 1]. */
    val m: List<Float>,
    override val z: Int,
    val colorArgb: Int = 0xFF000000.toInt(),
    val fontPt: Float = 14f,
    val bgArgb: Int = 0
) : PageObject {
    override val anchorX: Float get() = m.getOrElse(2) { 0f }
    override val anchorY: Float get() = m.getOrElse(5) { 0f }
}

@Serializable
@SerialName("image")
data class ImageObj(
    override val id: String,
    val assetId: String,
    val m: List<Float>,
    override val z: Int,
    val widthPt: Float,
    val heightPt: Float
) : PageObject {
    override val anchorX: Float get() = m.getOrElse(2) { 0f }
    override val anchorY: Float get() = m.getOrElse(5) { 0f }
}

@Serializable
sealed interface LinkTarget {
    @Serializable
    @SerialName("page")
    data class PageTarget(val pageId: String) : LinkTarget

    @Serializable
    @SerialName("url")
    data class UrlTarget(val url: String) : LinkTarget
}

/** A tappable link attached to a point on the page (on text, shapes or images). */
@Serializable
@SerialName("link")
data class LinkObj(
    override val id: String,
    val target: LinkTarget,
    override val anchorX: Float,
    override val anchorY: Float,
    override val z: Int,
    val label: String = ""
) : PageObject

/** Identity matrix values for new objects. */
fun identityMatrix(): List<Float> = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
