package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Isolated known-public probes. No production availability flag is overridden. */
class LiteRtGpuProbeDeviceTest {
    @Test fun compareActualCpuGpuProbeOutputs(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectGpuProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val model = ModelLibrary.read(context).single { it.filename == "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm" }
        val profile = HardwareAnalyzer.analyze(context)
        check(ModelLibrary.memoryEstimate(model, 1024, Int.MAX_VALUE).totalMb <= profile.safeModelRamLimitMb)
        val rows = JSONArray()
        val output = File(context.filesDir, "litert-gpu-probe.json")
        try {
            LiteRtSession(context, File(context.filesDir, "models/${model.filename}"), true, model.sha256Checksum.orEmpty()).use { session ->
                for (mode in listOf(ProcessingMode.CPU, ProcessingMode.GPU)) {
                    session.prepare(mode, 4, 1024)
                    for (prompt in listOf("Count from one to fifty, separated by commas.",
                        "What is 17 multiplied by 6? Give only the number.", "What is 23 plus 19? Give only the number.", "What is 9 multiplied by 7? Give only the number.")) {
                        val result = session.generate(prompt, GenerationParams(contextSize = 1024, maxTokens = 32, temperature = 0f, topK = 1, topP = 1f))
                        rows.put(JSONObject().put("backend", mode.name).put("prompt", prompt).put("text", result.text)
                            .put("tokens", result.outputTokens).put("tps", result.tokensPerSecond).put("ttft", result.firstTokenMs))
                        output.writeText(rows.toString(2))
                    }
                }
            }
        } finally { output.writeText(rows.toString(2)) }
    }
}
