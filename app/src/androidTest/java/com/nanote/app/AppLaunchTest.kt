package com.nanote.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke tests (require a connected device/emulator; compiled and
 * verified by CI through assembleDebugAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun packageNameIsCorrect() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.nanote.app.debug", appContext.packageName)
    }

    @Test
    fun homeScreenShowsBranding() {
        val branding = composeRule.activity.getString(
            com.nanote.core.design.R.string.app_name
        )
        composeRule.onNodeWithText(branding).assertExists()
    }
}
