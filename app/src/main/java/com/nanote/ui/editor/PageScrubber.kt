package com.nanote.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nanote.R
import kotlin.math.roundToInt

/**
 * v1.3 quick page navigation indicator (user request: مؤشر انتقال سريع بين
 * الصفحات): a slim vertical scrubber hugging the edge of the canvas. Drag
 * (or tap) anywhere on it to fly between pages with ZERO animation lag — the
 * viewport tracks the finger 1:1 through [onJump]. A live bubble shows the
 * target page number while dragging; the knob always marks the current page.
 *
 * RTL-aware: the scrubber sits on the "end" edge of the canvas (right in LTR,
 * left in Arabic RTL) and the number bubble grows toward the content side.
 */
@Composable
fun PageScrubber(
    pageCount: Int,
    pageIndex: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pageCount < 2) return

    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableIntStateOf(pageIndex) }
    var trackHeightPx by remember { mutableIntStateOf(0) }

    val shownIndex = (if (dragging) dragIndex else pageIndex).coerceIn(0, pageCount - 1)
    val fraction = shownIndex / (pageCount - 1).toFloat()
    val density = LocalDensity.current
    val handlePx = with(density) { 22.dp.toPx() }
    val handleYPx = ((trackHeightPx - handlePx).coerceAtLeast(0f) * fraction).roundToInt()

    val cd = stringResource(R.string.page_scrubber)

    Box(
        modifier
            .fillMaxHeight()
            .width(40.dp)
            .onSizeChanged { trackHeightPx = it.height }
            .semantics { contentDescription = cd }
    ) {
        // invisible generous hit area (tap or drag anywhere on the column)
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(pageCount) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        fun idxAt(y: Float): Int =
                            ((y.coerceIn(0f, h) / h) * (pageCount - 1)).roundToInt()

                        var lastJumped = -1
                        fun jumpIfChanged(idx: Int) {
                            if (idx != lastJumped) {
                                lastJumped = idx
                                dragIndex = idx
                                onJump(idx)
                            }
                        }

                        dragging = true
                        jumpIfChanged(idxAt(down.position.y))
                        try {
                            drag(down.id) { change ->
                                jumpIfChanged(idxAt(change.position.y))
                            }
                        } finally {
                            dragging = false
                        }
                    }
                }
        )

        // visible track
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        )

        // knob + live page bubble, positioned at the current/ target page
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(top = with(density) { handleYPx.toDp() })
        ) {
            if (dragging) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text(
                        text = "${dragIndex + 1} / $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 9.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}
