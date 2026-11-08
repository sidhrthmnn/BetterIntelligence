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
    @Test fun verifiedStreamingDateDoesNotAcquireFootersOrAnIncompleteCoverageTail() {
        val q="When is Road Atlas Hindi movie releasing on OTT?"
        val body="Road Atlas Hindi film starts streaming on November 18, 2026.\n\nRoad Atlas OTT › Daily Crossword\nFor reprint rights: Example Syndication Service."
        val s=source(body)
        val a=EvidenceAnswer.resolve("Road Atlas Hindi film starts streaming on November 18, 2026 [1].\n\nIt is the biggest hit ever [1].",listOf(s),q)
        assertEquals(a.text,AnswerSupport.CITED,a.support);assertTrue(a.text.contains("November 18, 2026"))
        for(junk in listOf("Crossword","Syndication","biggest hit","do not cover every")) assertFalse(a.text,a.text.contains(junk))
        assertTrue(GroundingPolicy.validateCitations(a.text,listOf(s)).second)
    }
    @Test fun unusableDraftStillExtractsAnActualStreamingDate() {
        val s=source("Road Atlas Hindi film starts streaming on November 18, 2026.")
        val a=EvidenceAnswer.resolve("unreadable output",listOf(s),"When is Road Atlas Hindi movie releasing on OTT?")
        assertEquals(a.text,AnswerSupport.CITED,a.support);assertTrue(a.text.contains("November 18, 2026"))
    }
    @Test fun expectedWindowCannotBecomeAnUnqualifiedConfirmedStreamingDate() {
        val s=source("Road Atlas Hindi film could start streaming on November 18, 2026. The OTT release date of Road Atlas Hindi film is not yet confirmed.")
        val a=EvidenceAnswer.resolve("Road Atlas Hindi film starts streaming on November 18, 2026 [1].",listOf(s),"When is Road Atlas Hindi movie releasing on OTT?")
        assertTrue(a.text,a.text.contains("could") || a.text.contains("not yet confirmed"))
        assertFalse(a.text,a.text.contains("film starts streaming"));assertTrue(GroundingPolicy.validateCitations(a.text,listOf(s)).second)
    }
    @Test fun reviewedFilmSpellingKeepsLanguageAndSequelNumberInResearch() {
        val route=AnswerRoutingPolicy.decide("When is dhrishyam 3 hindi releasing on ott")
        val topic=PublicTopics.parse(route.question)!!
        assertEquals("Drishyam 3",topic.subject);assertEquals("hindi",topic.language)
        assertTrue(route.question.contains("OTT"));assertTrue(topic.searches().all { it.contains("Drishyam 3") && it.contains("hindi") })
        assertFalse(topic.sourceMatches("Drishyam 3","Drishyam 3 is a Malayalam film starring Mohanlal.","https://publisher.example/d3"))
    }
    @Test fun pageControlsDoNotBecomeMovieFactsAndGenericTitlesNeedActualBodyIdentity() {
        assertTrue(PublisherBoilerplate.unrelatedFooter("Story › Daily Crossword\nFor reprint rights: Example Syndication Service.","When is the OTT release?"))
        assertFalse(PublisherBoilerplate.unrelatedFooter("Copyright © 2026 Example.","What is the copyright notice?"))
        val topic=PublicTopics.parse("How is Road Atlas Hindi movie?")!!
        assertFalse(topic.sourceMatches("Latest cinema coverage","The road is long. An atlas is useful. This is a Hindi film.","https://publisher.example/news"))
    }

    @Test fun sequelNumberDoesNotCountAsAStreamingDate() {
        val s=source("Road Atlas 3 is a Hindi film with OTT rights acquired by ExampleStream.").copy(title="Road Atlas 3 Hindi film")
        val a=EvidenceAnswer.resolve("Road Atlas 3 is a Hindi film with OTT rights acquired by ExampleStream [1].",listOf(s),"When is Road Atlas 3 Hindi movie releasing on OTT?")
        assertEquals(a.text,AnswerSupport.PARTIAL,a.support);assertTrue(a.text,a.text.contains(ReleaseAnswerScope.missingOtt))
    }

    @Test fun ottHeadingCannotTurnCinemaDateIntoStreamingDateAndPlatformAntecedentSurvives() {
        val s=source("Road Atlas OTT Release: Road Atlas Hindi film, which was released in theatres on September 3, 2026, is set for its digital debut.\n\nRoad Atlas OTT Release Date And Platform\nThe digital release announcement was made by ExampleStream through its social media account. According to the platform, Road Atlas Hindi film will begin streaming on November 18, 2026.")
        val a=EvidenceAnswer.resolve("unusable draft",listOf(s),"When is Road Atlas Hindi movie releasing on OTT?")
        assertTrue(a.text,a.text.contains("ExampleStream"));assertTrue(a.text,a.text.contains("November 18, 2026"))
        assertFalse(a.text,a.text.contains("September 3"));assertFalse(a.text.contains(ReleaseAnswerScope.missingOtt))
        assertTrue(a.text,GroundingPolicy.validateCitations(a.text,listOf(s)).second)
    }

    @Test fun movieEditionIsCapturedBeforePromptShrinkingAndNeverBorrowedFromAnotherLanguage() {
        val q="When is Road Atlas 3 Hindi movie releasing on OTT?"
        val html="<title>Road Atlas 3 OTT release</title><main><p>ExampleStream will premiere the Malayalam film Road Atlas 3 on June 18, following its May 21 theatrical release.</p><p>The director explores difficult choices as the family protects its secrets. A Hindi adaptation will arrive later with a different story.</p></main>"
        val full=WebPageExtractor.article(WebDocument("https://publisher.example/atlas3",html,"text/html"),1,q)!!
        assertEquals("malayalam",full.publicLanguage)
        assertEquals("malayalam",PublicTopics.primaryLanguage("Road Atlas OTT release","It is available in its original Malayalam language. A Hindi adaptation will arrive later."))
        val shrunk=full.copy(excerpt="Road Atlas 3 explores difficult family choices.")
        val topic=PublicTopics.parse(q)!!
        assertFalse(topic.sourceMatches(shrunk.title,shrunk.excerpt,shrunk.url,shrunk.publicLanguage))
        val round=GroundingResult.sources(GroundingResult(sources=listOf(shrunk)).json()).single()
        assertEquals(shrunk.publicLanguage,round.publicLanguage)
        assertTrue(topic.sourceMatches("Road Atlas 3 OTT update","Road Atlas 3 could start streaming in November.",shrunk.url,"hindi"))
    }
    @Test fun expectedStreamingWindowWinsOverUnrelatedFilmPlotInPartialSummary() {
        val s=source("Road Atlas is a Hindi crime thriller film.\n\nRoad Atlas OTT release\nRoad Atlas could start streaming on ExampleStream between mid and late November 2026.\n\nRoad Atlas story\nRoad Atlas follows a family that protects its secrets.")
        val a=EvidenceAnswer.resolve("unusable output",listOf(s),"When is Road Atlas Hindi movie releasing on OTT?")
        assertTrue(a.text,a.text.contains("could start streaming"));assertTrue(a.text.contains("November 2026"))
        assertFalse(a.text.contains("protects its secrets"));assertTrue(a.text,GroundingPolicy.validateCitations(a.text,listOf(s)).second)
    }
    @Test fun checkedComparisonsKeepWholeConditionedClaimsInTheirOwnRows() {
        val lines="Orion requires 4 GB RAM only with the vendor SDK [1].\n\nNova requires 6 GB RAM only on the tested device [2]."
        val sources=listOf(source(lines).copy(id=1),source(lines).copy(id=2))
        val formatted=AnswerPresentation.format(lines,"Compare Orion and Nova RAM requirements",sources)
        assertTrue(formatted,formatted.startsWith("| Item |"))
        assertTrue(formatted.contains("| Orion | Orion requires 4 GB"));assertTrue(formatted.contains("| Nova | Nova requires 6 GB"))
        assertTrue(formatted.contains("vendor SDK [1]"));assertTrue(formatted.contains("tested device [2]"))
    }
    @Test fun publisherFirstPersonStatementsAreAttributedAndWatermarksDoNotConsumeContext() {
        val s=source("The Swift running shoe is the best cheap shoe we've tested.").copy(url="https://www.rtings.com/shoes")
        val formatted=AnswerPresentation.format(s.excerpt+" [1]","Which shoe is good for running?",listOf(s))
        assertTrue(formatted,formatted.startsWith("rtings.com reports:"))
        assertEquals("Readable text.",PublisherBoilerplate.readable("Readable text."+"\u200b\u200c\u200d\ufeff".repeat(300)))
        assertEquals("ന\u200dമ",PublisherBoilerplate.readable("ന\u200dമ"))
    }
    @Test fun clockTimesAreComputedAtTheRequestedZoneAndNeverInventedByTheModel() {
        val now=java.time.Instant.parse("2026-11-08T00:00:00Z")
        val india=com.example.engine.DeviceClock.answer("What is the time in india",now,java.time.ZoneId.of("Europe/London"))!!
        assertTrue(india,india.contains("05:30") && india.contains("UTC+05:30"))
        assertTrue(com.example.engine.DeviceClock.answer("What time is it now",now,java.time.ZoneId.of("Europe/London"))!!.contains("00:00"))
        assertNull(com.example.engine.DeviceClock.answer("What time does Road Atlas start?",now))
        assertEquals(AnswerPath.LOCAL,AnswerRoutingPolicy.decide("What time is it now").path)
    }
    @Test fun reviewedTyposKeepQuestionIntentWithoutTreatingFilmLanguageAsAnInstruction() {
        assertTrue(ConversationalResearch.question("What's the wether in london",emptyList()).contains("weather"))
        assertTrue(ConversationalResearch.question("Who is the ownsr of L&R HOTELS",emptyList()).contains("owner"))
        assertTrue(ConversationalResearch.question("Antropic or openai have most number of users",emptyList()).contains("Anthropic"))
        assertFalse(ConversationalResearch.question("What is a wether?",emptyList()).contains("weather"))
        val policy=com.example.engine.ChatAnswerPolicy
        assertFalse(policy.system("When is Malayalam movie I'm Game releasing?",true,2048).contains("Reply in Malayalam"))
        assertTrue(policy.system("Explain gravity in Malayalam",false,2048).contains("Reply in Malayalam"))
        assertTrue(policy.system("Please reply in Manglish",false,2048).contains("Malayalam in Latin"))
    }

    @Test fun ongoingCastingQuestionPreservesTitleAfterMovieAndPresentIntent() {
        val route=AnswerRoutingPolicy.decide("Who is being cast in the movie Matrubhumi")
        val topic=PublicTopics.parse(route.question)!!
        assertEquals("Matrubhumi",topic.subject);assertTrue(topic.currentCasting)
        assertTrue(route.question,route.question.contains("being cast"));assertFalse(route.question.contains("starred"))
    }

    @Test fun explicitMovieEditionWinsOverAnUnidentifiedSameTitlePage() {
        val q="When is Road Atlas 3 Hindi movie releasing on OTT?"
        val unknown=source("The film Road Atlas 3 explores ethical dilemmas as a family protects its secrets.").copy(title="Road Atlas 3 OTT release")
        val hindi=source("Road Atlas 3 is a Hindi crime thriller film.\n\nRoad Atlas 3 OTT release\nA publisher report suggests Road Atlas 3 could start streaming on ExampleStream between mid and late November 2026.").copy(id=2,publicLanguage="hindi")
        assertEquals(listOf(2),PublicTopics.alignedSources(q,listOf(unknown,hindi)).map {it.id})
        assertEquals(listOf(unknown),PublicTopics.alignedSources(q,listOf(unknown)))
        val a=EvidenceAnswer.resolve("unusable draft",listOf(unknown,hindi),q)
        assertTrue(a.text,a.text.contains("ExampleStream") && a.text.contains("could"))
        assertFalse(a.text,a.text.contains("ethical dilemmas"));assertFalse(a.text.contains("[1]"))
    }

    @Test fun bareArticleTextBesideBreaksAndHeadingsRetainsStreamingWindow() {
        val q="When is Road Atlas 3 Hindi movie releasing on OTT?"
        val html="<title>Road Atlas 3 OTT update</title><main>Road Atlas 3 is the third chapter in the Hindi crime thriller franchise. It is currently playing in cinemas.<br><br><h2>Road Atlas 3 OTT release</h2>A publisher report suggests Road Atlas 3 could start streaming on ExampleStream between mid and late November 2026.<br><h2>What is Road Atlas 3 about?</h2>The family protects its secrets during a long investigation.</main>"
        val s=WebPageExtractor.article(WebDocument("https://publisher.example/atlas3",html,"text/html"),2,q)!!
        assertEquals("hindi",s.publicLanguage)
        assertFalse(s.excerpt,s.excerpt.contains("releaseA publisher"))
        val a=EvidenceAnswer.resolve("unusable draft",listOf(s),q)
        assertTrue(a.text,a.text.contains("ExampleStream") && a.text.contains("could"))
        assertFalse(a.text,a.text.contains("long investigation"))
        assertTrue(a.text,GroundingPolicy.validateCitations(a.text,listOf(s)).second)
    }

    @Test fun loanAmountRecoveryReportsCreditCapacityWithoutInventingDrawnDebt() {
        val q="How much loan does Orion have?"
        val s=source("Example Bank handed Orion a $520 million credit line. The facility was undrawn at closing.").copy(title="Orion credit facility",url="https://publisher.example/orion")
        val a=EvidenceAnswer.resolve("Orion's interest rate is 100 basis points [1].",listOf(s),q)
        assertEquals(a.text,AnswerSupport.PARTIAL,a.support)
        assertTrue(a.text,a.text.contains("$520 million credit line") && a.text.contains("undrawn"))
        assertTrue(a.text,a.text.contains("do not establish the current total outstanding debt"))
        assertFalse(a.text.contains("100 basis points"));assertTrue(GroundingPolicy.validateCitations(a.text,listOf(s)).second)
        assertFalse(GroundingPolicy.validateCitations(a.text.replace("page dated ${s.updatedAt}","page dated 2099-01-01"),listOf(s)).second)
        val unrelated=s.copy(excerpt="Example Bank handed Vega a $520 million credit line.")
        assertEquals(AnswerSupport.INSUFFICIENT,FinancialAnswerScope.recover(q,listOf(unrelated),CheckedAnswer("unusable",AnswerSupport.INSUFFICIENT,emptyList()))!!.support)
    }

    @Test fun whyQuestionsPreferActualCausesOverCaptionsAndFaqPromptsAreNotFacts() {
        val q="Why is the Orion phone cracking?"
        val html="<title>Why the Orion phone screen could be cracking</title><main><figure><img alt='Orion_208'><figcaption>A closeup of the Orion phone hinge mechanism.</figcaption></figure><p>A repair technician says the potential cause is due to pressure from the hinge against the flexible screen. The cause has not been confirmed by the manufacturer.</p><p>The Orion phone uses an unstacked logic board to keep it thin.</p></main>"
        val s=WebPageExtractor.article(WebDocument("https://publisher.example/orion",html,"text/html"),1,q)!!
        assertFalse(s.excerpt,s.excerpt.contains("Orion_208"));assertFalse(s.excerpt.contains("A closeup"))
        assertTrue(FactualResearch.answerFocusScore("The potential cause is due to hinge pressure.",q)>=65)
        assertEquals(0,FactualResearch.answerFocusScore("Orion phone uses an unstacked logic board.",q))
        assertTrue(FactualResearch.headlineOnly("Film cast › Questions & Answers\nWho stars alongside the lead actor?","Film cast"))
        assertTrue(FactualResearch.isCasting("Who is being cast in Matrubhumi film?"))
        val a=EvidenceAnswer.resolve("unusable output",listOf(s),q)
        assertTrue(a.text,a.text.contains("hinge") && a.text.contains("potential"))
        assertFalse(a.text.contains("unstacked logic board"))
    }

    @Test fun identityAnswersNameTheRequestedOwnerAndGlobalLeaderNotAnAcquiredCompanyOrRegion() {
        val owner="Who is the owner of L&R Hotels?"
        assertEquals(0,IdentityAnswerScope.focusScore("Supported by L+R Hotels, we helped Chardon's owners exit.",owner))
        val s=source("L+R Hotels is owned by brothers Ian Livingstone and Richard Livingstone.").copy(title="L+R Hotels ownership",url="https://publisher.example/l-r")
        assertEquals(100,IdentityAnswerScope.focusScore(s.excerpt,owner))
        assertEquals(100,IdentityAnswerScope.focusScore("Headquartered in London, L+R Hotels, owned by brothers Ian and Richard Livingstone, is a private investment company.",owner))
        assertTrue(ResearchPlanner.plan(owner).searches.any { it.contains("owned by") })
        val answer=EvidenceAnswer.resolve("Chardon's owners exited [1].",listOf(s),owner)
        assertTrue(answer.text,answer.text.contains("Ian Livingstone") && answer.text.contains("Richard Livingstone"))
        val q="Who is the richest person in the world right now?"
        assertEquals(0,IdentityAnswerScope.focusScore("Mukesh Ambani is Asia's richest person.",q))
        assertEquals(0,IdentityAnswerScope.focusScore("The richest person in the world has three times the wealth of the second.",q))
        val leader=source("Elon Musk is the richest person in the world according to the publisher's latest ranking.").copy(title="World wealth ranking")
        val a=EvidenceAnswer.resolve("The top person is wealthier than the second [1].",listOf(leader),q)
        assertTrue(a.text,a.text.contains("Elon Musk"))
    }

    @Test fun gluedPublisherQuestionsAreNotAnswersAndReportedFailuresOutrankUnrelatedExperiments() {
        val q="When is Road Atlas Hindi movie releasing on OTT?"
        val s=source("Reports say its OTT release might not be near.Road Atlas OTT release: When and where to watch?A publisher report suggests Road Atlas could start streaming on ExampleStream between mid and late November 2026.What is Road Atlas about?The family protects its secrets.")
        val a=EvidenceAnswer.resolve("unusable output",listOf(s),q)
        assertTrue(a.text,a.text.contains("could start streaming") && a.text.contains("ExampleStream"))
        assertFalse(a.text,a.text.contains("where to watch?"));assertFalse(a.text.contains("family protects"))
        val why="Why is the Orion phone cracking?"
        val experiment=source("Particles infiltrated the hinge, causing crunching, and that could lead to screen damage.").copy(title="Orion phone teardown")
        val failure=source("Reported Orion phone cracks may be due to pressure from a part of the hinge pushing against the inside screen.").copy(id=2,title="Why Orion phone screens crack")
        val result=EvidenceAnswer.resolve(experiment.excerpt+" [1]",listOf(experiment,failure),why)
        assertTrue(result.text,result.text.contains("pressure") && result.text.contains("may"))
        assertFalse(result.text,result.text.contains("Particles infiltrated"))
    }

    @Test fun castRepliesKeepNamedActorsWithoutComposersOrDoubleBullets() {
        val s=source("Alice Rowan stars in Road Atlas. Ben Stone is listed as a prominent star in Road Atlas. The music was composed by Mira Bell.")
        val a=EvidenceAnswer.resolve("*   **Alice Rowan** stars in Road Atlas [1].\n\n*   **Ben Stone** is listed as a prominent star in Road Atlas [1].\n\n*   The music was composed by Mira Bell [1].",listOf(s),"Who is being cast in Road Atlas Hindi film?")
        assertTrue(a.text,a.text.contains("Alice Rowan") && a.text.contains("Ben Stone"))
        assertFalse(a.text,a.text.contains("Mira Bell") || a.text.contains("- * "))
        assertTrue(a.text,GroundingPolicy.validateCitations(a.text,listOf(s)).second)
    }

    @Test fun financingAmountsSurviveFocusingAndCannotBeBorrowedFromAnInvestorOrHeadline() {
        val q="How much loan does Orion have?"
        val html="<title>Orion credit facility</title><main><p>Orion has secured a new $4 billion revolving credit facility. The facility was undrawn at closing.</p><p>Orion's interest rate is the overnight rate plus 100 basis points.</p></main>"
        val s=WebPageExtractor.article(WebDocument("https://publisher.example/orion",html,"text/html"),1,q)!!
        val focused=WebPageExtractor.focusExcerpt(s.excerpt,q,240,s.url)
        assertTrue(focused,focused.contains("$4 billion"))
        val own=source("We have established a new $4 billion credit facility. This is a revolving credit facility that was undrawn at closing.").copy(title="New credit facility",url="https://orion.example/news")
        assertEquals(100,FinancialAnswerScope.focusScore(own.excerpt,q,own.url))
        assertTrue(EvidenceRanking.acceptable(own,ResearchPlanner.plan(q)))
        val investor=source("Vega borrowed a $40 billion loan to buy a stake in Orion.").copy(id=2,title="Vega borrowing for Orion stake")
        assertEquals(0,FinancialAnswerScope.focusScore(investor.excerpt,q,investor.url))
        assertEquals(0,FinancialAnswerScope.focusScore("Orion has extended Vega a $5 billion loan.",q,"https://publisher.example/loan"))
        val result=EvidenceAnswer.resolve("unusable",listOf(own,investor),q)
        assertTrue(result.text,result.text.contains("$4 billion") && result.text.contains("undrawn"));assertFalse(result.text.contains("$40"))
        val terms=source("The loan is unsecured and can be tapped over three years.\n\nOrion's interest rate is 100 basis points.").copy(title="Orion gets $4 billion credit line")
        val partial=EvidenceAnswer.resolve("Orion's interest rate is 100 basis points [1].",listOf(terms),q)
        assertTrue(partial.text,partial.text.contains("do not establish"));assertFalse(partial.text.contains("$4 billion"));assertFalse(partial.text.contains("100 basis points"))
        assertTrue(partial.text,GroundingPolicy.validateCitations(partial.text,listOf(terms)).second)
        assertTrue(ResearchPlanner.plan(q).searches.any { it.contains("credit facility") })
        val qualified=source("Orion's books show zero debt and just $46 million of quarterly capital spending. The catch: there are $665 billion of commitments off the balance sheet.").copy(title="Orion balance sheet")
        val context=EvidenceAnswer.resolve("unusable",listOf(qualified),q)
        assertTrue(context.text,context.text.contains("commitments off the balance sheet"));assertTrue(context.text.contains("do not establish"))
        val debt=source("Orion has 4 billion dollars of outstanding debt.")
        val direct=EvidenceAnswer.resolve("unusable",listOf(debt),q)
        assertEquals(AnswerSupport.CITED,direct.support)
        assertTrue(direct.text,GroundingPolicy.validateCitations(direct.text,listOf(debt)).second)
    }

    @Test fun globalRankingParaphraseGetsAVisibleSourceDateWithoutLiteralRichestWording() {
        val q="Who is the richest person in the world right now?"
        val s=source("Tesla and SpaceX chief Elon Musk continued to hold the top position in the global billionaire rankings, with an estimated net worth of $878.5 billion.").copy(title="Asia's richest person update")
        val a=EvidenceAnswer.resolve("unusable",listOf(s),q)
        assertTrue(a.text,a.text.contains("Elon Musk") && a.text.contains("page dated ${s.updatedAt}"))
        assertTrue(a.text,GroundingPolicy.validateCitations(a.text,listOf(s)).second)
    }

}
