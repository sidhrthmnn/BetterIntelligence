package com.example

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.NativeGguf
import com.example.engine.NativeObserver
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fresh-process Vulkan option experiment, never applied by the shipping app. */
@RunWith(AndroidJUnit4::class)
class VulkanCompatibilityDeviceTest {
    @Test fun stagedModelWithConservativeShaderFeatures() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testVulkanCompatibility") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val name = args.getString("vulkanModel") ?: "smollm2-135m-instruct-q4_k_m.gguf"
        require(File(name).name == name)
        val file = File(context.filesDir, "models/$name")
        check(file.isFile)
        val flags = args.getString("vulkanFlags")?.split(',').orEmpty().filter { it.isNotBlank() }
        val allowed = setOf("GGML_VK_DISABLE_F16", "GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "GGML_VK_DISABLE_COOPMAT", "GGML_VK_DISABLE_COOPMAT2")
        require(flags.all { it in allowed })
        flags.forEach { Os.setenv(it, "1", true) }
        val report = JSONObject().put("flags", flags.joinToString(",")).put("model", name)
        try {
            NativeGguf.ensureAvailable()
            report.put("runtime", JSONObject(NativeGguf.runtimeInfo(true).toString(Charsets.UTF_8)))
            val layers = NativeGguf.prepare(file.absolutePath.toByteArray(), 8, 4, 512)
            report.put("layers", layers)
            check(layers > 0) { "No genuine GPU layer offload" }
            val output = NativeGguf.generate("Count from one to ten.".toByteArray(), byteArrayOf(),
                NativeObserver(null, { 4 }) { _, _, _ -> }, 16, 4, 512, 0f, 1f, 1, 60_000, emptyArray(), emptyArray()).toString(Charsets.UTF_8)
            check(output.isNotBlank())
            report.put("output", output).put("status", "passed")
        } catch (failure: Exception) {
            report.put("status", "failed").put("failure", failure.message)
            throw failure
        } finally {
            NativeGguf.release()
            File(context.filesDir, "vulkan-compatibility.json").writeText(report.toString(2))
        }
    }
}
