package com.nanote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nanote.ink.engine.EditorTool
import com.nanote.ink.engine.ToolSpec
import com.nanote.ui.editor.ToolPalette
import com.nanote.ui.theme.NanoteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI test (spec 9): the tool palette renders every tool with a
 * content description (TalkBack) and taps switch tools; the palette reorders.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ToolPaletteUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun paletteShowsAllTools_withContentDescriptions() {
        val current = androidx.compose.runtime.mutableStateOf(ToolSpec())
        compose.setContent {
            NanoteTheme {
                // Force a tablet-wide layout so the LazyRow composes ALL 8
                // tool chips (Robolectric's default window would clip the last
                // chip, and LazyRow only composes visible items).
                Box(Modifier.requiredWidth(1100.dp)) {
                    ToolPalette(
                        tool = current.value,
                        fingersDraw = true,
                        onTool = { t -> current.value = ToolSpec.withTool(current.value, t) },
                        onPen = { p -> current.value = ToolSpec.withPen(current.value, p) },
                        onColor = { c -> current.value = ToolSpec.withColor(current.value, c) },
                        onWidth = { w -> current.value = ToolSpec.withWidth(current.value, w) },
                        onFingersDraw = { },
                        onShapeKind = { k -> current.value = current.value.copy(shapeKind = k) }
                    )
                }
            }
        }

        // TalkBack descriptions exist for every tool (highlighter separated in v1.1)
        for (label in listOf(
            "Pen", "Highlighter", "Eraser", "Lasso", "Shapes", "Text", "Image", "Laser"
        )) {
            compose.onNodeWithContentDescription(label).assertExists()
        }

        // tapping the lasso chip switches the active tool
        compose.onNodeWithContentDescription("Lasso").performClick()
        compose.runOnIdle { }
        assert(current.value.tool == EditorTool.LASSO) {
            "Expected LASSO after tap, was ${current.value.tool}"
        }

        // v1.1: the separated highlighter tool selects and switches back cleanly
        compose.onNodeWithContentDescription("Highlighter").performClick()
        compose.runOnIdle { }
        assert(current.value.tool == EditorTool.HIGHLIGHTER) {
            "Expected HIGHLIGHTER after tap, was ${current.value.tool}"
        }
        assert(current.value.pen == com.nanote.ink.engine.PenType.HIGHLIGHTER) {
            "Expected HIGHLIGHTER pen engine, was ${current.value.pen}"
        }
        compose.onNodeWithContentDescription("Pen").performClick()
        compose.runOnIdle { }
        assert(current.value.tool == EditorTool.PEN) {
            "Expected PEN after tap, was ${current.value.tool}"
        }
        assert(current.value.pen != com.nanote.ink.engine.PenType.HIGHLIGHTER)
    }
}
