package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Only the isolated validation app. No model downloads, deletion, app approvals
 * or changes to production chat data. Covers all ten navigation destinations. */
class SimpleUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun allScreensHaveReadableCardsAndWorkingDetailsInBothThemes(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("simpleUi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
        val repository = AiCoreRepository.getInstance(context)
        compose.waitUntil(60_000) { vm.allModels.value.isNotEmpty() }
        repository.unloadModel()
        val previous = vm.appearance.value
        val screens = listOf(AppTab.CHAT to "chat_input_field", AppTab.MODELS to "models_list", AppTab.CONNECT to "connect_list",
            AppTab.SETTINGS to "settings_list", AppTab.HISTORY to "history_list", AppTab.APPEARANCE to "appearance_LIGHT",
            AppTab.WEB to "web_settings_list", AppTab.MEMORY to "memory_list", AppTab.PRIVACY to "privacy_list", AppTab.ADVANCED to "advanced_list",
            AppTab.OPTIMISE to "automatic_setup_list")
        try {
            for (theme in listOf("LIGHT", "AMOLED")) {
                compose.runOnUiThread { vm.setAppearance(theme) }
                compose.waitUntil(15_000) { vm.appearance.value == theme }
                for ((screen, tag) in screens) {
                    compose.runOnUiThread { vm.selectTab(screen) }
                    compose.waitUntil(15_000) { vm.currentTab.value == screen }
                    compose.waitForIdle()
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                    capture("${theme.lowercase()}-${screen.name.lowercase()}.png")
                    if (screen == AppTab.WEB) {
                        compose.onNodeWithTag("research_details").performClick()
                        compose.onNodeWithTag("web_settings_list").performScrollToNode(hasTestTag("view_research_diagnostics"))
                        compose.onNodeWithTag("view_research_diagnostics").assertIsDisplayed()
                    }
                }
            }
        } finally { compose.runOnUiThread { vm.setAppearance(previous); vm.selectTab(AppTab.CHAT) } }
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.filesDir, "simple-ui").apply { mkdirs() }
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
