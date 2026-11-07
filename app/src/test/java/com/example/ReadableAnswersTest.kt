package com.example

import com.example.data.ChatMessageEntity
import com.example.grounding.*
import com.example.ui.components.ChatMarkdown
import com.example.ui.components.MarkdownBlock
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Replays the failure shapes observed in the owner's phone chats. Publisher
 * facts below are fictional; no private conversation transcript is retained. */
@RunWith(RobolectricTestRunner::class)
class ReadableAnswersTest {
    private fun source(text: String) = EvidenceSource(1, "Road Atlas Hindi film", "https://publisher.example/atlas", text, 1, java.time.LocalDate.now().toString(), "WEB_PAGE", "PUBLISHED")
    @Test fun filmTitleAfterMovieLabelIsPublicEvenWithFirstPersonContraction() {
        val q = "When is malayalam movie i'm game releasing on ott"
        val topic = PublicTopics.parse(q)!!
        assertEquals("i'm game", topic.subject)
        val route = AnswerRoutingPolicy.decide(q)
        assertEquals(AnswerPath.WEB_GROUNDED, route.path)
        assertTrue(route.question, route.question.contains("OTT"))
        assertTrue(topic.searches().all { it.contains("OTT") })
    }
    @Test fun userDescriptionAndOttFollowupKeepActualTitleAndLanguage() {
        val history = listOf(ChatMessageEntity(role="user",content="When is malayalam movie i'm game releasing on ott"))
        val q = ConversationalResearch.question("Its a recent malayalm movie with an actor", history)
        assertTrue(q, q.contains("i'm game")); assertFalse(q.contains("recent malayalm film")); assertTrue(q.contains("OTT"))
        val next = ConversationalResearch.question("When will it release in ott platforms", history)
        assertTrue(next, next.contains("i'm game")); assertTrue(next.contains("OTT"))
    }
    @Test fun privateFirstPersonContextAndUnrelatedTurnsStayPrivate() {
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("I need help with my medical notes about a film").path)
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("I work on a film").path)
        val history = listOf(ChatMessageEntity(role="user",content="How is Road Atlas Hindi movie?"),ChatMessageEntity(role="user",content="Write a poem"))
        assertFalse(ConversationalResearch.question("When will it release in ott platforms", history).contains("Road Atlas"))
    }
    @Test fun cinemaDateCannotBeVerifiedAsOttDateAndUsefulRelatedFactSurvives() {
        val s = source("Road Atlas Hindi film was released in theaters on October 2, 2026.")
        val checked = EvidenceAnswer.resolve("Road Atlas Hindi film was released in theaters on October 2, 2026 [1].", listOf(s), "When is Road Atlas Hindi movie releasing on OTT?")
        assertEquals(AnswerSupport.PARTIAL, checked.support)
        assertTrue(checked.text, checked.text.contains("October 2, 2026"))
        assertTrue(checked.text, checked.text.contains(ReleaseAnswerScope.missingOtt))
        assertTrue(checked.text, GroundingPolicy.validateCitations(checked.text, listOf(s)).second)
    }
    @Test fun actualStreamingDateRemainsCited() {
        val s = source("Road Atlas Hindi film starts streaming on November 18, 2026.")
        val checked = EvidenceAnswer.resolve(s.excerpt + " [1]", listOf(s), "When is Road Atlas Hindi movie releasing on OTT?")
        assertEquals(AnswerSupport.CITED, checked.support)
        assertFalse(checked.text.contains(ReleaseAnswerScope.missingOtt))
    }
    @Test fun ambiguousCompanyIsClarifiedInsteadOfGivingThreeUnrelatedValues() {
        assertEquals(AnswerPath.CLARIFICATION, AnswerRoutingPolicy.decide("How much is on ai worth").path)
    }
    @Test fun genericPublisherTitleDoesNotHideExplicitFilmBodyIdentity() {
        val s = source("Road Atlas is a Hindi film. Critics praised the performances but found the pacing uneven.").copy(title="Latest cinema coverage")
        val topic = PublicTopics.parse("How is Road Atlas Hindi movie?")!!
        assertTrue(topic.sourceMatches(s.title, s.excerpt, s.url))
        assertFalse(topic.sourceMatches(s.title, "River Journey is a Tamil film. Critics praised the performances.", s.url))
    }
    @Test fun summariesDoNotAddGenericFailureApologyAndDoNotInventClaims() {
        val s = source("Road Atlas is a Hindi film. Critics praised the performances but found the pacing uneven.")
        val text = ReadableSourceSummary.build(listOf(s to s.excerpt), "How is Road Atlas Hindi movie?")!!
        assertTrue(text.contains("pacing uneven")); assertTrue(text.contains("[1]"))
        assertFalse(text.contains("couldn't verify")); assertFalse(text.contains("remaining details"))
    }
    @Test fun duplicateExtractsRetainLongerOriginalQualificationAndCitation() {
        val text = "Road Atlas released in theaters on October 2, 2026 [1].\n\nRoad Atlas released in theaters on October 2, 2026, only in selected cities [1]."
        val result = AnswerPresentation.paragraphs(text)
        assertEquals(1, result.split("\n\n").size); assertTrue(result.contains("only in selected cities")); assertTrue(result.contains("[1]"))
        assertEquals(2,AnswerPresentation.paragraphs("Temperature -4 C [1].\n\nTemperature +4 C [1].").split("\n\n").size)
    }
    @Test fun decorativePhotoLabelsAndButtonsAreExcludedFromArticleEvidence() {
        val html = "<title>Running shoes</title><main><h2>Budget running shoes</h2><img alt='Design Picture'><img alt='Stack Height Photo'><button>See all our test results</button><p>The Swift trainer is a budget running shoe. Our testers found its cushioning comfortable for easy daily runs.</p></main>"
        val s = WebPageExtractor.article(WebDocument("https://publisher.example/shoes", html, "text/html"),1,"Which shoe is good for running")!!
        assertTrue(s.excerpt.contains("cushioning comfortable")); assertFalse(s.excerpt.contains("Design Picture"));assertFalse(s.excerpt.contains("Stack Height Photo"));assertFalse(s.excerpt.contains("See all our test results"))
    }
    @Test fun partialRecommendationShowsSourceMeritsInsteadOfRepeatedProductName() {
        val s = source("The Swift trainer is a budget running shoe. Our testers found its cushioning comfortable for easy daily runs.").copy(title="Running shoe tests")
        val partial=CheckedAnswer("The source explicitly states that the Swift trainer is a budget running shoe [1].\n\nThe Swift trainer is a budget running shoe. [1]",AnswerSupport.PARTIAL,emptyList())
        assertEquals(1,AnswerPresentation.paragraphs(partial.text).split("\n\n").size)
        val result=EvidenceAnswer.resolve(partial.text + "\n\nSwift makes every runner faster [1].",listOf(s),"Which shoe is good for running?")
        assertTrue(result.text,result.text.contains("cushioning comfortable"))
        assertFalse(result.text.contains("every runner faster"));assertFalse(result.text.contains("do not cover every"))
        assertTrue(result.text,GroundingPolicy.validateCitations(result.text,listOf(s)).second)
    }
    @Test fun metadataImageIsPublisherProvidedBoundedAndRespectsImagePolicy() {
        val doc = Jsoup.parse("<meta property='og:image' content='/photos/trainer.png'><meta property='og:image:alt' content='Swift running shoe'>")
        val image = SourceImages.metadata(doc,"https://publisher.example/shoes",emptyList())!!
        assertEquals("https://publisher.example/photos/trainer.png",image.first)
        assertNull(SourceImages.metadata(doc,"https://publisher.example/shoes",listOf("noimageindex")))
        doc.selectFirst("meta[property=og:image]")!!.attr("content","http://localhost/private")
        assertNull(SourceImages.metadata(doc,"https://publisher.example/shoes",emptyList()))
    }
    @Test fun editorialCardsInsideOrderedListsRemainSeparateProseAndMetrics() {
        val html = "<title>Running shoes</title><main><ol><li><h2>Budget running shoes</h2><div>Swift Trainer Cushioning 7.3 Stability 8.4</div><a>See all our test results</a><p>The Swift trainer is a budget running shoe. Our testers found its cushioning comfortable for easy daily runs and regular weekly training.</p></li></ol></main>"
        val s = WebPageExtractor.article(WebDocument("https://publisher.example/shoes", html, "text/html"),1,"Which shoe is good for running")!!
        val prose = s.excerpt.split(Regex("\\n\\s*\\n")).first { it.contains("Our testers") }
        assertFalse(prose,prose.contains("Cushioning 7.3")); assertFalse(s.excerpt.contains("See all our test results"))
    }
    @Test fun imageMetadataRoundTripsAndOldChatsNeedNoMigration() {
        val old = source("Road Atlas is a Hindi film.")
        assertEquals("", GroundingResult.sources(GroundingResult(sources=listOf(old)).json()).single().imageUrl)
        val new = old.copy(imageUrl="https://publisher.example/atlas.png",imageAlt="Road Atlas poster")
        assertEquals(new,GroundingResult.sources(GroundingResult(sources=listOf(new)).json()).single())
    }
    @Test fun groupedCitationsHaveDistinctLinksAndCodeAndUnknownIdsStayUnlinked() {
        val text=ChatMarkdown.citations("**Sources** [1, 2, 99] and `[1]`",setOf(1,2))
        assertEquals(listOf("1","2"),text.getStringAnnotations("source",0,text.length).map { it.item })
    }
    @Test fun headingsSoftLinesTablesAndLiteralHashHaveReadableBoundedParsing() {
        val blocks=ChatMarkdown.parse("## Details\nSoftly wrapped\nparagraph.\n\n#hashtag\n\n| Fact | Source |\n|---|---|\n| RAM | [1] |")
        assertEquals(2,blocks.filterIsInstance<MarkdownBlock.Heading>().single().level)
        assertEquals("Softly wrapped paragraph.",blocks.filterIsInstance<MarkdownBlock.Paragraph>().first().text)
        assertTrue(blocks.filterIsInstance<MarkdownBlock.Paragraph>().any { it.text=="#hashtag" })
        assertEquals(2,blocks.filterIsInstance<MarkdownBlock.Table>().single().rows.size)
    }
    @Test fun imageFetchChecksPermissionForEveryRedirectAndRejectsHtml() = runBlocking {
        for(redirect in listOf(true,false)) ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()).use { server ->
            val responder=thread(isDaemon=true) { server.accept().use { socket ->
                val input=socket.getInputStream().bufferedReader(); while(!input.readLine().isNullOrEmpty()) {}
                socket.getOutputStream().write((if(redirect) "HTTP/1.1 302 Found\r\nLocation: /private\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" else "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello").toByteArray())
            } }
            var guards=0
            val result=runCatching { SearchHttp(okhttp3.OkHttpClient.Builder().followRedirects(false).build(),true).fetchImage("http://localhost:${server.localPort}/photo") { guards++; !it.endsWith("private") } }
            assertTrue(result.isFailure);assertEquals(if(redirect) 2 else 1,guards);responder.join(2000)
        }
        assertTrue(runCatching { SearchHttp().fetchImage("https://127.0.0.1/photo") { true } }.isFailure)
        assertTrue(runCatching { SourceImages.load(source("x").copy(imageUrl="https://publisher.example/a.png")) { false } }.isFailure)
    }
}
