package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModelHubDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun modelsAreHomeAndOnlyModelsAppsAndSettingsArePrimaryDestinations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
        val repo = AiCoreRepository.getInstance(context)
        val before = repo.conversations.first().size
        assertEquals(AppTab.MODELS, vm.currentTab.value)
        compose.onNodeWithTag("models_list").assertIsDisplayed()
        compose.onNodeWithTag("chat_input_field").assertDoesNotExist()
        compose.onNodeWithTag("new_chat").assertDoesNotExist()
        compose.onNodeWithTag("open_history").assertDoesNotExist()
        compose.onNodeWithTag("nav_CONNECT").performClick()
        compose.onNodeWithTag("connect_list").assertIsDisplayed()
        compose.onNodeWithTag("nav_SETTINGS").performClick()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        compose.onNodeWithText("Web & grounding").assertDoesNotExist()
        compose.onNodeWithText("Memory").assertDoesNotExist()
        compose.onNodeWithTag("nav_MODELS").performClick()
        compose.onNodeWithTag("models_list").assertIsDisplayed()
        assertEquals(before, repo.conversations.first().size)
    }
}
