package com.example

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Exercises real system IME insets; run with a software keyboard enabled. */
@RunWith(AndroidJUnit4::class)
class ChatKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun composerMeetsKeyboardAndNavigationReturnsAfterDismissal() {
        compose.onNodeWithTag("chat_input_field").performClick()
        val decor = compose.activity.window.decorView
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        assertComposerAboveKeyboard()
        compose.onNodeWithTag("chat_input_field").performTextInput("First line\nSecond line\nThird line")
        compose.waitForIdle()
        assertComposerAboveKeyboard()
        screenshot("keyboard-gap-fixed.png")
        compose.runOnUiThread {
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(decor.windowToken, 0)
        }
        compose.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        // Chat-first navigation has no permanent bottom bar.
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("chat_input_field").assertIsDisplayed()
        screenshot("chat-theme.png")
    }

    private fun assertComposerAboveKeyboard() {
        val decor = compose.activity.window.decorView
        val ime = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val bottom = compose.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInWindow.bottom
        val gap = decor.height - ime - bottom
        assertTrue("Composer gap/overlap = $gap px", abs(gap) <= 3 * decor.resources.displayMetrics.density)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.filesDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
