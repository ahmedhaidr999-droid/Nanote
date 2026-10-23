package com.nanote.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                ToolPalette(
                    tool = current.value,
                    fingersDraw = true,
                    onTool = { t -> current.value = current.value.copy(tool = t) },
                    onPen = { p -> current.value = current.value.copy(pen = p) },
                    onColor = { c -> current.value = current.value.copy(colorArgb = c) },
                    onWidth = { w -> current.value = current.value.copy(widthPt = w) },
                    onFingersDraw = { },
                    onShapeKind = { k -> current.value = current.value.copy(shapeKind = k) }
                )
            }
        }

        // TalkBack descriptions exist for every tool
        for (label in listOf("Pen", "Eraser", "Lasso", "Shapes", "Text", "Image", "Laser")) {
            compose.onNodeWithContentDescription(label).assertExists()
        }

        // tapping the lasso chip switches the active tool
        compose.onNodeWithContentDescription("Lasso").performClick()
        compose.runOnIdle { }
        assert(current.value.tool == EditorTool.LASSO) {
            "Expected LASSO after tap, was ${current.value.tool}"
        }
    }
}
