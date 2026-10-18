package com.example

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
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
        require(phase in setOf("probe", "polish", "cancel", "busy", "approval"))
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
            val seen = mutableSetOf<AccessibilityNodeInfo>()
            val deadline = SystemClock.elapsedRealtime() + 1500
            fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (seen.size >= 512 || SystemClock.elapsedRealtime() >= deadline || !seen.add(node)) return null
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
        fun key(code: Int, meta: Int = 0) {
            val now = SystemClock.uptimeMillis()
            assertTrue(automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta), true))
            assertTrue(automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta), true))
        }
        fun actionable(node: AccessibilityNodeInfo): Boolean {
            var parent: AccessibilityNodeInfo? = node
            val deadline = SystemClock.elapsedRealtime() + 1000
            repeat(3) {
                if (SystemClock.elapsedRealtime() >= deadline) return false
                parent?.let { if (it.isVisibleToUser && it.isEnabled && it.isClickable) return true }
                parent = parent?.parent
            }
            return false
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            shell("ime set $keyboard/com.example.TypeRightKeyboardService")
            shell("am start -n $host/.EditorActivity")
            val editor = awaitNode(host) { it.isEditable }
            // Wait for the fixture's actual window before dismissing any old
            // review. Otherwise its static title can be mistaken for a tool
            // action while Android is still attaching the new editor.
            find(keyboard) { it.contentDescription?.toString() == "Back" && actionable(it) }?.let {
                click(it)
                awaitNode(keyboard) { node -> node.contentDescription?.toString() == "Gboard Quick Tools" && actionable(node) }
            }
            val caseId = args.getString("typeRightCase") ?: "numeric"
            val (input, expected) = when (caseId) {
                "numeric" -> "i has 2 apples." to "I have 2 apples."
                "question" -> "what cab you do nos" to "What can you do now?"
                "negation" -> "Do not delete the backup." to "Do not delete the backup."
                "identifier" -> "Keep com.example.demo unchanged." to "Keep com.example.demo unchanged."
                "url" -> "Visit https://example.com at 09:15." to "Visit https://example.com at 09:15."
                "selection" -> "send the mesage now" to "message"
                "long" -> ("i has 2 apples and i is waiting for the delivery. ".repeat(20).trim()) to ""
                else -> error("Use a fixed synthetic test case")
            }
            report.put("case", caseId).put("modelVerifiedInHostUi", args.getString("modelLabel"))
            if (args.getString("offlineTypeRight") == "true") assertNull("Disable connectivity in the host before the offline fixture",
                context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
            }))
            val populated = awaitNode(host) { it.isEditable && it.text?.toString() == input }
            populated.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (caseId == "selection") {
                key(KeyEvent.KEYCODE_MOVE_END)
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                repeat(6) { key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON) }
                val selected = awaitNode(host) { it.isEditable && minOf(it.textSelectionStart, it.textSelectionEnd) == 9 && maxOf(it.textSelectionStart, it.textSelectionEnd) == 15 }
                report.put("selectedStart", minOf(selected.textSelectionStart, selected.textSelectionEnd))
                    .put("selectedEnd", maxOf(selected.textSelectionStart, selected.textSelectionEnd))
            }
            // ACTION_SET_TEXT already supplies a whole-field synthetic passage.
            // Some OEM TextViews reject accessibility selection actions; no
            // selection manipulation is needed for this whole-field check.
            report.put("selectionStart", populated.textSelectionStart).put("selectionEnd", populated.textSelectionEnd)
                .put("editorPopulated", true).put("windowTypes", JSONArray(automation.windows.map { it.type }))
            save()
            if (find(keyboard) { (it.contentDescription?.toString() == "Writing tools" || it.text?.toString() == "Writing tools") && actionable(it) } == null) {
                click(awaitNode(keyboard) { it.contentDescription?.toString() == "Gboard Quick Tools" })
            }
            val tools = awaitNode(keyboard) { (it.contentDescription?.toString() == "Writing tools" || it.text?.toString() == "Writing tools") && actionable(it) }
            report.put("input", input).put("windowTypes", JSONArray(automation.windows.map { it.type }))
                .put("imeWindowExposed", automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
                .put("writingToolsExposed", true)
            save()
            if (phase == "probe") { report.put("status", "probe_complete"); return }
            val requestedVersion = args.getString("expectedBiVersion")
            if (requestedVersion != null) assertEquals("Test the requested Play-delivered version", requestedVersion, report.getString("biVersionCode"))
            val started = SystemClock.elapsedRealtime()
            click(tools)
            if (phase == "cancel") {
                click(awaitNode(keyboard) { it.contentDescription?.toString() == "Back" })
                SystemClock.sleep(1000)
                assertEquals(input, awaitNode(host) { it.isEditable }.text.toString())
                report.put("status", "cancel_kept_editor"); return
            }
            if (phase == "busy" || phase == "approval") {
                val message = awaitNode(keyboard) { n -> n.text?.toString()?.let { text ->
                    if (phase == "busy") text.contains("busy", true) else text.contains("allow TypeRight", true)
                } == true }
                assertEquals(input, awaitNode(host) { it.isEditable }.text.toString())
                report.put("connectionMessage", message.text.toString()).put("status", "${phase}_kept_editor"); return
            }
            val deadline = SystemClock.elapsedRealtime() + 190_000
            var ready: AccessibilityNodeInfo? = null
            while (ready == null && SystemClock.elapsedRealtime() < deadline) {
                // Compose merges the proposal and status into one accessible
                // card on some devices; do not require a standalone Text node.
                ready = find(keyboard) { it.text?.toString()?.contains(expected) == true }
                val noChange = find(keyboard) { it.text?.toString()?.contains("No changes suggested") == true }
                if (ready == null && expected != input && noChange != null) {
                    report.put("observedReview", noChange.text.toString())
                    error("Installed keyboard suggested no correction for this fixed erroneous input")
                }
                if (ready == null) SystemClock.sleep(50)
            }
            val preview = checkNotNull(ready) { "No expected review within the bounded deadline" }
            report.put("reviewReadyMs", SystemClock.elapsedRealtime() - started).put("uiPollIntervalMs", 50)
            assertEquals("Review must not change the editor", input, awaitNode(host) { it.isEditable }.text.toString())
            assertNotNull("Installed keyboard must identify its selected local provider",
                find(keyboard) { it.text?.toString()?.startsWith("Better Intelligence") == true })
            if (expected == input) {
                report.put("output", expected).put("status", "unchanged_review_complete"); return
            }
            click(preview)
            val wholeOutput = if (caseId == "selection") "send the message now" else expected
            awaitNode(host) { it.isEditable && it.text?.toString() == wholeOutput }
            report.put("output", wholeOutput).put("reviewBeforeInsertion", true).put("status", "polish_insert_complete")
            if (args.getString("typeRightUndo") == "true") {
                if (find(keyboard) { it.contentDescription?.toString() == "Undo" } == null) {
                    click(awaitNode(keyboard) { it.contentDescription?.toString() == "Gboard Quick Tools" })
                    click(awaitNode(keyboard) { it.text?.toString() == "Text Editing" || it.contentDescription?.toString() == "Text Editing" })
                }
                click(awaitNode(keyboard) { it.contentDescription?.toString() == "Undo" })
                awaitNode(host) { it.isEditable && it.text?.toString() == input }
                report.put("undoRestoredOriginal", true)
            }
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString()); throw failure
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            shell("ime set $previousIme")
            save()
        }
    }
}
