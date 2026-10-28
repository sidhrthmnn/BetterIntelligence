package com.example

import com.example.data.ChatMessageEntity
import com.example.grounding.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchReliabilityTest {
    private fun source(text: String, title: String = "London weather") = EvidenceSource(1, title, "https://weather.example/london", text, 1)

    @Test fun thereResolvesPublicPlaceAndChangesIntentWithoutSendingPrivateHistory() {
        val history = listOf(ChatMessageEntity(role = "user", content = "Private home address and account"),
            ChatMessageEntity(role = "assistant", content = "Untrusted old answer", sourcesJson = GroundingResult(sources = listOf(
                source("Weather", "London - BBC Weather"), source("Weather", "London (Greater London) weather"))).json()))
        val query = ConversationalResearch.question("Who is the mayor there", history)
        assertEquals("Who is the mayor in London", query)
        assertFalse(ConversationalResearch.isWeather(query))
        assertFalse(query.contains("Private")); assertFalse(query.contains("BBC"))
        assertEquals("What is the temperature in London?", ConversationalResearch.question("What is the temperature there?", history))
        assertTrue(GroundingPolicy.shouldSearch(true, GroundingMode.AUTO, "What is the temperature in London?"))
        assertTrue(com.example.engine.ChatAnswerPolicy.requiresLiveData("What is the temperature in London?"))
        assertNull(ResearchQuery("Who is the current mayor of London?").earliest)
        assertNull(ResearchQuery("Who is the current mayor of London?").freshnessFilter)
        assertNotNull(ResearchQuery("Current London election news").earliest)
        val otherChat = history + ChatMessageEntity(role = "user", content = "Other chat", conversationId = 9)
        assertEquals("Who is the mayor there", ConversationalResearch.question("Who is the mayor there", otherChat))
    }

    @Test fun visibleWeatherObservationsPreserveUnitsTimeAndForecastLabels() {
        val html = """<title>London - BBC Weather</title><main><h1>London</h1>
            <div class="WeatherObservationsContainer"><div class="ObservationsContainerText">Observations for Central Park. Observed at: 09:00 GMT Wednesday 28 October.</div>
            <div class="TemperatureReading">Temperature 11° Celsius11° Temperature 52° Fahrenheit52°</div></div>
            <a id="daylink-0">Today<div class="wr-day-temperature__high">High<span class="wr-value--temperature--c">16°</span><span>60°</span></div>
            <div class="wr-day-temperature__low">Low<span class="wr-value--temperature--c">8°</span></div></a>
            <section class="wr-c-weather-watchers"><h2>Weather Watchers photos nearby</h2><p>99° 110° Sunshine and showers in London.</p></section>
            <h2>Latest forecast for London</h2><p>Tonight will turn dry, with clear spells and a gentle breeze for most areas.</p></main>"""
        val read = WebPageExtractor.article(WebDocument("https://www.bbc.co.uk/weather/123", html, "text/html"), 1, "What is the weather in London?")!!
        assertTrue(read.excerpt, read.excerpt.contains("Observed temperature: 11 °C (52 °F)"))
        assertTrue(read.excerpt.contains("09:00 GMT")); assertTrue(read.excerpt.contains("Today forecast: high 16 °C; low 8 °C"))
        assertFalse(read.excerpt.contains("99°"))
        assertTrue(EvidenceRanking.acceptable(read, ResearchPlanner.plan("Weather in London today?")))
        assertNull(ResearchQuery("Weather in London today?").freshnessFilter)
        val prepared = GroundingPolicy.prepareEvidence("Weather in London?", listOf(read), 2300)
        assertTrue(prepared.sources.single().excerpt, EvidenceAnswer.hasTemperature(prepared.sources.single().excerpt))
    }

    @Test fun fallbackQuotesActualEvidenceAndDoesNotCertifyUncitedOrInventedModelText() {
        val s = source("London weather\nObserved at 09:00 GMT. Observed temperature: 11 °C.")
        for (draft in listOf("London is 99 °C [1].", "The temperature is eleven degrees.", "[99]")) {
            val answer = EvidenceAnswer.resolve(draft, listOf(s), "Weather in London?")
            assertTrue(answer.sourceExtract); assertEquals(AnswerSupport.PARTIAL, answer.support)
            assertTrue(answer.text.contains("> Observed at 09:00 GMT. Observed temperature: 11 °C. [1]"))
            assertFalse(answer.text.contains("99"))
        }
        assertEquals(AnswerSupport.INSUFFICIENT, EvidenceAnswer.resolve("It is sunny [99]", emptyList(), "Weather in London?").support)
        assertEquals(AnswerSupport.INSUFFICIENT, EvidenceAnswer.resolve("11 °C [1]", listOf(s), "Weather in Oslo?").support)
    }

    @Test fun missingTemperatureIsExplicitAndPhotoReadingsAreNotUsed() {
        val s = source("London maximum gust is 22 mph from the west southwest.")
        val answer = EvidenceAnswer.resolve("London maximum gust is 22 mph from the west southwest [1].", listOf(s), "Weather in London?")
        assertEquals(AnswerSupport.PARTIAL, answer.support)
        assertTrue(answer.text.contains("Temperature was not available"))
        assertFalse(answer.sourceExtract)
        assertEquals(AnswerSupport.INSUFFICIENT, EvidenceAnswer.resolve("99 °C", listOf(source("London weather photo: observed 99 °C today.")), "London weather?").support)
    }

    @Test fun extractsDoNotInventComparisonsOrSecretInformationOrRemoveConditions() {
        val s = source("Orion requires 4 GB RAM only with a compatible driver.", "Orion documentation")
        assertEquals(AnswerSupport.INSUFFICIENT, EvidenceAnswer.resolve("Vega is faster [1]", listOf(s), "Compare Orion vs Vega").support)
        assertEquals(AnswerSupport.INSUFFICIENT, EvidenceAnswer.resolve("Secret plan [1]", listOf(s), "What is Orion's secret unannounced plan?").support)
        val extracted = EvidenceAnswer.resolve("Unknown", listOf(s), "What RAM does Orion require?")
        assertTrue(extracted.text, extracted.text.contains("only with a compatible driver"))
    }

    @Test fun mayorSectionIsRankedAheadOfDifferentLordMayorAndZeroWidthNamesNormalize() {
        val html = """<title>Mayor and Lord Mayor</title><main><h1>Mayor and Lord Mayor</h1>
            <h2>The Lady Mayor, Dame Example</h2><p>The current Lady Mayor is Dame Example. Elected annually, the Lord Mayor is an ambassador.</p>
            <h2>The Mayor of London, Sa​diq Khan</h2><p>The Mayor of London leads the Greater London Authority. Sadiq Khan is responsible for strategic governance.</p></main>"""
        val s = WebPageExtractor.article(WebDocument("https://city.example/mayors", html, "text/html"), 1, "Who is the mayor in London?")!!
        assertTrue(s.excerpt, s.excerpt.indexOf("Sadiq Khan") < s.excerpt.indexOf("Dame Example"))
        val resolved = EvidenceAnswer.resolve("Sadiq Khan is Mayor of London.", listOf(s), "Who is the mayor in London?")
        assertTrue(resolved.sourceExtract); assertTrue(resolved.text.contains("Sadiq Khan")); assertFalse(resolved.text.contains("Dame Example"))
    }

    /** Optional diagnostics against captured public HTML. Fixtures above remain offline. */
    @Test fun capturedPublicPagesOptional() {
        val directory = System.getenv("BI_RELIABILITY_CAPTURE") ?: return
        val results = listOf("bi-city-mayor" to "Who is the mayor in London?", "bi-bbc-weather" to "What is the weather in London?").map { (name, question) ->
            val url = if (name.contains("bbc")) "https://www.bbc.co.uk/weather/2643743" else "https://www.cityoflondon.gov.uk/about-us/about-the-city-of-london-corporation/lord-mayor/lord-mayor-mayor-of-london"
            val s = WebPageExtractor.article(WebDocument(url, File(directory, "$name.html").readText(), "text/html"), 1, question)!!
            val resolved = EvidenceAnswer.resolve("Uncited draft", listOf(s), question)
            assertTrue(resolved.text, resolved.sourceExtract)
            if (name.contains("bbc")) assertTrue(resolved.text, EvidenceAnswer.hasTemperature(resolved.text))
            else assertTrue(resolved.text, resolved.text.contains("Sadiq Khan"))
            org.json.JSONObject().put("question", question).put("excerpt", s.excerpt).put("answer", resolved.text)
        }
        File(directory, "bi-reliability-captured.json").writeText(org.json.JSONArray(results).toString(2))
    }
}
