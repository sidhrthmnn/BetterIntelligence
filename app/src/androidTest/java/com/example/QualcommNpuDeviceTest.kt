package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.GenerationParams
import com.example.engine.InferenceEngine
import com.example.engine.ProcessingMode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Real Qualcomm-only test. Requires explicitly staged, licensed matching weights. */
@RunWith(AndroidJUnit4::class)
class QualcommNpuDeviceTest {
    @Test fun compiledModelRunsThroughQnnAndUnloadsCleanly() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testQualcommNpu") == "true")
        assertTrue("Build with -PbiQualcommNpu=true", BuildConfig.QUALCOMM_NPU_ENABLED)
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val directory = File(context.filesDir, "models").canonicalFile
        val model = File(directory, requireNotNull(args.getString("npuModel"))).canonicalFile
        check(model.parentFile == directory && model.isFile && model.extension == "litertlm")
        val hash = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var size = input.read(buffer)
            while (size >= 0) { hash.update(buffer, 0, size); size = input.read(buffer) }
        }
        val sha = hash.digest().joinToString("") { "%02x".format(it) }
        assertEquals("Pass the immutable publisher checksum", args.getString("npuSha256"), sha)
        val engine = InferenceEngine(context)
        val report = JSONObject().put("modelSha256", sha).put("runtime", com.example.engine.QualcommNpuSupport.RUNTIME)
        try {
            engine.loadLiteRt(model, identity = sha, initialContext = 1280).getOrThrow()
            val params = GenerationParams(temperature = 0f, maxTokens = 16, contextSize = 1280)
            // AUTO validates both short and >128-token prefill before caching.
            assertEquals("102", engine.generateSync("What is 17 multiplied by 6? Give only the number.", params).trim().removeSuffix("."))
            assertTrue(engine.hardware.capabilities.value.npuAvailable)
            engine.setProcessingMode(ProcessingMode.NPU)
            repeat(3) {
                assertEquals("102", engine.generateSync("What is 17 multiplied by 6? Give only the number.", params).trim().removeSuffix("."))
                assertEquals("NPU", engine.telemetry.value.activeBackend)
            }
            report.put("telemetry", JSONObject(engine.telemetry.value.toJson())).put("status", "passed")
        } finally {
            engine.unloadActiveModel()
            assertFalse(engine.isReady())
            File(context.filesDir, "qualcomm-npu-validation.json").writeText(report.toString(2))
        }
    }
}
