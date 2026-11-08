package com.example

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.engine.*
import com.example.grounding.*
import com.example.ui.AiCoreViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt in with webReliability=true, in the separate validation installation.
 * Synthetic publisher facts are never represented as a real web benchmark. */
class WebReliabilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val repo get() = AiCoreRepository.getInstance(context)
    private val vm get() = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
    private val rows = JSONArray()
    private fun admit() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("webReliability") == "true")
        check(context.packageName.endsWith(".validation"))
    }
    private suspend fun fresh() {
        val before = vm.conversationId.value
        compose.runOnUiThread { vm.newConversation() }
        compose.waitUntil(15_000) { vm.conversationId.value != 0L && vm.conversationId.value != before }
    }
    private suspend fun ask(question: String): ChatMessageEntity {
        val count = repo.messages(vm.conversationId.value).first().size
        compose.onNodeWithTag("chat_input_field").performTextReplacement(question)
        compose.onNodeWithTag("send_message_btn").performClick()
        compose.waitUntil(180_000) { !vm.isChatGenerating.value && vm.chatMessages.value.size > count }
        return repo.messages(vm.conversationId.value).first().last { it.role == "assistant" }.also {
            rows.put(JSONObject().put("question", question).put("status", it.groundingStatus).put("state", it.messageState)
                .put("answer", it.content).put("sources", JSONArray(it.sourcesJson)).put("model", it.modelName).put("latencyMs",it.latencyMs).put("tokensPerSecond",it.tokensPerSec))
        }
    }
    @Test fun realNativeModelRecoversGeneralAndMixedTasksKeepsLiveFactsUnverifiedAndCancelsCleanly(): Unit = runBlocking {
        admit()
        val filename = InstrumentationRegistry.getArguments().getString("reliabilityModel") ?: "smollm2-135m-instruct-q4_k_m.gguf"
        val model = withTimeout(90_000) { repo.allModels.first { models -> models.any { it.filename == filename && it.isInstalled } } }
            .single { it.filename == filename }
        repo.engine.setProcessingMode(ProcessingMode.CPU)
        repo.switchActiveModel(model)
        repo.preferencesRepository.saveWebAllowed(true)
        compose.waitUntil(20_000) { vm.webAllowed.value && vm.conversationId.value != 0L && !repo.isModelLoading }
        compose.runOnUiThread {
            vm.toggleForegroundService(false)
            vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 192, temperature = 0f, threads = 4))
            vm.useSearchProviderForTesting(object : SearchProvider {
                override suspend fun search(question: String): List<EvidenceSource> = throw WebRequestException(429, 120_000)
            })
        }
        try {
            fresh()
            val general = ask("How do birds fly?")
            assertEquals("LOCAL_UNVERIFIED", general.groundingStatus)
            assertTrue(general.content, general.content.contains("on-device knowledge"))
            assertTrue(general.content, general.content.length > 100)
            assertTrue(GroundingResult.sources(general.sourcesJson).isEmpty())
            fresh()
            val live = ask("What's the current Jubilee line status?")
            assertEquals("PROVIDER_RATE_LIMITED", live.groundingStatus)
            assertFalse(live.content, Regex("(?i)good service|no delays|running normally").containsMatchIn(live.content))
            fresh()
            val mixed = ask("Explain inflation and tell me the latest UK inflation rate.")
            assertEquals("LOCAL_WITH_WEB_GAP", mixed.groundingStatus)
            assertTrue(mixed.content, mixed.content.contains("couldn't check"))
            assertTrue(mixed.content, mixed.content.contains("inflation", true))
            fresh()
            val started = CompletableDeferred<Unit>()
            compose.runOnUiThread { vm.useSearchProviderForTesting(object : SearchProvider {
                override suspend fun search(question: String): List<EvidenceSource> { started.complete(Unit); awaitCancellation() }
            }) }
            compose.onNodeWithTag("chat_input_field").performTextReplacement("Search for Orion Android memory")
            compose.onNodeWithTag("send_message_btn").performClick()
            withTimeout(15_000) { started.await() }
            compose.runOnUiThread { vm.cancelGeneration() }
            compose.waitUntil(5000) { !vm.isChatGenerating.value }
            val stopped = repo.messages(vm.conversationId.value).first().last { it.role == "assistant" }
            assertEquals("STOPPED", stopped.messageState); assertEquals("USER_CANCELLED", stopped.groundingStatus)
            assertEquals("Response stopped.", stopped.content)
            rows.put(JSONObject().put("question", "Cancellation during discovery").put("state", stopped.messageState).put("status", stopped.groundingStatus))
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_Web & grounding"))
            compose.onNodeWithTag("setting_Web & grounding").performClick()
            compose.onNodeWithTag("web_settings_list").performScrollToNode(hasTestTag("research_details"))
            compose.onNodeWithTag("research_details").performClick()
            compose.onNodeWithTag("web_settings_list").performScrollToNode(hasTestTag("view_research_diagnostics"))
            compose.onNodeWithTag("view_research_diagnostics").performClick()
            compose.waitUntil(5000) { vm.researchDiagnosticReport.value != null }
            compose.onNodeWithTag("research_diagnostics_report").assertExists()
            val export = vm.researchDiagnosticReport.value!!
            assertTrue(export.contains("PROVIDER_RATE_LIMITED"))
            for (secret in listOf("Orion", "inflation", "conversationId", "originalQuery", "excerpt")) assertFalse(secret, export.contains(secret))
        } finally {
            repo.engine.unloadActiveModel()
            File(context.filesDir, "web-reliability-native.json").writeText(rows.toString(2))
        }
    }
    @Test fun livePublicSourcesAndOnDeviceFallbackUseTheSameReaderAndCitationChecks(): Unit = runBlocking {
        admit()
        val questions = listOf("How does Android Vulkan graphics initialization work?", "Weather in London today", "Who is acting as Doom in Avengers Doomsday?", "Which shoe is good for running?", "When is Malayalam movie I'm Game releasing on OTT?")
        var successful = 0
        for (question in questions) {
            val start = android.os.SystemClock.elapsedRealtime()
            val outcome = PublicWebResearch().research(question) {}
            val answer = if (outcome.sources.isEmpty()) null else EvidenceAnswer.resolve("I cannot answer from memory.", outcome.sources, question)
            if (answer != null && answer.support != AnswerSupport.INSUFFICIENT) {
                assertTrue("$question: ${answer.text}", GroundingPolicy.validateCitations(answer.text, outcome.sources).second); successful++
            } else assertNotNull("A failed read needs a classified reason", outcome.failure)
            rows.put(JSONObject().put("question", question).put("scope", "Live phone retrieval plus deterministic synthesis; no model run")
                .put("failure", outcome.failure?.name.orEmpty()).put("readSources", outcome.sources.size)
                .put("supported", answer?.support?.name.orEmpty()).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start))
            assertTrue(android.os.SystemClock.elapsedRealtime() - start < 65_000)
            outcome.sources.firstOrNull { it.imageUrl.isNotBlank() }?.let { image ->
                val decoded = runCatching { SourceImages.load(image) { true } }
                rows.put(JSONObject().put("scope", "Optional live publisher image").put("host", ReadableSourceSummary.publisher(image.url))
                    .put("decoded", decoded.isSuccess))
                decoded.getOrNull()?.let { bitmap -> assertTrue(bitmap.width <= 768 && bitmap.height <= 768); bitmap.recycle() }
            }
        }
        // Force only Exa to fail; actual public source fetches remain live.
        val actual = SearchHttp()
        val fallback = PublicWebResearch(object : WebFetcher {
            override suspend fun fetch(url: String) = actual.fetch(url)
            override suspend fun fetchPage(url: String, permitted: suspend (String) -> Boolean) = actual.fetchPage(url, permitted)
            override suspend fun postJson(url: String, json: String, permitted: suspend (String) -> Boolean): WebDocument = throw WebRequestException(429, 120_000)
        }).research(questions.first()) {}
        assertTrue("BI fallback did not read its public Android source: ${fallback.failure}", fallback.sources.isNotEmpty())
        assertTrue(fallback.sources.any { it.discoveryProvider == BiDiscovery.providerName })
        rows.put(JSONObject().put("question", questions.first()).put("scope", "Simulated Exa rate limit; real publisher requests through BI fallback")
            .put("provider", fallback.metrics.provider).put("readSources", fallback.sources.size))
        File(context.filesDir, "web-reliability-live.json").writeText(rows.toString(2))
        assertTrue("None of the live phone questions had readable supported sources", successful > 0)
    }
    @Test fun phoneRegressionQuestionsUseSourcesAndNeverExposeUncheckedDrafts(): Unit = runBlocking {
        admit()
        val model = withTimeout(90_000) { repo.allModels.first { models -> models.any { it.filename == "Qwen3.5-2B-Q4_K_M.gguf" && it.isInstalled } } }
            .single { it.filename == "Qwen3.5-2B-Q4_K_M.gguf" }
        repo.engine.setProcessingMode(ProcessingMode.CPU); repo.switchActiveModel(model)
        repo.preferencesRepository.saveWebAllowed(true)
        compose.waitUntil(20_000) { vm.webAllowed.value && vm.conversationId.value != 0L && !repo.isModelLoading }
        compose.runOnUiThread {
            vm.toggleForegroundService(false)
            vm.updateGenerationParams(GenerationParams(contextSize=2048,maxTokens=160,temperature=0f,threads=4))
        }
        // Fictional source facts exercise the real model + full ViewModel path.
        val cases = listOf(
            Triple("When is malayalam movie i'm game releasing on ott", "I'm Game Malayalam film", "I'm Game Malayalam film starts streaming on November 18, 2026."),
            Triple("When is Road Atlas Hindi movie releasing on OTT?", "Road Atlas Hindi film", "Road Atlas Hindi film was released in theaters on October 2, 2026."),
            Triple("Which shoe is good for running", "Running shoe tests", "The Swift trainer is a budget running shoe. Our testers found its cushioning comfortable for easy daily runs.")
        )
        val leaks=java.util.concurrent.atomic.AtomicInteger()
        val observer=launch(Dispatchers.Default) { vm.streamingChatText.collect { if (it.isNotBlank()) leaks.incrementAndGet() } }
        try {
            for ((question,title,body) in cases) {
                fresh()
                compose.runOnUiThread { vm.useSearchProviderForTesting(object : SearchProvider {
                    override suspend fun search(question:String):List<EvidenceSource> = listOf(EvidenceSource(1,title,"https://publisher.example/regression",body,System.currentTimeMillis(),java.time.LocalDate.now().toString(),"WEB_PAGE","PUBLISHED"))
                }) }
                val answer=ask(question)
                assertFalse(answer.content,answer.content.contains("couldn't verify an answer"))
                assertTrue(answer.content,GroundingPolicy.validateCitations(answer.content,GroundingResult.sources(answer.sourcesJson)).second)
                if (question.contains("Road Atlas")) assertTrue(answer.content,answer.content.contains(ReleaseAnswerScope.missingOtt))
                if (question.contains("i'm game")) assertTrue(answer.content,answer.content.contains("November 18, 2026"))
                if (question.startsWith("Which shoe")) {
                    assertTrue(answer.content,answer.content.contains("Swift",true) && answer.content.contains("running shoe",true))
                    assertFalse(answer.content.contains("Design Picture")); assertFalse(answer.content.contains("See all our test results"))
                }
                assertEquals("No grounded draft should reach the display before checking",0,leaks.get())
            }
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir,"readable-answer.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        } finally { observer.cancel(); repo.engine.unloadActiveModel();File(context.filesDir,"readable-native.json").writeText(rows.toString(2)) }
    }

    @Test fun failingPhoneTopicsUseLiveWebAndRealQwen(): Unit = runBlocking {
        admit()
        val model=withTimeout(90_000) {repo.allModels.first { it.any { m -> m.filename=="Qwen3.5-2B-Q4_K_M.gguf" && m.isInstalled } }}.single {it.filename=="Qwen3.5-2B-Q4_K_M.gguf"}
        repo.engine.setProcessingMode(ProcessingMode.CPU);repo.switchActiveModel(model)
        repo.preferencesRepository.saveWebAllowed(true)
        compose.waitUntil(20_000) {vm.webAllowed.value && vm.conversationId.value!=0L && !repo.isModelLoading}
        compose.runOnUiThread {
            vm.toggleForegroundService(false)
            vm.updateGenerationParams(GenerationParams(contextSize=2048,maxTokens=192,temperature=0f,threads=4))
            vm.useSearchProviderForTesting(PublicWebResearch(discoveryAllowed={repo.preferencesRepository.webAllowed.first()}))
        }
        try {
            for(q in listOf("When is malayalam movie i'm game releasing on ott", "When is dhrishyam 3 hindi releasing on ott", "Which shoe is good for running", "How much is on ai worth")) {
                fresh();val a=ask(q);val sources=GroundingResult.sources(a.sourcesJson)
                if(q.contains("on ai")) {assertTrue(a.content,a.content.contains("Do you mean"));continue}
                assertTrue("No live sources for $q: ${a.groundingStatus}: ${a.content}",sources.isNotEmpty())
                assertTrue(sources.all { !it.url.contains("publisher.example") })
                assertFalse(a.content,a.content.contains("couldn't verify an answer"))
                assertTrue(a.content,GroundingPolicy.validateCitations(a.content,sources).second)
                if(q.contains("ott")) assertTrue(a.content,Regex("(?i)ott|streaming|digital").containsMatchIn(a.content))
                for(junk in listOf("Daily Crossword","Syndication Service","Copyright ©")) assertFalse(a.content,a.content.contains(junk))
                if(q.contains("i'm game")) {
                    assertTrue(a.content,a.content.contains("JioHotstar",true))
                    assertTrue(a.content,Regex("(?i)November 0?6|0?6 November|Nov\\.? 0?6|2026-11-06").containsMatchIn(a.content))
                    assertFalse(a.content.contains("do not cover every requested"))
                }
                  if(q.contains("dhrishyam")) {
                      assertTrue(a.content, a.content.contains("Netflix",true))
                      assertTrue(a.content, Regex("(?i)could|might|expected|not.{0,30}confirmed").containsMatchIn(a.content))
                      assertFalse(a.content, Regex("(?i)Georgekutty|Jeethu Joseph|Mohanlal").containsMatchIn(a.content))
                  }
                if(q.startsWith("Which")) {assertFalse(a.content.contains("Design Picture"));assertFalse(a.content.contains("See all our test results"))}
            }
        } finally {repo.engine.unloadActiveModel();File(context.filesDir,"readable-live-native.json").writeText(rows.toString(2))}
    }

    @Test fun tenEarlierPublicPhoneQuestionsUseTheActualModelAndLiveResearch(): Unit = runBlocking {
        admit()
        val model=withTimeout(90_000) {repo.allModels.first { it.any { m -> m.filename=="Qwen3.5-2B-Q4_K_M.gguf" && m.isInstalled } }}.single {it.filename=="Qwen3.5-2B-Q4_K_M.gguf"}
        repo.engine.setProcessingMode(ProcessingMode.CPU);repo.switchActiveModel(model)
        repo.preferencesRepository.saveWebAllowed(true)
        compose.waitUntil(20_000) {vm.webAllowed.value && vm.conversationId.value!=0L && !repo.isModelLoading}
        compose.runOnUiThread {
            vm.toggleForegroundService(false)
            vm.updateGenerationParams(GenerationParams(contextSize=2048,maxTokens=224,temperature=0f,threads=4))
            vm.useSearchProviderForTesting(PublicWebResearch(discoveryAllowed={repo.preferencesRepository.webAllowed.first()}))
        }
        val questions=listOf("How much loan does openai have","Antropic or openai have most number of users","What is the time in india","What time is it now","Why is the iphone duo cracking","What's the wether in london","Who is being cast in the movie Matrubhumi","How's batha tamil movie","Who is the ownsr of L&R HOTELS","Who is the richest person in the world right now")
        val failures=mutableListOf<String>()
        try {
            for(q in questions) {
                fresh();val a=ask(q);val sources=GroundingResult.sources(a.sourcesJson)
                val problem=when {
                    a.messageState=="ERROR" -> "generation error"
                    a.content.contains("couldn't verify an answer") -> "generic verification failure"
                    a.content.contains("Daily Crossword") || a.content.contains("For reprint rights") -> "publisher controls leaked"
                    q.contains("time",true) && (a.groundingStatus!="DEVICE_CLOCK" || !Regex("\\d{2}:\\d{2}").containsMatchIn(a.content)) -> "incorrect clock routing"
                    sources.isNotEmpty() && !GroundingPolicy.validateCitations(a.content,sources).second -> "invalid final citation"
                    sources.isEmpty() && !q.contains("time",true) && a.groundingStatus !in setOf("NEEDS_SUBJECT","NEEDS_LOCATION") -> "no supporting sources: ${a.groundingStatus}"
                      q.contains("loan") && !Regex("[$£€]\\s*\\d").containsMatchIn(a.content) -> "missing borrowing amount/report"
                      q.contains("iphone") && (a.content.contains("_208") || a.content.contains("Part Of All") || !Regex("(?i)cause|pressure|stress").containsMatchIn(a.content)) -> "caption or unrelated specification instead of cause"
                      q.contains("wether") && !EvidenceAnswer.hasTemperature(a.content) -> "missing actual weather temperature"
                      q.contains("Matrubhumi") && (!a.content.contains("Salman",true) || !a.content.contains("Chitrangda",true)) -> "cast names omitted"
                      q.contains("HOTELS") && !a.content.contains("Livingstone",true) -> "requested owner not identified"
                      q.contains("richest") && !a.content.contains("Elon Musk",true) -> "global leader not identified"
                    else -> ""
                }
                if(problem.isNotEmpty()) failures += "$q: $problem"
                File(context.filesDir,"ten-earlier-live-native.json").writeText(rows.toString(2))
            }
            assertTrue(failures.joinToString("\n"),failures.isEmpty())
        } finally {repo.engine.unloadActiveModel();File(context.filesDir,"ten-earlier-live-native.json").writeText(rows.toString(2))}
    }

}
