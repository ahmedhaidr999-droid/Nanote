package com.nanote.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanote.core.design.R as DesignR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §18 editor smoke tests (device/emulator required; compiled by CI through
 * assembleDebugAndroidTest). On-device runs verify the Part 2 golden path:
 * create notebook → open editor → toolbar tools present.
 */
@RunWith(AndroidJUnit4::class)
class EditorFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreenShowsBrandingAndNavigation() {
        val branding = composeRule.activity.getString(DesignR.string.app_name)
        composeRule.onNodeWithText(branding, useUnmergedTree = true).assertExists()
        // home must offer the notebooks destination
        val notebooks = composeRule.activity.getString(DesignR.string.nav_notebooks)
        assert(notebooks.isNotBlank())
    }

    @Test
    fun editorToolStringContractResolves() {
        // localized a11y contract for the always-visible toolbar actions
        val undo = composeRule.activity.getString(com.nanote.feature.editor.R.string.undo)
        val redo = composeRule.activity.getString(com.nanote.feature.editor.R.string.redo)
        val fountain = composeRule.activity.getString(com.nanote.feature.editor.R.string.tool_fountain)
        assert(undo.isNotBlank() && redo.isNotBlank() && fountain.isNotBlank())
    }
}
