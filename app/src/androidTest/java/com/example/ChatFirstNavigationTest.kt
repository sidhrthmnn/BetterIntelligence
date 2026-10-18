package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Product navigation and screenshot coverage without loading weights or changing production data. */
@RunWith(AndroidJUnit4::class)
class ChatFirstNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun chatFirstDraftSurvivesNavigationAndThreeThemesCoverSettingsDestinations() {
        require(compose.activity.packageName.endsWith(".validation"))
        compose.onNodeWithTag("chat_input_field").assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("chat_input_field").performTextReplacement("Synthetic unsent draft: keep this text.")
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("setting_Models").performClick()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.onNodeWithTag("chat_input_field").assertTextContains("Synthetic unsent draft: keep this text.")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("chat_input_field").assertTextContains("Synthetic unsent draft: keep this text.")
        compose.onNodeWithTag("chat_input_field").performTextClearance()
        for (theme in listOf("LIGHT", "DARK", "AMOLED")) {
            compose.onNodeWithTag("open_settings").performClick()
            openSetting("Appearance")
            compose.onNodeWithTag("appearance_$theme").performClick()
            compose.waitForIdle()
            screenshot("appearance-$theme.png")
            compose.onNodeWithTag("navigate_back").performClick()
            screenshot("settings-$theme.png")
            for ((destination, filename) in listOf("Models" to "models", "Connected apps" to "connected", "Web & grounding" to "web")) {
                openSetting(destination)
                compose.waitForIdle(); screenshot("$filename-$theme.png")
                compose.onNodeWithTag("navigate_back").performClick()
            }
            compose.onNodeWithTag("navigate_back").performClick()
            compose.waitForIdle(); screenshot("chat-$theme.png")
            compose.onNodeWithTag("open_history").performClick()
            compose.waitForIdle(); screenshot("history-$theme.png")
            compose.onNodeWithTag("navigate_back").performClick()
        }
    }
    private fun openSetting(title: String) {
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_$title"))
        compose.onNodeWithTag("setting_$title").performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350) // Allow the Surface frame to reach the system screenshot compositor.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = java.io.File(instrumentation.targetContext.filesDir, "ui-2.0").apply { mkdirs() }
        java.io.File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
