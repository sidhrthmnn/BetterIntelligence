package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.service.DownloadQueueStore
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated UI/preferences only: does not download or delete user models. */
class DownloadPreferencesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun oneWifiSwitchPersistsAcrossNavigationAndRecreation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("downloadPreferences") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation"))
        val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
        DownloadQueueStore(context).setWifiOnly(true)
        compose.runOnUiThread { vm.selectTab(AppTab.SETTINGS) }
        fun openPreferences() {
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_Models_Downloads"))
            compose.onNodeWithTag("setting_Models_Downloads").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("pref_wifi_only"))
        }
        openPreferences()
        compose.onAllNodesWithTag("pref_wifi_only").assertCountEquals(1)
        compose.onNodeWithTag("pref_allow_mobile_data").assertDoesNotExist()
        compose.onNodeWithText("Allow mobile data").assertDoesNotExist()
        compose.onNodeWithTag("pref_wifi_only").assertIsOn().performClick().assertIsOff()
        assertFalse(DownloadQueueStore(context).wifiOnly())
        compose.runOnUiThread { vm.selectTab(AppTab.CONNECT) }
        compose.waitForIdle()
        compose.runOnUiThread { vm.selectTab(AppTab.SETTINGS) }
        openPreferences()
        compose.onNodeWithTag("pref_wifi_only").assertIsOff()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        val recreatedVm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
        compose.runOnUiThread { recreatedVm.selectTab(AppTab.SETTINGS) }
        // DetailDisclosure expansion is saveable; open only if it was collapsed.
        if (compose.onAllNodesWithTag("pref_wifi_only").fetchSemanticsNodes().isEmpty()) openPreferences()
        compose.onNodeWithTag("pref_wifi_only").assertIsOff()
        compose.onNodeWithText("Turn off to download using mobile data or Wi-Fi.").assertExists()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "download-preferences.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        compose.onNodeWithTag("pref_wifi_only").performClick().assertIsOn()
        assertTrue(DownloadQueueStore(context).wifiOnly())
    }
}
