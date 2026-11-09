package com.example

import com.example.grounding.*
import com.example.data.ChatMessageEntity
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Same frozen fixture runner on baseline and current; only stable APIs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UniversalAnswerEvaluationTest {
    @Test fun evaluateFrozenPublicQuestions() {
        val cases = JSONArray(checkNotNull(javaClass.getResourceAsStream("/universal-answer-cases.json")).bufferedReader().use { it.readText() })
        assertTrue(cases.length() >= 100)
        val rows = JSONArray()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i); val q = c.getString("question"); val start = System.nanoTime()
            val kind = c.getString("kind"); val checks = JSONObject()
            when (kind) {
                "identity" -> { val query = ResearchQuery(q)
                    checks.put("acceptCorrectSubject", query.candidateRelevant(c.getString("good")))
                    checks.put("rejectWrongSubject", !query.candidateRelevant(c.getString("bad"))) }
                "route" -> checks.put("route", AnswerRoutingPolicy.decide(q).path.name == c.getString("expected"))
                "followup" -> {
                    val topic = PublicTopics.resolve(c.getString("followup"), listOf(ChatMessageEntity(role = "user", content = q, conversationId = 12)), 12)
                    checks.put("sessionIdentity", topic?.subject == c.getString("target"))
                }
                "evidence" -> {
                    val source = EvidenceSource(1, q, "https://fixture.example/fact", c.getString("body"), 1, LocalDate.now().toString(), "WEB_PAGE")
                    val answer = EvidenceAnswer.resolve("I cannot answer from memory.", listOf(source), q)
                    checks.put("usefulAnswer", answer.support != AnswerSupport.INSUFFICIENT && answer.text.contains(c.getString("target"), true))
                    checks.put("citation", GroundingPolicy.validateCitations(answer.text, listOf(source)).second)
                }
            }
            val passed = checks.keys().asSequence().all { checks.getBoolean(it) }
            rows.put(JSONObject().put("case", i).put("kind", kind).put("category", c.getString("category")).put("checks", checks)
                .put("passed", passed).put("latencyUs", (System.nanoTime() - start) / 1000))
        }
        val report = JSONObject().put("cases", cases.length()).put("passed", (0 until rows.length()).count { rows.getJSONObject(it).getBoolean("passed") })
            .put("rows", rows).put("scope", "Deterministic routing, entity and frozen-evidence checks; not measured LLM factual accuracy")
        val output = File(System.getenv("BI_EVAL_OUTPUT") ?: "build/reports/universal-answer-evaluation.json")
        output.parentFile.mkdirs(); output.writeText(report.toString(2))
        if (System.getenv("BI_EVAL_ENFORCE") == "true") for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            assertTrue(row.toString(), row.getBoolean("passed"))
        }
    }
}
