package com.example

import com.example.data.ChatMessageEntity
import com.example.engine.ChatAnswerPolicy
import com.example.engine.ChatTurn
import com.example.grounding.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class BalancedAnswerRoutingTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val source = EvidenceSource(1, "Orion Android runtime GPU support", "https://docs.example/orion",
        "Orion Android runtime supports GPU inference with a compatible driver. It requires 4 GB of shared memory.", 1_000)

    @Test fun labelledCorpusMeasuresRoutingRatherThanClaimingAnswerAccuracy() {
        val rows = javaClass.classLoader!!.getResourceAsStream("answer-routing-corpus.tsv")!!.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() }.map { it.split('\t', limit = 3) }
        assertTrue(rows.size.toString(), rows.size >= 100)
        val report = JSONArray(); val errors = mutableListOf<String>()
        var baselineUnnecessary = 0; var newUnnecessary = 0; var localCases = 0
        var oldNanos = 0L; var newNanos = 0L
        rows.forEach { (label, category, question) ->
            val before = System.nanoTime()
            val old = com.example.balancedbaseline.ResearchRouting.decide(question, today).search
            oldNanos += System.nanoTime() - before
            val start = System.nanoTime()
            val route = AnswerRoutingPolicy.decide(question, today = today)
            newNanos += System.nanoTime() - start
            if (label == "LOCAL") { localCases++; if (old) baselineUnnecessary++; if (route.path != AnswerPath.LOCAL) newUnnecessary++ }
            if (route.path.name != label) errors += "$question: expected $label, got ${route.path} (${route.reason})"
            if (route.path == AnswerPath.WEB_GROUNDED) assertFalse(GroundingPolicy.shouldSearch(false, GroundingMode.AUTO, question))
            report.put(JSONObject().put("question", question).put("category", category).put("expected", label)
                .put("actual", route.path).put("legacyWeb", old).put("reason", route.reason))
        }
        val output = File("build/reports/balanced-routing-evaluation.json").apply { parentFile.mkdirs() }
        output.writeText(JSONObject().put("baseline", "81253eee23b2e9ebf96eb813f5339f8d623d7032")
            .put("scope", "Labelled deterministic routing corpus, not real-world model accuracy. JVM elapsed routing time includes warmup.")
            .put("cases", report).put("localCases", localCases).put("legacyUnnecessarySearches", baselineUnnecessary)
            .put("newUnnecessarySearches", newUnnecessary).put("legacyRoutingNs", oldNanos).put("newRoutingNs", newNanos).toString(2))
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test fun failuresKeepLocalKnowledgeButNeverInventLiveOrVerifiedInformation() {
        for (failure in ResearchFailure.entries) {
            val local = AnswerRoutingPolicy.decide("Explain how lithium batteries work.")
            val recovery = AnswerRoutingPolicy.recover(local, failure)
            assertEquals(local.question, recovery.localQuestion)
            assertEquals("LOCAL_WEB_UNAVAILABLE", recovery.status)
            val live = AnswerRoutingPolicy.recover(AnswerRoutingPolicy.decide("What is the current temperature in London?"), failure)
            assertNull(live.localQuestion); assertTrue(live.notice.contains("couldn't check")); assertFalse(EvidenceAnswer.hasTemperature(live.notice))
            val verify = AnswerRoutingPolicy.recover(AnswerRoutingPolicy.decide("Verify this claim using official sources."), failure)
            assertNull(verify.localQuestion); assertTrue(verify.notice.contains("couldn't verify"))
            assertFalse(AnswerSourceStatus.label(verify.status).contains("verified"))
            val mixed = AnswerRoutingPolicy.recover(AnswerRoutingPolicy.decide("How does Gemma 4 work, and what's its latest supported Android runtime?"), failure)
            assertEquals("How does Gemma 4 work", mixed.localQuestion)
            assertEquals("LOCAL_WITH_WEB_GAP", mixed.status)
        }
        assertFalse(ChatAnswerPolicy.requiresLiveData("Explain how lithium batteries work."))
        assertTrue(ChatAnswerPolicy.requiresLiveData("What's the latest Android version?"))
    }

    @Test fun localFollowupsKeepContextAndPrivateHistoryNeverBecomesDiscoveryText() {
        val history = listOf(ChatMessageEntity(role = "user", content = "Private project secret. Explain GPU inference.", conversationId = 7),
            ChatMessageEntity(role = "assistant", content = "A GPU executes parallel model operations [1].", conversationId = 7,
                sourcesJson = GroundingResult(sources = listOf(source)).json(), groundingStatus = "EVIDENCE_CITED"))
        for (q in listOf("Can it run Gemma 4?", "What about NPU?", "Explain it simply.", "What about the previous example?")) {
            val route = AnswerRoutingPolicy.decide(q, history = history, today = today, now = 2_000)
            assertEquals(q, AnswerPath.LOCAL, route.path)
            assertFalse(route.question.contains("Private project"))
        }
        val transformed = ChatAnswerPolicy.history(listOf(ChatTurn("user", "Use Malayalam"), ChatTurn("assistant", "Old claim [1].")), true)
        assertEquals(2, transformed.size); assertTrue(transformed.first().content.contains("Use Malayalam"))
        assertTrue(transformed.last().content.contains("unverified context")); assertFalse(transformed.last().content.contains("[1]"))
        val privateQuestion = "Explain this pasted code\n// search online api_key: private-value"
        val route = AnswerRoutingPolicy.decide(privateQuestion)
        assertEquals(AnswerPath.LOCAL, route.path); assertTrue(route.privacyBlocked)
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("Explain the current code I pasted\nval url = \"https://private.example/user\"").path)
    }

    @Test fun freshPublicEvidenceCanBeReusedButStaleEvidenceCannot() {
        val history = listOf(ChatMessageEntity(role = "assistant", content = "Prior result", groundingStatus = "EVIDENCE_CITED",
            sourcesJson = GroundingResult(sources = listOf(source)).json()))
        val route = AnswerRoutingPolicy.decide("Does it support GPU?", history = history, now = 2_000)
        assertEquals(AnswerPath.WEB_GROUNDED, route.path); assertEquals(1, route.reusedSources.size)
        assertTrue(AnswerRoutingPolicy.decide("Does it support GPU?", history = history, now = 400_000).reusedSources.isEmpty())
        assertTrue(AnswerRoutingPolicy.decide("Check again", history = history, now = 2_000).reusedSources.isEmpty())
        val localTask = history + ChatMessageEntity(role = "user", content = "Write a poem") +
            ChatMessageEntity(role = "assistant", content = "A short poem", groundingStatus = "LOCAL")
        assertEquals("Try again", ConversationalResearch.question("Try again", localTask))
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("Try again", history = localTask).path)
    }

    @Test fun cachedWeatherExpiresAndPermissionAlwaysPrecedesCache() = runBlocking {
        var time = 1_000L; var calls = 0
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to object : SearchProvider {
            override suspend fun search(question: String): List<EvidenceSource> { calls++; return listOf(source) }
        })) { time }
        val q = "Weather in London today"
        coordinator.retrieve(q, true, GroundingMode.AUTO, SearchProviderId.WEB)
        time += 59_000; coordinator.retrieve(q, true, GroundingMode.AUTO, SearchProviderId.WEB); assertEquals(1, calls)
        time += 2_000; coordinator.retrieve(q, true, GroundingMode.AUTO, SearchProviderId.WEB); assertEquals(2, calls)
        coordinator.retrieve(q, false, GroundingMode.AUTO, SearchProviderId.WEB); assertEquals(2, calls)
        coordinator.clearTemporaryData(); coordinator.retrieve(q, true, GroundingMode.AUTO, SearchProviderId.WEB); assertEquals(3, calls)
    }

    @Test fun offlineTimeoutBlockEmptyAndExtractionFailuresRemainDistinct() = runBlocking {
        for (kind in ResearchFailure.entries) {
            val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to object : SearchProvider {
                override suspend fun search(question: String) = emptyList<EvidenceSource>()
                override suspend fun research(question: String, progress: (ResearchStage) -> Unit) = ResearchOutcome(emptyList(), kind)
            }))
            assertEquals(kind, coordinator.retrieve("Search for Orion", true, GroundingMode.AUTO, SearchProviderId.WEB).failure)
        }
        val failed = GroundingCoordinator(mapOf(SearchProviderId.WEB to object : SearchProvider {
            override suspend fun search(question: String): List<EvidenceSource> = throw java.io.IOException("private URL must not leak")
        })).retrieve("Search for Orion", true, GroundingMode.AUTO, SearchProviderId.WEB)
        assertEquals(ResearchFailure.DISCOVERY_UNAVAILABLE, failed.failure); assertFalse(failed.notice.contains("private URL"))
        val refused = PublicWebResearch(WebFetcher { error("Page inaccessible") }, DiscoveryProvider { _, _ ->
            DiscoveryResult(listOf(ResearchCandidate(source.url, source.title)), DiscoveryState.FOUND)
        }).research("Search for Orion Android runtime GPU support") {}
        assertEquals(ResearchFailure.INACCESSIBLE, refused.failure)
    }

    @Test fun circuitSkipsRepeatedFailuresAndRecoversAfterBoundedPause() = runBlocking {
        var time = 1_000L; var calls = 0; var healthy = false
        val circuit = DiscoveryCircuit { time }
        val discovery = FreeSearchDiscovery(DiscoveryProvider { _, _ -> calls++; DiscoveryResult(emptyList(), if (healthy) DiscoveryState.EMPTY else DiscoveryState.UNAVAILABLE) },
            DiscoveryProvider { _, _ -> error("Backup requires consent") }, circuit, { false })
        repeat(5) { discovery.discover(ResearchQuery("Orion"), false) }; assertEquals(2, calls)
        time += 60_001; healthy = true
        discovery.discover(ResearchQuery("Orion"), false); assertEquals(3, calls)
        discovery.discover(ResearchQuery("Orion"), false); assertEquals(4, calls)
        circuit.record(DiscoveryState.BLOCKED); assertEquals(DiscoveryState.BLOCKED, circuit.unavailable())
        time += 300_001; assertNull(circuit.unavailable())
    }

    @Test fun ordinaryLocalQuestionsNeverCallAnyProviderEvenWhenItIsBroken() = runBlocking {
        var requests = 0
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to object : SearchProvider {
            override suspend fun search(question: String): List<EvidenceSource> { requests++; error("offline") }
        }))
        for (q in listOf("Explain recursion", "What is the capital of France?", "Write a story", "Explain the current code I pasted")) {
            assertEquals("LOCAL", coordinator.retrieve(q, true, GroundingMode.AUTO, SearchProviderId.WEB).status)
        }
        assertEquals(0, requests)
    }
}
