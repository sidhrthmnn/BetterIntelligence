package com.example

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.data.ChatMessageEntity
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class FilmResearchTopicTest {
    private val question = "How's batha tamil movie?"
    private fun user(text: String, chat: Long = 1) = ChatMessageEntity(id = 10, role = "user", content = text, conversationId = chat)
    private fun source(title: String = "Batha Tamil film review", body: String = "Batha is a Tamil film. The critic rated Batha 2.5 stars and praised its compelling performances but criticised its uneven screenplay.") =
        EvidenceSource(1, title, "https://reviews.example/batha", "$title\n$body", System.currentTimeMillis(), kind = "WEB_PAGE")

    @Test fun contractionAndLowercaseTitleRouteToResearchInAutomaticMode() {
        val route = AnswerRoutingPolicy.decide(question)
        assertEquals(AnswerPath.WEB_GROUNDED, route.path)
        assertTrue(route.question.contains("batha Tamil film", true))
        assertTrue(route.question.contains("reviews audience reception"))
        val topic = PublicTopics.parse(route.question)!!
        assertEquals("batha", topic.subject); assertEquals("tamil", topic.language)
        assertEquals(PublicEntityType.FILM, topic.type)
    }
    @Test fun userSubjectOverridesUnrelatedAssistantAndSearchHistory() {
        val wrong = source("iPhone 15 Pro Max review", "The iPhone 15 Pro Max has an excellent camera and a bright display.")
        val history = listOf(user(question), ChatMessageEntity(role = "assistant", content = "I suggest checking IMDb. An iPhone is excellent.",
            conversationId = 1, sourcesJson = GroundingResult(sources = listOf(wrong)).json()))
        val route = AnswerRoutingPolicy.decide("Find review", history = history, conversationId = 1)
        assertEquals(AnswerPath.WEB_GROUNDED, route.path)
        assertTrue(route.question.contains("batha", true)); assertFalse(route.question.contains("iPhone", true))
        assertEquals(10L, PublicTopics.resolve("Find review", history, 1)!!.userMessageId)
        assertTrue(route.reusedSources.isEmpty())
    }
    @Test fun castRatingReleaseAndSequelRetainIdentityWithoutPreviousWebEvidence() {
        for ((follow, intent) in listOf("Who acted in it?" to PublicTopicIntent.CAST, "What is its rating?" to PublicTopicIntent.RATING,
            "When was it released?" to PublicTopicIntent.RELEASE, "What about the sequel?" to PublicTopicIntent.SEQUEL, "Is it good?" to PublicTopicIntent.REVIEW)) {
            val topic = PublicTopics.resolve(follow, listOf(user(question)), 1)!!
            assertEquals(follow, intent, topic.intent)
            val route = AnswerRoutingPolicy.decide(follow, history = listOf(user(question)), conversationId = 1)
            assertEquals(follow, AnswerPath.WEB_GROUNDED, route.path)
            assertTrue(follow, route.question.contains("batha", true))
        }
    }
    @Test fun subjectlessCommandsClarifyAndNeverReachDiscovery() = runBlocking {
        var calls = 0
        val discovery = DiscoveryProvider { _, _ -> calls++; DiscoveryResult(emptyList(), DiscoveryState.EMPTY) }
        for (q in listOf("Find review", "search", "tell me more", "find sources", "What's the rating?")) {
            assertEquals(q, AnswerPath.CLARIFICATION, AnswerRoutingPolicy.decide(q).path)
            assertEquals("", ResearchQuery(q).searchText)
            assertTrue(ResearchPlanner.plan(q).searches.isEmpty())
            assertTrue(PublicWebResearch(discoveryOverride = discovery).search(q).isEmpty())
        }
        assertEquals(0, calls)
    }
    @Test fun conversationsAndExplicitTopicChangesCannotReviveAnOldFilm() {
        val history = listOf(user(question, 1), user("Explain recursion", 2))
        assertEquals(AnswerPath.CLARIFICATION, AnswerRoutingPolicy.decide("Find review", history = history, conversationId = 2).path)
        assertTrue(AnswerRoutingPolicy.decide("Find review", history = history, conversationId = 1).question.contains("batha", true))
        assertNull(PublicTopics.resolve("Find review", listOf(user(question), user("Write a poem about the sea")), 1))
        val changed = PublicTopics.resolve("How's Maharaja Tamil movie?", listOf(user(question)), 1)!!
        assertEquals("Maharaja", changed.subject)
    }
    @Test fun queryVariationsPreserveTitleAndLanguageWithoutGuessingAnotherFilm() {
        val query = AnswerRoutingPolicy.decide(question).question
        val searches = ResearchPlanner.plan(query).searches
        assertEquals(3, searches.size)
        assertTrue(searches.all { it.contains("batha", true) && it.contains("tamil", true) })
        assertFalse(ResearchQuery(query).candidateRelevant("iPhone 15 Pro Max review", "verified creator review rating"))
        assertFalse(ResearchQuery("find review").candidateRelevant("A verified creator review"))
    }
    @Test fun nearbyRomanisedSpellingIsAClarificationNotVerifiedEvidence() = runBlocking {
        val q = AnswerRoutingPolicy.decide(question).question
        val discovery = DiscoveryProvider { _, _ -> DiscoveryResult(listOf(ResearchCandidate("https://reviews.example/baththa", "Baththa movie review")), DiscoveryState.FOUND) }
        val research = PublicWebResearch(WebFetcher { error("A nonmatching spelling must not be read as the requested film") }, discovery)
        val result = GroundingCoordinator(mapOf(SearchProviderId.WEB to research)).retrieve(q, true, GroundingMode.ON, SearchProviderId.WEB)
        assertEquals("NEEDS_SUBJECT", result.status); assertTrue(result.sources.isEmpty())
        assertTrue(result.notice.contains("Baththa")); assertTrue(result.notice.contains("possible spelling"))
        assertFalse(ResearchQuery(q).candidateRelevant("Baththa movie review"))
        assertNull(PublicTopics.possibleSpelling(PublicTopics.parse(q)!!, "iPhone 15 Pro Max review"))
        val history = listOf(user(question), ChatMessageEntity(role = "assistant", content = result.notice, groundingStatus = "NEEDS_SUBJECT", conversationId = 1))
        assertEquals(AnswerPath.CLARIFICATION, AnswerRoutingPolicy.decide("Yes", mode = GroundingMode.ON, history = history, conversationId = 1).path)
        val confirmed = PublicTopics.resolve("Baththa Tamil film", history, 1)!!
        assertEquals("Baththa", confirmed.subject); assertEquals(PublicTopicIntent.REVIEW, confirmed.intent)
    }
    @Test fun filmIdentityAndReviewBodyAreRequiredAtEveryEvidenceBoundary() = runBlocking {
        val q = AnswerRoutingPolicy.decide(question).question
        val unrelated = source("iPhone 15 Pro Max review", "The iPhone 15 Pro Max has an excellent camera.")
        assertFalse(EvidenceRanking.acceptable(unrelated, ResearchPlanner.plan(q)))
        assertFalse(FactualResearch.usable(unrelated, q))
        assertEquals(AnswerSupport.INSUFFICIENT, EvidenceAnswer.resolve("The iPhone has a great camera [1].", listOf(unrelated), q).support)
        val provider = object : SearchProvider { override suspend fun search(question: String) = listOf(unrelated) }
        val result = GroundingCoordinator(mapOf(SearchProviderId.WEB to provider)).retrieve(q, true, GroundingMode.AUTO, SearchProviderId.WEB)
        assertEquals("INSUFFICIENT_EVIDENCE", result.status); assertTrue(result.sources.isEmpty())
        val castOnly = source(body = "Batha is a Tamil film directed by Example Director. Its cast includes Example Actor and Example Actress.")
        assertFalse(FactualResearch.usable(castOnly, q)); assertNull(SourceBrief.build(q, listOf(castOnly), emptyList()))
        assertTrue(FactualResearch.usable(source(), q))
        assertFalse(FactualResearch.usable(source(body = "Batha is a Hindi film. The critic rated Batha 4 stars for its compelling performances."), q))
    }
    @Test fun enabledAlwaysSearchChecksPublicFactsWhileOfflinePrivateAndWritingStayLocal() = runBlocking {
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("Explain recursion").path)
        assertEquals(AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide("Explain recursion", mode = GroundingMode.ON).path)
        for (q in listOf("Write a poem about the sea", "Hello", "Do not search. $question", "My password=synthetic123"))
            assertEquals(q, AnswerPath.LOCAL, AnswerRoutingPolicy.decide(q, mode = GroundingMode.ON).path)
        var calls = 0
        val provider = object : SearchProvider { override suspend fun search(question: String): List<EvidenceSource> { calls++; return listOf(source()) } }
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to provider))
        coordinator.retrieve(question, false, GroundingMode.ON, SearchProviderId.WEB)
        coordinator.retrieve(question, true, GroundingMode.OFF, SearchProviderId.WEB)
        assertEquals(0, calls)
    }
    @Test fun uncertaintyAndSearchRecommendationsAllowOnlyEligibleRecovery() {
        val local = AnswerRoutingPolicy.decide("Tell me about an unfamiliar public monument")
        assertTrue(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(local, "I'm not familiar with that monument. You could search online."))
        assertTrue(LocalAnswerUncertainty.needsResearch("I recommend checking IMDb for reviews."))
        assertFalse(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide(question, allowed = false), "I don't know."))
        assertFalse(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide("Write a story"), "I don't know."))
    }
    @Test fun unavailableOrUnidentifiedFilmGetsAnActionableMatchingSubjectNotice() {
        val route = AnswerRoutingPolicy.decide(question)
        val failure = AnswerRoutingPolicy.recover(route, ResearchFailure.INSUFFICIENT)
        assertNull(failure.localQuestion); assertTrue(failure.notice.contains("batha", true)); assertTrue(failure.notice.contains("release year"))
        assertFalse(failure.notice.contains("iPhone"))
        assertTrue(AnswerRoutingPolicy.recover(route, ResearchFailure.TIMEOUT).notice.contains("too long"))
    }
    @Test fun alwaysSearchDoesNotReuseAQueryCacheInsteadOfCheckingAgain() = runBlocking {
        var calls = 0
        val provider = object : SearchProvider { override suspend fun search(question: String): List<EvidenceSource> { calls++; return listOf(source()) } }
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to provider))
        val q = AnswerRoutingPolicy.decide(question).question
        repeat(2) { assertTrue(coordinator.retrieve(q, true, GroundingMode.ON, SearchProviderId.WEB).sources.isNotEmpty()) }
        assertEquals(2, calls)
    }
    @Test fun safeSummaryRetainsSupportedRatingAndPublisherWithoutRawRetrievalBlocks() {
        val q = AnswerRoutingPolicy.decide(question).question
        val result = EvidenceAnswer.resolve("Batha is perfect and has 5 stars [1].", listOf(source()), q)
        assertTrue(result.text, result.text.contains("2.5 stars")); assertTrue(result.text.contains("[1]"))
        assertTrue(result.text.contains("reviews.example")); assertFalse(Regex("(?<![\\d.])5 stars").containsMatchIn(result.text))
        assertFalse(result.text.contains("From the retrieved pages")); assertFalse(result.text.lines().any { it.startsWith(">") })
        assertFalse(AnswerSourceStatus.label("SOURCE_EXCERPTS").contains("excerpt", true))
    }
    @Test fun professionalReviewIsPreferredAndVerifiedProseKeepsTheFilmScope() {
        val q = AnswerRoutingPolicy.decide(question).question
        assertTrue(PublicTopics.parse(q)!!.searches().first().contains("critic reviews"))
        val critic = source("Batha movie review")
        val audience = source("Batha movie review: viewers response").copy(id = 2)
        assertTrue(EvidenceRanking.score(critic, ResearchPlanner.plan(q)) > EvidenceRanking.score(audience, ResearchPlanner.plan(q)))
        val result = EvidenceAnswer.resolve("The critic rated it 2.5 stars [1].", listOf(critic), q)
        assertTrue(result.text, result.text.contains("batha", true))
        assertTrue(result.text.contains("2.5 stars")); assertTrue(result.text.contains("[1]"))
    }
    @Test fun shortLabelledRatingDoesNotBecomeAnUnverifiableAnswerOrAnIconDump() {
        val q = AnswerRoutingPolicy.decide(question).question
        val html = "<title>Batha Tamil movie review</title><article><h1>Batha Tamil movie review</h1><p>" +
            "<img alt='rating icon'><img alt='rating icon'>Rating: 2 out of 5</p>" +
            "<p>Batha is a Tamil film with an uneven story and compelling performances.</p></article>"
        val page = WebPageExtractor.article(WebDocument("https://reviews.example/batha", html, "text/html"), 1, q)!!
        assertFalse(page.excerpt.contains("rating icon"))
        val rating = source().copy(excerpt = "Batha Tamil movie review\nRating: 2 out of 5")
        val answer = EvidenceAnswer.resolve("An unsupported uncited assessment.", listOf(rating), q)
        assertTrue(answer.text, answer.text.contains("2 out of 5")); assertTrue(answer.text.contains("[1]")); assertTrue(answer.text.contains("batha", true))
        assertNotEquals(AnswerSupport.INSUFFICIENT, answer.support)
        assertNull(MovieRatingEvidence.summary(q, listOf(rating.copy(excerpt = "Other Film review\nRating: 5 out of 5")), emptyList()))
    }
    @Test fun diagnosticsAreBoundedScopedAndOmitPrivateQueriesAndResponses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ResearchDiagnostics.clear(context)
        try {
            repeat(55) { ResearchDiagnostics.record(context, 7, it.toLong(), question, AnswerRoutingPolicy.decide(question), GroundingResult(), null, false) }
            val file = File(context.cacheDir, "research-diagnostics.json")
            val rows = JSONArray(file.readText())
            assertEquals(48, rows.length()); assertEquals(7L, rows.getJSONObject(0).getLong("conversationId"))
            assertTrue(rows.getJSONObject(0).getString("resolvedPublicQuery").contains("batha"))
            ResearchDiagnostics.record(context, 8, 99, "My password=never_log_this", AnswerRoutingPolicy.decide("My password=never_log_this"), GroundingResult(), null, false)
            assertFalse(file.readText().contains("never_log_this"))
            ResearchDiagnostics.removeConversation(context, 7)
            assertEquals(1, JSONArray(file.readText()).length())
        } finally { ResearchDiagnostics.clear(context) }
    }
}
