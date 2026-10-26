package com.example

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import com.example.grounding.*
import com.example.data.ChatMessageEntity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import kotlin.concurrent.thread

/** Explicit opt-in: real public-page retrieval and the same staged model in
 * alternating OFF/ON order. References/grading live outside model inputs. */
@RunWith(AndroidJUnit4::class)
class WebOnOffDeviceTest {
    @Test fun pairedLiveEvaluation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("pairedResearch") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".validation"))
        val name = arguments.getString("pairedModel") ?: "gemma-4-E2B-it.litertlm"
        require(File(name).name == name)
        val file = File(context.filesDir, "models/$name")
        require(file.isFile)
        val identity = arguments.getString("pairedSha256").orEmpty()
        val contextSize = 2048; val outputTokens = 160
        val repairEnabled = arguments.getString("pairedRepair") == "true"
        val engine = InferenceEngine(context)
        val report = JSONObject().put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("fingerprint", Build.FINGERPRINT).put("model", name).put("sha256", identity)
            .put("requestedBackend", "CPU").put("context", contextSize).put("maxOutputTokens", outputTokens)
            .put("temperature", 0).put("threads", 4).put("repeats", 1).put("startedAt", System.currentTimeMillis()).put("repairEnabled", repairEnabled)
            .put("limitations", "One alternating run; network uncontrolled; charging; semantic quality independently reviewed. Direct public URLs are identical in OFF/ON questions. " +
                if (repairEnabled) "Supplemental correction experiment: up to 96 additional output tokens and eight seconds; not the controlled primary ON/OFF comparison." else "No correction pass in this primary comparison.")
        val rows = JSONArray(); report.put("results", rows)
        val output = File(context.filesDir, "web-on-off-${if (repairEnabled) "repair-" else ""}$name.json")
        fun save() = output.writeText(report.toString(2))
        val tests = JSONArray(instrumentation.context.assets.open("web-on-off-cases.json").bufferedReader().use { it.readText() })
        val first = arguments.getString("pairedStart")?.toIntOrNull() ?: 1
        val last = arguments.getString("pairedEnd")?.toIntOrNull() ?: tests.length()
        val ids = arguments.getString("pairedIds")?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet()
        try {
            ModelLibrary.read(context)
            val model = ModelLibrary.inferenceModel(context, name, identity)
            report.put("beforeLoad", JSONObject().put("availableMb", engine.hardware.resources().availableMb))
            engine.setProcessingMode(ProcessingMode.CPU)
            val started = SystemClock.elapsedRealtime()
            if (name.endsWith(".gguf")) engine.loadCustomGguf(file, identity).getOrThrow()
            else engine.loadLiteRt(file, name = model?.name ?: name, identity = identity, initialContext = contextSize).getOrThrow()
            report.put("loadMs", SystemClock.elapsedRealtime() - started); save()
            for (index in 0 until tests.length()) {
                val test = tests.getJSONObject(index)
                val id = test.getInt("id")
                if (id !in first..last || ids != null && id !in ids) continue
                val question = test.getString("question")
                val history = if (!test.has("publicTopic")) emptyList() else listOf(
                    ChatMessageEntity(role = "user", content = "We are discussing LiteRT-LM.", conversationId = 1),
                    ChatMessageEntity(role = "assistant", content = "The subject is LiteRT-LM.", conversationId = 1,
                        sourcesJson = GroundingResult(sources = listOf(EvidenceSource(1, test.getString("publicTopic"), "https://developers.google.com/edge/litert-lm/android", "", 1))).json()))
                for (mode in if (index % 2 == 0) listOf(GroundingMode.OFF, GroundingMode.ON) else listOf(GroundingMode.ON, GroundingMode.OFF)) {
                    val state = engine.hardware.resources()
                    check(!state.lowMemory && state.availableMb > 512 && state.thermal < 4) { "Stop safely: memory or thermal pressure" }
                    instrumentation.sendStatus(0, Bundle().apply { putString("progress", "Case $id ${mode.name}") })
                    val calls = Collections.synchronizedList(mutableListOf<String>())
                    val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to PublicWebResearch(SearchHttp(onRequest = { calls += it }))))
                    val researchQuestion = ConversationalResearch.question(question, history)
                    val start = SystemClock.elapsedRealtime()
                    val evidence = coordinator.retrieve(researchQuestion, true, mode, SearchProviderId.WEB)
                    val retrievalMs = SystemClock.elapsedRealtime() - start
                    var raw = ""; var answer = ""; var support = evidence.status
                    var sources = emptyList<EvidenceSource>(); var inferenceMs = 0L; var failure = ""; var input = 0; var outputCount = 0
                    var telemetry = JSONObject(); var claimChecks = JSONArray(); var peakPss = Debug.getPss().toLong()
                    var repairMs = 0L; var repairAttempted = false; var repairRaw = ""; var repairOutcome = "NOT_NEEDED_OR_ELIGIBLE"
                    val monitor = AtomicBoolean(false); var sampler: Thread? = null
                    try {
                        if (evidence.status in setOf("SEARCH_FAILED", "NEEDS_LOCATION", "NEEDS_SUBJECT")) answer = evidence.notice
                        else {
                            val system = ChatAnswerPolicy.system(question, evidence.sources.isNotEmpty(), contextSize)
                            val prepared = GroundingPolicy.prepareEvidence(if (evidence.sources.isNotEmpty()) researchQuestion else question, evidence.sources, 2700,
                                contextSize - outputTokens - 96 - engine.contextTokenCount(system), engine::contextTokenCount)
                            sources = prepared.sources
                            val peak = AtomicLong(peakPss); monitor.set(true)
                            sampler = thread(name = "paired-pss") { while (monitor.get()) { peak.accumulateAndGet(Debug.getPss().toLong(), ::maxOf); Thread.sleep(250) } }
                            val inferStart = SystemClock.elapsedRealtime()
                            raw = withTimeout(90_000) { engine.generateSync(prepared.prompt, GenerationParams(temperature = 0f, topK = 1, topP = 1f,
                                contextSize = contextSize, maxTokens = outputTokens, threads = 4, systemPrompt = system,
                                history = ChatAnswerPolicy.history(history.map { ChatTurn(it.role, it.content) }, sources.isNotEmpty()))) }
                            inferenceMs = SystemClock.elapsedRealtime() - inferStart
                            telemetry = JSONObject(engine.telemetry.value.toJson()); input = engine.telemetry.value.promptTokens; outputCount = engine.telemetry.value.completionTokens
                            peakPss = peak.get()
                            if (sources.isNotEmpty()) {
                                var checked = GroundingPolicy.checkAnswer(raw, sources, researchQuestion)
                                if (repairEnabled) {
                                    val resources = engine.hardware.resources()
                                    val repair = EvidenceRepair.prompt(researchQuestion, raw, prepared, checked, contextSize,
                                        resources.availableMb, resources.lowMemory, resources.thermal,
                                        engine.telemetry.value.timeToFirstTokenMs, engine.telemetry.value.tokensPerSecond) {
                                        engine.contextTokenCount(it) + engine.contextTokenCount(system) + EvidenceRepair.maxTokens + 96 <= contextSize
                                    }
                                    if (repair != null) {
                                        repairAttempted = true; val repairStart = SystemClock.elapsedRealtime()
                                        val corrected = withTimeoutOrNull(EvidenceRepair.deadlineMs) {
                                            engine.generateSync(repair, GenerationParams(temperature = 0f, topK = 1, topP = 1f, maxTokens = EvidenceRepair.maxTokens,
                                                contextSize = contextSize, threads = 4, systemPrompt = system))
                                        }
                                        repairMs = SystemClock.elapsedRealtime() - repairStart
                                        repairOutcome = "TIMEOUT"
                                        if (corrected != null) {
                                            repairRaw = corrected
                                            val verified = GroundingPolicy.checkAnswer(corrected, sources, researchQuestion)
                                            repairOutcome = verified.support.name
                                            if (verified.support != AnswerSupport.INSUFFICIENT) checked = verified
                                        }
                                    }
                                }
                                answer = checked.text; support = checked.support.name
                                checked.claims.forEach { claimChecks.put(JSONObject().put("text", it.text).put("accepted", it.supported).put("reason", it.reason)) }
                            } else {
                                answer = ChatAnswerPolicy.finishLocal(question, raw)
                                if (answer != raw) support = "LOCAL_UNVERIFIED"
                            }
                        }
                    } catch (e: TimeoutCancellationException) { failure = "Inference timed out" }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = e.message.orEmpty().take(250) }
                    finally { monitor.set(false); sampler?.join(1500) }
                    val row = JSONObject().put("id", id).put("category", test.getString("category")).put("mode", mode.name).put("question", question)
                        .put("retrievalMs", retrievalMs).put("inferenceMs", inferenceMs).put("totalMs", SystemClock.elapsedRealtime() - start)
                        .put("httpRequests", calls.size).put("rawAnswer", raw).put("answer", answer).put("support", support).put("failure", failure)
                        .put("sources", JSONArray(GroundingResult(sources = sources).json())).put("claimChecks", claimChecks)
                        .put("inputTokens", input).put("outputTokens", outputCount).put("peakProcessPssKb", peakPss).put("telemetry", telemetry)
                        .put("beforeThermal", state.thermal).put("afterThermal", engine.hardware.resources().thermal)
                        .put("repairMs", repairMs).put("repairAttempted", repairAttempted).put("repairRaw", repairRaw).put("repairOutcome", repairOutcome)
                        .put("manualReview", "PENDING_INDEPENDENT_REFERENCE_REVIEW")
                    rows.put(row); save(); coordinator.clearTemporaryData()
                    assertTrue("Bounded HTTP budget", calls.size <= 24)
                    if (mode == GroundingMode.OFF) assertEquals("OFF must not access research network", 0, calls.size)
                }
            }
            report.put("status", "completed")
        } catch (e: Throwable) { report.put("status", "failed").put("failure", e.message.orEmpty()); throw e }
        finally {
            engine.unloadActiveModel()
            val mapped = File("/proc/self/maps").readText().contains(name)
            report.put("endedAt", System.currentTimeMillis()).put("released", !engine.isReady()).put("weightsStillMapped", mapped); save()
            assertFalse("Native model mappings must be released", mapped)
        }
    }
}
