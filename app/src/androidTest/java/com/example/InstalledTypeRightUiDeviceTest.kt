package com.example

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Controls the existing IME through Android accessibility, never its private data.
 * Requires the separate disposable editor fixture. No TypeRight APK is installed.
 * Opt-in so CI/ordinary instrumentation never changes the user's selected IME.
 */
class InstalledTypeRightUiDeviceTest {
    @Test fun installedKeyboardReviewsAndInsertsSyntheticText() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("installedTypeRightUi") ?: return
        require(phase in setOf("probe", "polish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".validation"))
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = automation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        val previousIme = shell("settings get secure default_input_method")
        require(previousIme.matches(Regex("[A-Za-z0-9._/]+")))
        val previousFlags = automation.serviceInfo.flags
        val keyboard = "com.aistudio.typerigh2.jkwpzq"
        val host = "com.example.typingtesthost"
        val report = JSONObject().put("phase", phase).put("keyboardPackage", keyboard)
            .put("keyboardVersionCode", Regex("versionCode=(\\d+)").find(shell("dumpsys package $keyboard"))?.groupValues?.get(1))
            .put("biVersionCode", Regex("versionCode=(\\d+)").find(shell("dumpsys package com.aistudio.betterintelligence.mobile"))?.groupValues?.get(1))
            .put("status", "running")
        val output = File(context.filesDir, "installed-typeright-ui-$phase.json")
        fun save() = output.writeText(report.toString(2))
        fun find(packageName: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.packageName?.toString() == packageName && predicate(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> walk(child)?.let { return it } }
                return null
            }
            return automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(::walk).firstOrNull()
        }
        fun awaitNode(packageName: String, timeout: Long = 15_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val until = SystemClock.elapsedRealtime() + timeout
            do {
                find(packageName, predicate)?.let { return it }
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < until)
            error("Expected synthetic test control was not exposed by $packageName")
        }
        fun click(node: AccessibilityNodeInfo) {
            var candidate: AccessibilityNodeInfo? = node
            repeat(6) {
                candidate?.let { if (it.isClickable && it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return }
                candidate = candidate?.parent
            }
            error("Observed keyboard control was not clickable")
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            shell("ime set $keyboard/com.example.TypeRightKeyboardService")
            shell("am start -n $host/.EditorActivity")
            val editor = awaitNode(host) { it.isEditable }
            val input = "i has 2 apples."
            val expected = "I have 2 apples."
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
            }))
            val populated = awaitNode(host) { it.isEditable && it.text?.toString() == input }
            populated.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            assertTrue(populated.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, input.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, input.length)
            }))
            val tools = awaitNode(keyboard) { it.contentDescription?.toString() == "Writing tools" }
            report.put("input", input).put("windowTypes", JSONArray(automation.windows.map { it.type }))
                .put("imeWindowExposed", automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
                .put("writingToolsExposed", true)
            save()
            if (phase == "probe") { report.put("status", "probe_complete"); return }
            val requestedVersion = args.getString("expectedBiVersion")
            if (requestedVersion != null) assertEquals("Test the requested Play-delivered version", requestedVersion, report.getString("biVersionCode"))
            val started = SystemClock.elapsedRealtime()
            click(tools)
            val preview = awaitNode(keyboard, 190_000) { it.text?.toString() == expected }
            report.put("reviewReadyMs", SystemClock.elapsedRealtime() - started).put("uiPollIntervalMs", 50)
            assertEquals("Review must not change the editor", input, awaitNode(host) { it.isEditable }.text.toString())
            assertNotNull("Installed keyboard must identify its selected local provider",
                find(keyboard) { it.text?.toString()?.startsWith("Better Intelligence") == true })
            click(preview)
            awaitNode(host) { it.isEditable && it.text?.toString() == expected }
            report.put("output", expected).put("reviewBeforeInsertion", true).put("status", "polish_insert_complete")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString()); throw failure
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            shell("ime set $previousIme")
            save()
        }
    }
}
