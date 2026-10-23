package com.example

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import com.example.grounding.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in, synthetic prompts only, isolated validation package. Never reads the
 * installed Play application's chat data or modifies its selected model.
 */
class ChatReliabilityDeviceTest {
    @Test fun realGemmaStreamingHistoryAndCancellation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("chatReliability") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val filename = args.getString("reliabilityModel") ?: "gemma-4-E2B-it.litertlm"
        val mode = ProcessingMode.valueOf(args.getString("reliabilityBackend") ?: "CPU")
        val file = File(context.filesDir, "models/$filename")
        require(file.name == filename && file.isFile)
        val identity = if (filename.contains("E2B")) "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
            else "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val buffer = ByteArray(1 shl 20); while (true) {
            val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
        } }
        assertEquals(identity, hash.digest().joinToString("") { "%02x".format(it) })
        val samples = JSONArray()
        val report = JSONObject().put("device", Build.FINGERPRINT).put("model", filename).put("sha256", identity)
            .put("backend", mode.name).put("runtime", "0.18.0").put("activationDataType", if (mode == ProcessingMode.GPU) "FLOAT32" else "DEFAULT_CPU")
            .put("samples", samples)
        val output = File(context.filesDir, "reliability-${file.nameWithoutExtension}-${mode.name}.json")
        val session = LiteRtSession(context, file, true, identity)
        try {
            session.prepare(mode, 4, 2048)
            val prompts = listOf("What is 17 multiplied by 6? Give only the number.",
                "What is the weather today?", "Explain the literal token `<unused15>` in one sentence.",
                "What is the name of my game?")
            repeat(2) { round -> prompts.forEachIndexed { index, prompt ->
                val streamed = StringBuilder()
                val history = if (index == 3) listOf(ChatTurn("user", "I'm making a game called Colour Circle."), ChatTurn("assistant", "We can discuss Colour Circle.")) else emptyList()
                val result = session.generate(prompt, GenerationParams(contextSize = 2048, maxTokens = 128,
                    temperature = 0f, topK = 1, topP = 1f, threads = 4, history = history,
                    systemPrompt = "You are a helpful assistant. Do not invent live weather or location. Ask for a city when needed.")) { delta, _ -> streamed.append(delta) }
                samples.put(JSONObject().put("round", round).put("prompt", prompt).put("answer", result.text)
                    .put("firstTokenMs", result.firstTokenMs).put("tokensPerSecond", result.tokensPerSecond).put("inputTokens", result.inputTokens)
                    .put("outputTokens", result.outputTokens).put("initializationMs", session.initializationMs))
                output.writeText(report.toString(2))
                ModelOutputValidator.validate(result.text)
                assertTrue(result.text.isNotBlank()); assertEquals(result.text, streamed.toString())
                if (index == 0) assertEquals("102", result.text.trim())
                if (index == 3) assertTrue(result.text.contains("Colour Circle", true))
            } }
            val first = CompletableDeferred<Unit>()
            val cancelled = launch { session.generate("Count from one to a thousand.", GenerationParams(contextSize = 2048, maxTokens = 512)) { _, _ -> first.complete(Unit) } }
            withTimeout(120_000) { first.await() }; cancelled.cancelAndJoin()
            // Reset after cancellation must complete before reopening the native context.
            val restored = session.generate("What is 17 multiplied by 6? Give only the number.", GenerationParams(contextSize = 2048, maxTokens = 16, temperature = 0f, topK = 1))
            assertEquals("102", restored.text.trim())
            report.put("cancellationAndRecovery", true)
        } finally { session.close(); report.put("released", !session.isInitialized); output.writeText(report.toString(2)) }
    }

    @Test fun publicWeatherAndLocationClarification() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("weatherNetwork") != "true") return@runBlocking
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to PublicWebResearch()))
        assertEquals("NEEDS_LOCATION", coordinator.retrieve("What is the weather today?", true, GroundingMode.AUTO, SearchProviderId.WEB).status)
        val weather = coordinator.retrieve("What is the weather in London today?", true, GroundingMode.AUTO, SearchProviderId.WEB)
        assertEquals(weather.notice, "STRUCTURED_ANSWER", weather.status)
        assertEquals("STRUCTURED_DATA", weather.sources.single().kind)
        assertTrue(weather.notice.contains("Open-Meteo")); assertTrue(weather.notice.contains("[1]"))
    }
}
