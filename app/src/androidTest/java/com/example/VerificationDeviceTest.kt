package com.example

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Identical drafts/evidence; baseline freezes v29 verification/recovery only.
 * Synthetic fixture facts are never represented as real publisher discoveries. */
class VerificationDeviceTest {
 private val context get()=ApplicationProvider.getApplicationContext<Context>()
 private fun sources(a:JSONArray)=(0 until a.length()).map { val s=a.getJSONObject(it); EvidenceSource(s.getInt("id"),s.getString("title"),s.getString("url"),s.getString("excerpt"),1,s.getString("updatedAt"),"WEB_PAGE","PUBLISHED") }
 @Test fun matchedVerificationCorpus() {
  check(context.packageName.endsWith(".validation"))
  val cases=JSONArray(InstrumentationRegistry.getInstrumentation().context.assets.open("verification-corpus.json").bufferedReader().use { it.readText() })
  val rows=JSONArray()
  try {
   for(i in 0 until cases.length()) {
    val c=cases.getJSONObject(i); val q=c.getString("question"); val draft=c.getString("draft"); val supplied=sources(c.getJSONArray("sources"))
    for(trial in 1..2) for(old in if(trial==1) listOf(true,false) else listOf(false,true)) {
     val start=SystemClock.elapsedRealtimeNanos()
     val checked=if(old) V29EvidenceAnswer.resolve(draft,supplied,q) else EvidenceAnswer.resolve(draft,supplied,q)
     val ms=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
     val raw=if(old) V29GroundingPolicy.checkAnswer(draft,supplied,q) else GroundingPolicy.checkAnswer(draft,supplied,q)
     val cited=checked.support!=AnswerSupport.INSUFFICIENT && (if(old) V29GroundingPolicy.validateCitations(checked.text,supplied) else GroundingPolicy.validateCitations(checked.text,supplied)).second
     rows.put(JSONObject().put("case",c.getString("id")).put("baseline",old).put("trial",trial).put("support",checked.support.name)
      .put("cited",cited).put("expectedFact",c.getString("expected").isNotEmpty() && checked.text.contains(c.getString("expected")))
      .put("elapsedMs",ms).put("sampledPssMb",Debug.getPss()/1024).put("draftChecks",VerificationDiagnostics.counts(raw)).put("finalChecks",VerificationDiagnostics.counts(checked)))
     if(!old) {
      assertEquals(c.getString("id")+": "+raw,c.getString("claimSupport"),raw.support.name)
      if(c.getString("expected").isNotEmpty()) { assertTrue(checked.text,checked.text.contains(c.getString("expected"))); assertTrue(checked.text,cited) }
     }
    }
   }
  } finally { File(context.filesDir,"verification-device.json").writeText(rows.toString(2)) }
 }
 @Test fun actualQwenDraftsComparedWithIdenticalPreparedEvidence():Unit=runBlocking {
  check(context.packageName.endsWith(".validation"))
  val core=InferenceEngine(context); val filename="Qwen3.5-2B-Q4_K_M.gguf"
  val model=ModelLibrary.read(context).single { it.filename==filename }
  val file=File(context.filesDir,"models/$filename"); assertTrue("Stage checksum-verified Qwen in the isolated installation",file.isFile)
  val rows=JSONArray()
  val evidence=listOf(EvidenceSource(1,"Orion requirements","https://orion.example/specs","Orion requires 4 GB RAM only with the vendor SDK. Orion does not support image input.",1,"2026-11-07","WEB_PAGE","PUBLISHED"),
   EvidenceSource(2,"Nova requirements","https://nova.example/specs","Nova requires 6 GB RAM only with the vendor SDK. Nova supports image input.",1,"2026-11-07","WEB_PAGE","PUBLISHED"))
  try {
   core.setProcessingMode(ProcessingMode.CPU);core.loadCustomGguf(file,model.sha256Checksum.orEmpty()).getOrThrow()
   for(q in listOf("What RAM does Orion require?","Does Orion support image input?","Compare Orion and Nova RAM requirements")) {
    val supplied=if(q.startsWith("Compare")) evidence else evidence.take(1)
    val prepared=GroundingPolicy.prepareEvidence(q,supplied,6000,1200,core::contextTokenCount)
    assertEquals(supplied.size,prepared.sources.size)
    val raw=core.generateSync(prepared.prompt,GenerationParams(contextSize=2048,maxTokens=160,temperature=0f,threads=4))
    val row=JSONObject().put("question",q).put("rawDraft",raw).put("promptTokens",core.contextTokenCount(prepared.prompt))
     .put("firstTokenMs",core.telemetry.value.timeToFirstTokenMs).put("tokensPerSecond",core.telemetry.value.tokensPerSecond)
    for(old in listOf(true,false)) {
     val start=SystemClock.elapsedRealtimeNanos()
     val checked=if(old) V29EvidenceAnswer.resolve(raw,prepared.sources,q) else EvidenceAnswer.resolve(raw,prepared.sources,q)
     val ms=(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
     val cited=checked.support!=AnswerSupport.INSUFFICIENT && (if(old) V29GroundingPolicy.validateCitations(checked.text,prepared.sources) else GroundingPolicy.validateCitations(checked.text,prepared.sources)).second
     row.put(if(old) "baseline" else "revised",JSONObject().put("answer",checked.text).put("support",checked.support.name).put("cited",cited).put("verificationMs",ms).put("checks",VerificationDiagnostics.counts(checked)))
     if(!old) { assertNotEquals(checked.text,AnswerSupport.INSUFFICIENT,checked.support); assertTrue(checked.text,cited) }
    }
    rows.put(row)
   }
  } finally { core.unloadActiveModel();File(context.filesDir,"verification-native.json").writeText(rows.toString(2)) }
 }
}
