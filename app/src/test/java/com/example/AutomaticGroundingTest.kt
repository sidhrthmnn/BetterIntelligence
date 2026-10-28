package com.example

import com.example.grounding.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticGroundingTest {
    private val question = "Who is playing doom in avengers doomsday"
    private val confirmed = EvidenceSource(1, "Marvel Studios announces Avengers: Doomsday", "https://thewaltdisneycompany.com/news/cast/",
        "Marvel Studios announces Avengers: Doomsday\nRobert Downey Jr. returns in a new role as Doctor Doom in Avengers: Doomsday.", 1)

    @Test fun broadPublicQuestionsSearchWithoutMagicFreshnessWords() {
        val factual = listOf(question, "who plays doom in avengers doomsday", "Who's playing Doctor Doom?", "Which actor is playing Doom?",
            "Who wrote Pride and Prejudice?", "Who directed Inception?", "Who voices Mario in the movie?", "Who founded Nintendo?",
            "What is photosynthesis?", "Explain recursion", "Why is the sky blue?", "How does Vulkan work?", "What does DNA stand for?",
            "Where is the Taj Mahal?", "When did World War II end?", "Who was UK prime minister in 1945?", "Does Android support Vulkan?",
            "Can example domains be registered?", "What is the weather in London today?", "Who is the mayor of London?",
            "What does iPhone Duo cost in the UK?", "What is the latest Android version?", "List the planets", "Difference between RAM and storage", "Tell me about photosynthesis")
        factual.forEach { assertTrue(it, GroundingPolicy.shouldSearch(true, GroundingMode.AUTO, it)) }
        val local = listOf("Hello", "Thanks", "What did I tell you about my project?", "What was our game item?", "Is it a reptile?",
            "Write a poem about today", "Translate this text: who is playing Doom?", "What is 15% of 240?", "Do not search. Who plays Doom?")
        local.forEach { assertFalse(it, GroundingPolicy.shouldSearch(true, GroundingMode.AUTO, it)) }
        assertFalse(GroundingPolicy.shouldSearch(false, GroundingMode.AUTO, question))
        assertTrue(ResearchQuery(question).searchText.contains("actor cast"))
        assertFalse(ResearchQuery(question).terms.contains("play"))
        assertNull(ResearchQuery("Who is currently playing Doom in Avengers Doomsday?").earliest)
    }

    @Test fun surnameSuffixesAndInitialsDoNotBreakCitedSentences() {
        val answer = "Robert Downey Jr. plays Doctor Doom in Avengers: Doomsday [1]."
        assertEquals(1, ClaimVerification.sentences(answer).size)
        val checked = EvidenceAnswer.resolve(answer, listOf(confirmed), question)
        assertEquals(checked.claims.toString(), AnswerSupport.CITED, checked.support)
        assertEquals(answer, checked.text)
        assertEquals(1, ClaimVerification.sentences("Dr. A. Example wrote the report [1].").size)
        assertEquals(2, ClaimVerification.sentences("The first fact is here [1]. A second fact is here [1].").size)
    }

    @Test fun matchingHeadlinesAndTheoriesAreNotCastingAnswers() {
        val headline = "'Avengers: Doomsday' Theory Suggests the Real Doom Is Hiding in Plain Sight"
        val theory = confirmed.copy(title = headline, excerpt = headline)
        assertFalse(FactualResearch.usable(theory, question))
        assertEquals(AnswerSupport.INSUFFICIENT, EvidenceAnswer.resolve("Uncited answer", listOf(theory), question).support)
        val speculation = confirmed.copy(title = "A fan theory about Doom in Avengers Doomsday", excerpt = confirmed.excerpt + "\n\nA rumour suggests a different actor might be playing Doom.")
        assertEquals(AnswerSupport.INSUFFICIENT, EvidenceAnswer.resolve("Robert Downey Jr. plays Doctor Doom in Avengers: Doomsday [1].", listOf(speculation), question).support)
        val missingActor = confirmed.copy(excerpt = "Avengers: Doomsday\nThe production has a large cast and its new posters are available to see online.")
        assertFalse(FactualResearch.usable(missingActor, question))
        val extracted = EvidenceAnswer.resolve("Unknown answer", listOf(confirmed), question)
        assertTrue(extracted.text, extracted.text.contains("Robert Downey Jr."))
        assertTrue(extracted.sourceExtract)
        assertFalse(EvidenceAnswer.resolve("Unknown", listOf(confirmed.copy(excerpt = confirmed.title)), question).sourceExtract)
    }

    @Test fun discoveryUsesOnlyAllowedBackupAndNeverTurnsSnippetsIntoEvidence() = runBlocking {
        var primaryCalls = 0; var backupCalls = 0
        val primary = DiscoveryProvider { _, _ -> primaryCalls++; DiscoveryResult(emptyList(), DiscoveryState.BLOCKED) }
        val backup = DiscoveryProvider { _, _ -> backupCalls++; DiscoveryResult(listOf(ResearchCandidate(confirmed.url, confirmed.title)), DiscoveryState.FOUND) }
        assertEquals(DiscoveryState.BLOCKED, FreeSearchDiscovery(primary, backup) { false }.discover(ResearchQuery(question), false).state)
        assertEquals(0, backupCalls)
        assertEquals(DiscoveryState.FOUND, FreeSearchDiscovery(primary, backup) { true }.discover(ResearchQuery(question), false).state)
        assertEquals(2, primaryCalls); assertEquals(1, backupCalls)
        val json = """{"results":[{"url":"${confirmed.url}","title":"Actual publisher","content":"Untrusted search snippet"},{"url":"http://unsafe.example/","title":"Unsafe"},{"url":"https://127.0.0.1/private"}]}"""
        assertEquals(1, MwmblDiscovery.candidates(json).size)
        assertEquals("Untrusted search snippet", MwmblDiscovery.candidates(json).single().routingHint)
    }

    @Test fun liteHandlesUnknownMarkupButDoesNotRetryAChallenge() = runBlocking {
        val calls = mutableListOf<String>()
        var challenged = false
        val discovery = HtmlSearchDiscovery(WebFetcher { url ->
            calls += url
            WebDocument(url, if (challenged) "<form id='challenge-form'></form>" else if ("lite.duckduckgo" in url)
                "<a class='result-link' href='${confirmed.url}'>${confirmed.title}</a>" else "<html>Unknown layout</html>", "text/html")
        }, { true })
        assertEquals(DiscoveryState.FOUND, discovery.discover(ResearchQuery(question), false).state)
        assertEquals(2, calls.size)
        calls.clear(); challenged = true
        assertEquals(DiscoveryState.BLOCKED, discovery.discover(ResearchQuery(question), false).state)
        assertEquals(1, calls.size)
    }

    @Test fun anonymousQuotaFailureBacksOffWithoutKeysOrPaidRequests() = runBlocking {
        val calls = mutableListOf<String>()
        var quota = false
        val fetcher = WebFetcher { url ->
            calls += url
            if (quota) error("Website unavailable (HTTP 429)")
            WebDocument(url, """{"results":[{"url":"${confirmed.url}","title":"${confirmed.title}"}]}""", "application/json")
        }
        val reader = MwmblDiscovery(fetcher) { true }
        assertEquals(DiscoveryState.FOUND, reader.discover(ResearchQuery(question), false).state)
        quota = true
        assertEquals(DiscoveryState.UNAVAILABLE, reader.discover(ResearchQuery(question), false).state)
        assertEquals(DiscoveryState.UNAVAILABLE, MwmblDiscovery(fetcher) { true }.discover(ResearchQuery(question), false).state)
        assertEquals(2, calls.size)
        assertTrue(calls.all { "api_key" !in it && it.startsWith("https://api.mwmbl.org/api/v2/search/?q=") })
    }

    @Test fun headlineResultCannotSuppressAnActualPublisherPage() = runBlocking {
        val discovery = DiscoveryProvider { _, _ -> DiscoveryResult(listOf(
            ResearchCandidate("https://theory.example/page", "Avengers Doomsday Theory: Doom Might Be Hiding"),
            ResearchCandidate(confirmed.url, confirmed.title)), DiscoveryState.FOUND) }
        val requests = mutableListOf<String>()
        val results = PublicWebResearch(WebFetcher { url ->
            requests += url
            if (url.endsWith("/robots.txt")) WebDocument(url, "User-agent: *\nAllow: /", "text/plain")
            else WebDocument(url, "<title>${confirmed.title}</title><article><h1>${confirmed.title}</h1><p>Robert Downey Jr. returns in a new role as Doctor Doom in Avengers: Doomsday. Marvel Studios announced the casting.</p></article>", "text/html")
        }, discovery).search(question)
        assertEquals(1, results.size); assertEquals(confirmed.url, results.single().url)
        assertFalse(requests.any { "theory.example" in it })
    }

    @Test fun capturedOfficialCastingPageOptional() {
        val directory = System.getenv("BI_RELIABILITY_CAPTURE") ?: return
        val file = java.io.File(directory, "bi-v20-disney-cast.html").takeIf { it.isFile } ?: return
        val page = WebDocument("https://thewaltdisneycompany.com/news/marvel-studios-announces-robert-downey-jr-and-the-russo-brothers-to-return-for-avengers-doomsday/", file.readText(), "text/html")
        val source = WebPageExtractor.article(page, 1, question)!!
        assertTrue(source.excerpt, FactualResearch.usable(source, question))
        val prepared = GroundingPolicy.prepareEvidence(question, listOf(source), 2300)
        val answer = EvidenceAnswer.resolve("Robert Downey Jr. plays Doctor Doom in Avengers: Doomsday [1].", prepared.sources, question)
        assertTrue(answer.text, answer.text.contains("Robert Downey Jr."))
        assertNotEquals(answer.claims.toString(), AnswerSupport.INSUFFICIENT, answer.support)
    }
}
