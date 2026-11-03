package com.example

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.engine.*
import com.example.grounding.*
import com.example.rustcore.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real JNI tests. Synthetic policy/resource fixtures never claim hardware execution. */
@RunWith(AndroidJUnit4::class)
class RustIntelligenceDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun isolatedNativeCore() {
        check(context.packageName.endsWith(".validation"))
        RustIntelligence.forceLegacyForTest = false
        assertEquals("Packaged JNI library did not initialize", IntelligenceMode.RUST, RustIntelligence.mode)
        assertEquals(1, RustIntelligence.call("info")!!.getInt("version"))
    }
    @After fun restoreMode() { RustIntelligence.forceLegacyForTest = false }

    private fun request(op: String, args: JSONObject = JSONObject()) = checkNotNull(RustIntelligence.call(op, args))

    @Test fun nativeProtocolRejectsMalformedOversizedAndUnknownRequestsWithoutEcho() {
        for (bytes in listOf("not json".toByteArray(), ByteArray(262145) { 1 },
            """{"version":99,"op":"info"}""".toByteArray(), """{"version":1,"op":"unsupported","args":{"private":"never echo"}}""".toByteArray())) {
            val response = JSONObject(NativeIntelligence.dispatch(bytes).toString(Charsets.UTF_8))
            assertFalse(response.getBoolean("ok")); assertFalse(response.toString().contains("never echo"))
        }
        assertEquals(IntelligenceMode.RUST, RustIntelligence.mode)
    }

    @Test fun actualJniAdmissionProtectsCancellationOwnershipAndOldStreams() {
        val owner = 987654321L
        val lease = request("acquire", JSONObject().put("owner", owner)).getLong("lease")
        try {
            assertTrue(request("acquire", JSONObject().put("owner", owner + 1)).isNull("lease"))
            assertTrue(request("accept", JSONObject().put("owner", owner).put("lease", lease).put("sequence", 0)).getBoolean("accepted"))
            assertFalse(request("accept", JSONObject().put("owner", owner).put("lease", lease).put("sequence", 0)).getBoolean("accepted"))
            assertFalse(request("finish", JSONObject().put("owner", owner + 1).put("lease", lease)).getBoolean("finished"))
            request("cancel", JSONObject().put("owner", owner).put("lease", lease))
            assertFalse(request("accept", JSONObject().put("owner", owner).put("lease", lease).put("sequence", 1)).getBoolean("accepted"))
        } finally { assertTrue(request("finish", JSONObject().put("owner", owner).put("lease", lease)).getBoolean("finished")) }
        assertFalse(request("info").getBoolean("busy"))
    }

    @Test fun primaryFallbackRecoveryAndMissingStatusUseActualRustPlanner(): Unit = runBlocking {
        val primary = model(910001, "Primary", 200)
        val fallback = model(910002, "Secondary", 50)
        val profile = HardwareAnalyzer.analyze(context).copy(totalRamMb = 12000, availableRamMb = 9000, safeModelRamLimitMb = 8000, isLowMemory = false)
        RustModelPolicy.reset(primary); RustModelPolicy.reset(fallback)
        try {
            assertEquals("UNINITIALIZED", request("model_status", JSONObject().put("id", RustModelPolicy.key(primary))).getString("state"))
            val ready = RustModelPolicy.choose(primary, fallback, null, profile, 512, true, true, false)
            assertEquals(primary.id, ready.model!!.id)
            RustModelPolicy.outcome(primary, java.io.IOException("Synthetic transient failure"), 1000)
            val degraded = RustModelPolicy.choose(primary, fallback, primary, profile, 512, true, true, false)
            assertEquals(ModelTierRole.SECONDARY_FALLBACK, degraded.role)
            assertEquals(fallback.id, degraded.model!!.id)
            val status = request("model_status", JSONObject().put("id", RustModelPolicy.key(primary)))
            val wait = status.getLong("retry_at_ms") - SystemClock.elapsedRealtime() + 20
            delay(wait.coerceIn(0, 3000))
            val restored = RustModelPolicy.choose(primary, fallback, fallback, profile, 512, true, true, false)
            assertEquals(primary.id, restored.model!!.id)
            assertEquals(ModelTierRole.PRIMARY, restored.role)
            assertEquals(ModelTierRole.NONE, RustModelPolicy.choose(primary, fallback, primary, profile, 512, true, false, true).role)
        } finally { RustModelPolicy.reset(primary); RustModelPolicy.reset(fallback) }
    }

    @Test fun rustAndLegacyAgreeAcrossRoutingCorpusAndPoliteTasks() {
        val before = RustIntelligence.diagnostics().getLong("calls")
        val corpus = InstrumentationRegistry.getInstrumentation().context.assets.open("answer-routing-corpus.tsv").bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t', limit = 3).last() }
        val cases = corpus + listOf("Could you write a poem about today's news?", "Can you explain recursion?", "Who are you?", "Thanks",
            "Please translate this: current weather", "Do not search. Who plays Doom?", "My project password is secret=example123456789")
        for (q in cases) {
            RustIntelligence.forceLegacyForTest = true
            val legacy = AnswerRoutingPolicy.decide(q)
            RustIntelligence.forceLegacyForTest = false
            val native = AnswerRoutingPolicy.decide(q)
            assertEquals(q, legacy.path, native.path); assertEquals(q, legacy.privacyBlocked, native.privacyBlocked)
            assertEquals(q, legacy.question, native.question)
        }
        assertTrue(RustIntelligence.diagnostics().getLong("calls") > before)
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("Could you write a poem about today's news?").path)
        assertEquals(AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide("What is the latest Android release?").path)
    }

    @Test fun contextSelectionHasLegacyParityForUnicodeBudgetsAnchorsAndCorrections() {
        val summary = ConversationContext.summaryHeader + "\n" + (0..20).joinToString("\n") {
            if (it == 0) "User said: \"My project is Orion 🌈.\"" else "User said: \"Decision $it: use violet, preserve café context.\""
        }
        for (budget in listOf(0, 128, 256, 512, 2048, 4096)) for (q in listOf("What is my project?", "Which decision is latest?", "Continue it")) {
            RustIntelligence.forceLegacyForTest = true
            val legacy = ConversationContext.summaryWithinBudget(summary, budget, q)
            RustIntelligence.forceLegacyForTest = false
            assertEquals("$budget / $q", legacy, ConversationContext.summaryWithinBudget(summary, budget, q))
        }
    }

    @Test fun focusedHtmlAndCitationsKeepLegacyParityAndRejectPublisherControls() {
        val q = "Compare Orion and Nova memory requirements"
        val html = "<title>Orion and Nova memory</title><main><h1>Orion and Nova memory</h1><nav>irrelevant navigation</nav>" +
            (1..40).joinToString("") { "<p>Unrelated archive entry $it about gardening, rooms and flowers.</p>" } +
            "<h2>Orion</h2><p>Orion requires 4 GB of shared memory only on the tested device.</p>" +
            "<h2>Nova</h2><p>Nova requires 6 GB of shared memory only with the vendor runtime.</p></main>"
        val page = WebDocument("https://publisher.example/memory", html, "text/html")
        RustIntelligence.forceLegacyForTest = true
        val baseline = WebPageExtractor.article(page, 1, q)!!
        RustIntelligence.forceLegacyForTest = false
        val native = WebPageExtractor.article(page, 1, q)!!
        assertEquals(baseline.excerpt, native.excerpt)
        assertTrue(native.excerpt.contains("4 GB")); assertTrue(native.excerpt.contains("6 GB"))
        val result = EvidenceAnswer.resolve("An unsupported definitive conclusion.", listOf(native), q)
        assertTrue(result.text.contains("[1]")); assertFalse(result.text.contains("unsupported definitive"))
        val denied = WebPageExtractor.article(WebDocument("https://publisher.example/blocked", "<title>Verify you are human</title><p>CAPTCHA</p>", "text/html"), 1, q)
        assertNull(denied)
        assertFalse(PublicWebDns.isPublic(java.net.InetAddress.getByName("127.0.0.1")))
    }

    @Test fun roomPersistenceMemoryDeletionAndConversationIsolationRemainCompatible(): Unit = runBlocking {
        val name = "rust-compat-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val first = db.conversationDao().insert(ConversationEntity(title = "Synthetic first"))
            val second = db.conversationDao().insert(ConversationEntity(title = "Synthetic second"))
            db.chatDao().insertMessage(ChatMessageEntity(role = "user", content = "Synthetic Orion note", conversationId = first))
            db.conversationDao().saveContext(first, "User said: Orion", 1)
            val memory = db.memoryDao().insertMemory(MemorySnippetEntity(title = "Synthetic preference", content = "Use violet", category = "Test", sourceApp = context.packageName))
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals(1, db.chatDao().messageSnapshot(first).size)
            assertTrue(db.chatDao().messageSnapshot(second).isEmpty())
            assertEquals("User said: Orion", db.conversationDao().get(first)!!.contextSummary)
            assertEquals(1, db.memoryDao().getActiveMemoriesBounded(2).size)
            assertEquals(1, db.memoryDao().searchActiveMemoriesBounded("violet", 2).size)
            assertTrue(db.memoryDao().searchActiveMemoriesBounded("unrelated", 2).isEmpty())
            db.memoryDao().deleteMemory(memory)
            assertTrue(db.memoryDao().getActiveMemoriesBounded(2).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun realOfflineGgufInferenceCancellationReuseAndModeComparison(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rustInference") == "true")
        val file = File(context.filesDir, "models/SmolLM2-135M-Instruct-Q8_0.gguf")
        assertTrue("Stage the checksum-verified GGUF in the validation package first", file.isFile)
        val engine = InferenceEngine(context)
        val rows = JSONArray()
        engine.setProcessingMode(ProcessingMode.CPU)
        val beforeLoad = SystemClock.elapsedRealtime()
        engine.loadCustomGguf(file).getOrThrow()
        val loadMs = SystemClock.elapsedRealtime() - beforeLoad
        try {
            val params = GenerationParams(temperature = 0f, contextSize = 512, maxTokens = 16, threads = 4)
            engine.generateSync("Count from one to ten.", params) // warmup
            var reference: String? = null
            for (legacy in listOf(true, false, false, true, false, true)) {
                RustIntelligence.forceLegacyForTest = legacy
                val start = SystemClock.elapsedRealtime()
                val text = engine.generateSync("Count from one to ten.", params)
                assertTrue(text.isNotBlank()); if (reference == null) reference = text else assertEquals(reference, text)
                val t = engine.telemetry.value
                assertEquals("CPU", t.activeBackend); assertEquals(0, t.activeGpuLayers)
                rows.put(JSONObject(t.toJson()).put("engineMode", if (legacy) "LEGACY" else "RUST")
                    .put("elapsedMs", SystemClock.elapsedRealtime() - start).put("modelLoadMs", loadMs))
            }
            RustIntelligence.forceLegacyForTest = false
            val gotToken = CompletableDeferred<Unit>()
            val job = engine.generateStream("Write a long story about a forest.", params.copy(maxTokens = 256), object : StreamTokenListener {
                override fun onToken(token: String, tokenIndex: Int) { gotToken.complete(Unit) }
                override fun onComplete(fullText: String, latencyMs: Long, tokensPerSec: Float, promptTokens: Int, completionTokens: Int) {}
                override fun onError(errorCode: Int, errorMessage: String) { if (!gotToken.isCompleted) gotToken.completeExceptionally(IllegalStateException(errorMessage)) }
            })
            withTimeout(30_000) { gotToken.await() }; engine.cancelGeneration(); job.cancelAndJoin()
            assertTrue("The native streaming job must actually observe cancellation", job.isCancelled)
            assertFalse(engine.telemetry.value.isRunning); assertFalse(request("info").getBoolean("busy"))
            assertTrue(engine.generateSync("Say hello.", params).isNotBlank())
            engine.unloadActiveModel(); assertFalse(engine.isReady())
            engine.loadCustomGguf(file).getOrThrow(); assertTrue(engine.generateSync("Say hello.", params).isNotBlank())
        } finally {
            RustIntelligence.forceLegacyForTest = false; engine.unloadActiveModel()
            File(context.filesDir, "rust-inference-comparison.json").writeText(rows.toString(2))
        }
    }

    private fun model(id: Long, name: String, mb: Long) = ModelEntity(id = id, name = name, filename = "fixture-$id.gguf",
        architecture = "llama", quantization = "Q4_K_M", parameterCount = "0.1B", contextLength = 2048,
        fileSizeMb = mb, ramRequiredMb = (mb + 512).toInt(), isInstalled = true, isActive = false, isCustom = true, description = "Synthetic policy fixture")

    @Test fun measuredPolicyAndExtractionComparison() {
        val html = "<title>Orion memory requirements</title><article><h1>Orion memory requirements</h1>" +
            (0..100).joinToString("") { i -> "<h2>Section $i</h2><p>Orion requires ${4 + i % 3} GB of shared memory only with the tested runtime, with section $i describing its configuration.</p>" } + "</article>"
        val page = WebDocument("https://publisher.example/orion", html, "text/html")
        val rows = JSONArray()
        var reference: String? = null
        for (legacy in listOf(true, false, false, true)) {
            RustIntelligence.forceLegacyForTest = legacy
            repeat(4) { WebPageExtractor.article(page, 1, "Orion memory requirements") }
            val samples = ArrayList<Long>()
            repeat(20) {
                val start = System.nanoTime()
                val excerpt = WebPageExtractor.article(page, 1, "Orion memory requirements")!!.excerpt
                samples += System.nanoTime() - start
                if (reference == null) reference = excerpt else assertEquals(reference, excerpt)
            }
            samples.sort()
            rows.put(JSONObject().put("mode", if (legacy) "LEGACY" else "RUST").put("samples", 20)
                .put("medianExtractionMs", samples[10] / 1_000_000.0).put("maxExtractionMs", samples.last() / 1_000_000.0))
        }
        RustIntelligence.forceLegacyForTest = false
        val bridge = ArrayList<Long>()
        repeat(200) { val start = System.nanoTime(); request("info"); bridge += System.nanoTime() - start }
        bridge.sort()
        val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        File(context.filesDir, "rust-policy-comparison.json").writeText(JSONObject().put("extraction", rows)
            .put("bridgeInfoMedianMicros", bridge[100] / 1000.0).put("sampledProcessPssMb", memory.totalPss / 1024.0)
            .put("scope", "Same-build internal LEGACY/RUST policies; native library resident in both modes. Synthetic 101-paragraph HTML, no HTTP or inference.").toString(2))
    }
}
