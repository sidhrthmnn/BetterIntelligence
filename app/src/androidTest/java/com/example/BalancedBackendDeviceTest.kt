package com.example

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.Modality
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real decode measurements and metadata checks; unsupported/skipped is explicit. */
class BalancedBackendDeviceTest {
    @Test fun measuredBackendsReuseContextAndReportRealCapabilities(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("balancedBackends") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val engine = InferenceEngine(context)
        val report = JSONObject().put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
            .put("runtime", SmartBackendSelector.LITERT_RUNTIME).put("experimentalMtpDefault", false)
        val rows = JSONArray(); report.put("models", rows)
        val output = File(context.filesDir, "balanced-backend-device.json")
        try {
            val catalog = ModelLibrary.read(context)
            for (name in listOf("gemma-4-E2B-it.litertlm", "gemma-4-E4B-it.litertlm", "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm")) {
                engine.unloadActiveModel()
                val model = catalog.single { it.filename == name }
                val file = File(context.filesDir, "models/$name")
                val row = JSONObject().put("model", name).put("sha256", model.sha256Checksum); rows.put(row)
                if (!file.isFile) { row.put("skipped", "Model not staged"); continue }
                ModelInfo.from(file.absolutePath).use { info ->
                    check(info is ModelInfo.Llm)
                    row.put("declaredBackends", info.supportedBackends(Modality.TEXT).toString())
                        .put("maximumContext", info.maxContextTokens()).put("dynamicContext", info.isDynamicContext())
                        .put("mtpDrafter", info.hasSpeculativeDecodingSupport())
                }
                val profile = HardwareAnalyzer.analyze(context)
                row.put("availableMb", profile.availableRamMb).put("safeBudgetMb", profile.safeModelRamLimitMb)
                val reason = ModelLibrary.incompatibilityReason(model.copy(isInstalled = true), profile, 1024)
                if (reason != null) { row.put("skipped", reason); output.writeText(report.toString(2)); continue }
                engine.setProcessingMode(ProcessingMode.CPU)
                engine.loadLiteRt(file, model.name, model.sha256Checksum.orEmpty(), initialContext = 1024).getOrThrow()
                val samples = JSONArray(); row.put("samples", samples)
                for (size in listOf(1024, 2048).map { ModelLibrary.configuredContext(model, it) }.distinct()) for (mode in listOf(ProcessingMode.CPU, ProcessingMode.AUTO, ProcessingMode.GPU)) {
                    val state = engine.hardware.resources()
                    if (state.lowMemory || state.thermal >= 3 || state.availableMb < 512) {
                        samples.put(JSONObject().put("context", size).put("requested", mode.name).put("skipped", "Memory or thermal guard")); continue
                    }
                    if (mode == ProcessingMode.GPU && !engine.hardware.capabilities.value.gpuAvailable) {
                        samples.put(JSONObject().put("context", size).put("requested", mode.name).put("skipped", engine.hardware.capabilities.value.gpuReason)
                            .put("decision", engine.telemetry.value.backendDecision))
                        continue
                    }
                    engine.setProcessingMode(mode)
                    repeat(2) {
                        val start = SystemClock.elapsedRealtime()
                        val text = engine.generateSync("What is 17 multiplied by 6? Give only the number.",
                            GenerationParams(contextSize = size, maxTokens = 16, temperature = 0f, threads = 4))
                        // Backend correctness, not a model's formatting score.
                        // Smaller models may explain the arithmetic before 102.
                        assertTrue(text, Regex("\\b102\\b").containsMatchIn(text))
                        samples.put(JSONObject().put("context", size).put("requested", mode.name).put("repeat", it)
                            .put("text", text).put("wallMs", SystemClock.elapsedRealtime() - start)
                            .put("telemetry", JSONObject(engine.telemetry.value.toJson())))
                        output.writeText(report.toString(2))
                    }
                }
                // MTP is tested only with a real packaged drafter and ample RAM.
                engine.unloadActiveModel()
                val headroom = HardwareAnalyzer.analyze(context)
                val need = ModelLibrary.memoryEstimate(model, 1024, Int.MAX_VALUE).totalMb + 1024
                if (row.optBoolean("mtpDrafter") && need < headroom.safeModelRamLimitMb &&
                    InstrumentationRegistry.getArguments().getString("experimentalMtp") == "true") {
                    val mtpRows = JSONArray(); row.put("mtpSamples", mtpRows)
                    LiteRtSession(context, file, true, model.sha256Checksum.orEmpty(), experimentalMtp = true).use { session ->
                        session.prepare(ProcessingMode.GPU, 4, 1024)
                        for (prompt in listOf("What is 17 multiplied by 6? Give only the number.", "What is 6 multiplied by 7? Give only the number.")) {
                            val result = session.generate(prompt, GenerationParams(contextSize = 1024, maxTokens = 16, temperature = 0f))
                            assertEquals(if (prompt.contains("17")) "102" else "42", result.text.trim())
                            mtpRows.put(JSONObject().put("text", result.text).put("used", session.lastMtpUsed)
                                .put("firstTokenMs", result.firstTokenMs).put("tokensPerSecond", result.tokensPerSecond))
                        }
                    }
                } else row.put("mtpValidation", "Not executed: drafter, explicit opt-in and additional shared-memory headroom all required. Default remains disabled.")
                output.writeText(report.toString(2))
            }
        } finally { engine.unloadActiveModel(); output.writeText(report.toString(2)) }
    }
}
