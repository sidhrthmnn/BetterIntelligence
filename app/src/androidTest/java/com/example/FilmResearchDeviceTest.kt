package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated synthetic chats; opt in with movieResearch=true. Fixtures are not
 * real Batha reviews. The second test uses real Exa and read publisher pages. */
class FilmResearchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val rows = JSONArray()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val repo get() = AiCoreRepository.getInstance(context)
    private val vm get() = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
    private suspend fun setup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("movieResearch") == "true")
        check(context.packageName.endsWith(".validation"))
        val gemma = withTimeout(120_000) { repo.allModels.first { models -> models.any { it.filename == "gemma-4-E2B-it.litertlm" && it.isInstalled } } }
            .single { it.filename == "gemma-4-E2B-it.litertlm" }
        repo.engine.setProcessingMode(ProcessingMode.CPU)
        repo.switchActiveModel(gemma)
        repo.preferencesRepository.saveWebAllowed(true)
        compose.waitUntil(20_000) { vm.webAllowed.value && vm.conversationId.value != 0L && !repo.isModelLoading }
        compose.runOnUiThread { vm.toggleForegroundService(false); vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 256, temperature = 0f, threads = 4)) }
    }
    private suspend fun fresh() {
        val before = vm.conversationId.value
        compose.runOnUiThread { vm.newConversation() }
        compose.waitUntil(15_000) { vm.conversationId.value != 0L && vm.conversationId.value != before }
    }
    private suspend fun ask(text: String): ChatMessageEntity {
        compose.waitForIdle()
        val count = repo.messages(vm.conversationId.value).first().size
        val start = android.os.SystemClock.elapsedRealtime()
        compose.onNodeWithTag("chat_input_field").performTextReplacement(text)
        compose.onNodeWithTag("send_message_btn").performClick()
        compose.waitUntil(180_000) { !vm.isChatGenerating.value && vm.chatMessages.value.size > count }
        val answer = repo.messages(vm.conversationId.value).first().last { it.role == "assistant" }
        rows.put(JSONObject().put("question", text).put("answer", answer.content).put("status", answer.groundingStatus)
            .put("state", answer.messageState).put("model", answer.modelName).put("sources", JSONArray(answer.sourcesJson))
            .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start))
        assertTrue(answer.content, answer.messageState in setOf("COMPLETE", "LENGTH_LIMIT"))
        assertTrue(answer.modelName, answer.modelName.contains("Gemma"))
        assertFalse(answer.content, answer.content.contains("From the retrieved pages"))
        assertFalse(answer.content, answer.content.lines().any { it.trimStart().startsWith(">") })
        return answer
    }
    @Test fun fixturesWithRealGemmaRejectPoisonedEvidenceResolveFollowupsAndRespectConsent(): Unit = runBlocking {
        setup()
        val requests = CopyOnWriteArrayList<String>()
        val unrelatedOnly = AtomicBoolean(true)
        val unavailable = AtomicBoolean(false)
        fun page(title: String, url: String, body: String) = EvidenceSource(1, title, url, "$title\n$body", System.currentTimeMillis(), kind = "WEB_PAGE")
        val wrong = page("iPhone 15 Pro Max review", "https://phones.example/iphone-review", "The iPhone 15 Pro Max has an excellent camera and an engaging display.")
        compose.runOnUiThread { vm.useSearchProviderForTesting(object : SearchProvider {
            override suspend fun search(question: String): List<EvidenceSource> {
                requests += question
                if (unavailable.get()) throw java.io.IOException("Synthetic network failure")
                if (unrelatedOnly.get()) return listOf(wrong)
                if (question.contains("recursion", true)) return listOf(page("Recursion explained", "https://code.example/recursion", "Recursion is a technique where a function calls itself. A base case stops the recursive calls."))
                val body = if (PublicTopics.parse(question)?.intent == PublicTopicIntent.CAST)
                    "Batha is a Tamil film. Batha stars Example Actor and Example Actress."
                else "Batha is a Tamil film. The critic rated Batha 2.5 stars, praising its compelling performances but criticising its uneven screenplay."
                return listOf(page("Batha Tamil film review", "https://reviews.example/batha", body), wrong.copy(id = 2))
            }
        }) }
        try {
            fresh()
            compose.runOnUiThread { vm.setGroundingMode(GroundingMode.AUTO) }
            val rejected = ask("How's batha tamil movie?")
            assertTrue(requests.single(), requests.single().contains("batha", true))
            assertTrue(GroundingResult.sources(rejected.sourcesJson).isEmpty())
            assertTrue(rejected.content, rejected.content.contains("batha", true)); assertFalse(rejected.content.contains("iPhone", true))
            unrelatedOnly.set(false)
            val review = ask("Find review")
            assertTrue(requests.last(), requests.last().contains("batha", true)); assertTrue(review.content, review.content.contains("2.5 stars"))
            assertTrue(GroundingResult.sources(review.sourcesJson).all { it.title.contains("Batha") })
            assertTrue(ask("Who acted in it?").content.contains("Example Actor"))
            assertTrue(ask("What is its rating?").content.contains("2.5"))
            fresh()
            val before = requests.size
            val missing = ask("Find review")
            assertEquals(before, requests.size); assertTrue(missing.content.contains("Which film"))
            // New and old chats use ON by default, so even a stable public
            // explanation is fact-checked before model generation.
            assertEquals(GroundingMode.ON, vm.groundingMode.value)
            val coding = ask("Explain recursion")
            assertTrue(requests.last().contains("recursion", true)); assertTrue(GroundingResult.sources(coding.sourcesJson).isNotEmpty())
            fresh()
            unavailable.set(true)
            val failed = ask("How's batha tamil movie?")
            assertTrue(failed.content.contains("batha", true)); assertTrue(GroundingResult.sources(failed.sourcesJson).isEmpty())
            repo.preferencesRepository.saveWebAllowed(false)
            compose.waitUntil(10_000) { !vm.webAllowed.value }
            fresh(); val prior = requests.size
            ask("How's batha tamil movie?")
            assertEquals(prior, requests.size)
        } finally {
            repo.engine.unloadActiveModel()
            File(context.filesDir, "film-fixture-device.json").writeText(JSONObject().put("fixtureQueries", JSONArray(requests)).put("answers", rows).toString(2))
        }
    }
    @Test fun liveGemmaResearchKeepsTheFilmSubjectAndShowsReadableCitedAnswers(): Unit = runBlocking {
        setup()
        try {
            fresh()
            val batha = ask("How's batha tamil movie?")
            assertFalse(batha.groundingStatus in setOf("LOCAL", "LOCAL_UNVERIFIED"))
            val follow = ask("Find review")
            assertFalse(follow.content.contains("iPhone", true))
            val sources = GroundingResult.sources(follow.sourcesJson)
            if (sources.isEmpty()) assertTrue(follow.content, follow.content.contains("batha", true))
            else assertTrue(sources.all { PublicTopics.parse("batha Tamil film reviews")!!.sourceMatches(it.title, it.excerpt, it.url) })
            fresh()
            val known = ask("How's Maharaja Tamil movie?")
            val read = GroundingResult.sources(known.sourcesJson)
            assertTrue(known.content, read.isNotEmpty())
            assertTrue(known.content, known.content.contains(Regex("\\[\\d")))
            assertTrue(known.content, known.content.contains("Maharaja", true))
            assertTrue(read.all { it.title.contains("Maharaja", true) || it.url.contains("maharaja", true) })
            val cast = ask("Who acted in it?")
            assertTrue(cast.content, cast.content.contains("Vijay Sethupathi", true))
            assertTrue(GroundingResult.sources(cast.sourcesJson).isNotEmpty())
            val diagnostics = File(context.cacheDir, "research-diagnostics.json").readText()
            assertTrue(diagnostics.contains("Maharaja", true)); assertFalse(diagnostics.contains("iPhone", true))
        } finally {
            repo.engine.unloadActiveModel()
            File(context.filesDir, "film-live-device.json").writeText(rows.toString(2))
        }
    }
}
