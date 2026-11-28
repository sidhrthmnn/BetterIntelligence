package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.tasks.TaskAccess
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ModelCollectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun installedModelsAndConnectionScopesStaySeparate() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("collectionUi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        repository.awaitModelInitialization()
        val models = repository.allModels.first()
        val installed = models.filter { it.isInstalled }
        assertTrue(installed.size >= 2)
        val one = installed.first()
        val available = models.first { !it.isInstalled && it.filename.startsWith("qwen3_0.6b") }
        compose.onNodeWithTag("model_source_my_models").performClick().assertIsSelected()
        compose.onNodeWithTag("models_list").performScrollToNode(hasTestTag("model_card_${one.id}"))
        compose.onNodeWithTag("model_card_${one.id}").assertIsDisplayed()
        compose.onNodeWithTag("model_card_${available.id}").assertDoesNotExist()
        compose.onNodeWithTag("model_source_library").performClick()
        compose.onNodeWithTag("models_list").performScrollToNode(hasTestTag("model_card_${available.id}"))
        compose.onNodeWithTag("model_card_${available.id}").assertIsDisplayed()
        compose.onNodeWithTag("model_source_downloads").performClick()
        compose.onNodeWithText("No downloads yet").assertIsDisplayed()

        val pkg = "com.example.betterclient.selectionfixture"
        repository.registerClient(pkg)
        try {
            compose.onNodeWithTag("nav_CONNECT").performClick()
            compose.onNodeWithTag("connect_list").performScrollToNode(hasTestTag("allow_app_$pkg"))
            compose.onNodeWithTag("allow_app_$pkg").performClick()
            compose.onNodeWithTag("approve_connection").assertIsNotEnabled()
            compose.onNodeWithTag("grant_TEXT").assertIsOff()
            compose.onNodeWithTag("grant_VISION").assertIsOff()
            compose.onNodeWithTag("grant_TEXT").performClick()
            compose.onNodeWithTag("approve_connection").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("allow_app_$pkg") and isOn()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("READY", repository.taskAccessStatus(pkg, TaskAccess.TEXT))
            assertEquals("DENIED", repository.taskAccessStatus(pkg, TaskAccess.VISION, intensive = true))
            assertEquals("DENIED", repository.taskAccessStatus(pkg, TaskAccess.DOWNLOAD))
            assertFalse(repository.checkSecurityAndRateLimit(pkg, "READ_MEMORY").first)
        } finally {
            repository.deletePolicy(pkg)
        }
    }
}
