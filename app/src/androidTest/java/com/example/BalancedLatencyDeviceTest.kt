package com.example

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Same phone/model/settings, alternating broad-web baseline and balanced AUTO.
 * Small live comparison; outages and quality gaps are results, not filtered out. */
class BalancedLatencyDeviceTest {
    @Test fun pairedLocalAndWebLatency(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("balancedLatency") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val engine = InferenceEngine(context)
        val catalog = ModelLibrary.read(context)
        val profile = HardwareAnalyzer.analyze(context)
        val model = catalog.firstOrNull { it.filename == "gemma-4-E2B-it.litertlm" && ModelLibrary.fits(it.copy(isInstalled = true), profile, 1024) }
            ?: catalog.first { it.filename.startsWith("qwen3_0.6b") && it.filename.endsWith(".litertlm") }
        val rows = JSONArray()
        val report = JSONObject().put("device", Build.MODEL).put("model", model.filename).put("backend", "CPU")
            .put("context", 1024).put("maxTokens", 128).put("baseline", "81253eee broad AUTO, equivalent to forced research for these two public questions")
            .put("limitations", "One alternating pair per question; network uncontrolled. Model outputs reviewed separately. Not general answer accuracy or a sustained thermal benchmark.")
            .put("results", rows)
        val output = File(context.filesDir, "balanced-latency-device.json")
        try {
            engine.setProcessingMode(ProcessingMode.CPU)
            engine.loadLiteRt(File(context.filesDir, "models/${model.filename}"), model.name, model.sha256Checksum.orEmpty(), initialContext = 1024).getOrThrow()
            engine.generateSync("Say ready.", GenerationParams(contextSize = 1024, maxTokens = 8, temperature = 0f, threads = 4))
            for ((index, q) in listOf("Explain recursion in one sentence.", "What is the capital of France? Give only the city.").withIndex()) {
                for (legacy in if (index == 0) listOf(true, false) else listOf(false, true)) {
                    val calls = AtomicInteger()
                    val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to PublicWebResearch(SearchHttp(onRequest = { calls.incrementAndGet() }), backupAllowed = { true })))
                    val start = SystemClock.elapsedRealtime()
                    val evidence = coordinator.retrieve(q, true, if (legacy) GroundingMode.ON else GroundingMode.AUTO, SearchProviderId.WEB)
                    val retrieval = SystemClock.elapsedRealtime() - start
                    val row = JSONObject().put("question", q).put("path", if (legacy) "LEGACY_WEB" else "BALANCED_AUTO")
                        .put("retrievalMs", retrieval).put("networkRequests", calls.get())
                    rows.put(row)
                    if (evidence.status == "SEARCH_FAILED") row.put("answer", evidence.notice).put("status", evidence.status)
                    else {
                        val system = ChatAnswerPolicy.system(q, evidence.sources.isNotEmpty(), 1024)
                        val packet = GroundingPolicy.prepareEvidence(q, evidence.sources, 1600, 1024 - 128 - 96 - engine.contextTokenCount(system), engine::contextTokenCount)
                        val answer = engine.generateSync(packet.prompt, GenerationParams(contextSize = 1024, maxTokens = 128, temperature = 0f, threads = 4, systemPrompt = system))
                        val checked = if (packet.sources.isEmpty()) null else EvidenceAnswer.resolve(answer, packet.sources, q)
                        row.put("answer", checked?.text ?: answer).put("status", checked?.support?.name ?: "LOCAL")
                            .put("telemetry", JSONObject(engine.telemetry.value.toJson()))
                        if (!legacy) {
                            assertEquals(0, calls.get())
                            assertTrue(answer, if (index == 0) Regex("(?i)itself|it.self|recursive").containsMatchIn(answer) else answer.contains("Paris", true))
                        }
                    }
                    row.put("totalMs", SystemClock.elapsedRealtime() - start)
                    output.writeText(report.toString(2))
                }
            }
        } finally { engine.unloadActiveModel(); output.writeText(report.toString(2)) }
    }
}
