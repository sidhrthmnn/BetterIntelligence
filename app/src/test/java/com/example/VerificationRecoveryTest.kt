package com.example
import com.example.grounding.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
class VerificationRecoveryTest {
 @Test fun identicalV29EvidenceAndDraftsRecoverWithoutAcceptingBadClaims() {
  val cases = JSONArray(javaClass.classLoader!!.getResource("verification-corpus.json")!!.readText())
  val rows = JSONArray()
  for(i in 0 until cases.length()) {
   val c=cases.getJSONObject(i); val sources=readSources(c.getJSONArray("sources")); val q=c.getString("question"); val draft=c.getString("draft")
   val raw=GroundingPolicy.checkAnswer(draft,sources,q)
   val before=V29EvidenceAnswer.resolve(draft,sources,q); val after=EvidenceAnswer.resolve(draft,sources,q)
   assertEquals(c.getString("id")+": "+raw, c.getString("claimSupport"),raw.support.name)
   val expected=c.getString("expected")
   if(expected.isNotEmpty()) {
    assertTrue(c.getString("id")+": "+after.text,after.text.contains(expected))
    assertTrue(after.text,GroundingPolicy.validateCitations(after.text,sources).second)
    assertEquals(c.getString("id")+": "+after.text,if(c.getBoolean("complete")) AnswerSupport.CITED else AnswerSupport.PARTIAL,after.support)
   } else assertNotEquals(c.getString("id")+": "+after.text,AnswerSupport.CITED,after.support)
   rows.put(JSONObject().put("case",c.getString("id")).put("baseline",before.support.name).put("revised",after.support.name).put("claims",VerificationDiagnostics.counts(raw)))
  }
  println("VERIFICATION_COMPARISON="+rows)
 }
 @Test fun directionAndUnitsCannotBeReversedByParaphrases() {
  val s=EvidenceSource(1,"France capital","https://example.com/france","Paris is the capital of France.",1)
  assertEquals(AnswerSupport.CITED,GroundingPolicy.checkAnswer("The capital of France is Paris [1].",listOf(s),"What is the capital of France?").support)
  assertEquals(AnswerSupport.INSUFFICIENT,GroundingPolicy.checkAnswer("France is the capital of Paris [1].",listOf(s),"What is the capital of France?").support)
  val ram=s.copy(title="Orion memory",excerpt="Orion requires 4 GB RAM.")
  assertEquals(AnswerSupport.INSUFFICIENT,GroundingPolicy.checkAnswer("Orion requires 4 MB RAM [1].",listOf(ram),"What RAM does Orion require?").support)
 }
 @Test fun diagnosticExportOnlyIncludesBoundedAggregateCounts() {
  val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
  val file=java.io.File(context.cacheDir,"research-diagnostics.json")
  try {
   file.writeText("[{\"research\":{},\"claimChecks\":{\"generatedClaims\":999999,\"privatePrompt\":\"never_export_this\"}}]")
   val report=ResearchDiagnostics.export(context)
   assertFalse(report.contains("never_export_this")); assertFalse(report.contains("privatePrompt"))
   assertEquals(100,org.json.JSONObject(report).getJSONArray("reports").getJSONObject(0).getJSONObject("claimChecks").getInt("generatedClaims"))
  } finally { ResearchDiagnostics.clear(context) }
 }
 companion object {
  fun readSources(a:JSONArray)=(0 until a.length()).map { val s=a.getJSONObject(it); EvidenceSource(s.getInt("id"),s.getString("title"),s.getString("url"),s.getString("excerpt"),1,s.getString("updatedAt"),"WEB_PAGE","PUBLISHED") }
 }
}
