package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.engine.*
import com.example.service.*
import com.example.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Explicit opt-in; only the separate validation package is modified. */
class AutomaticSetupDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName.endsWith(".validation")) }
    private val repo get() = AiCoreRepository.getInstance(context)
    private val vm get() = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]

    @Test fun setupConsentAndPartialDeletion(): Unit = runBlocking {
        assumeTrue(args.getString("automaticSetupUi") == "true")
        repo.awaitModelInitialization()
        val cancelledToken = repo.automaticSetup.approvalToken()
        repo.automaticSetup.cancelPending()
        assertNotEquals(cancelledToken, repo.automaticSetup.approvalToken())
        val stale = runCatching { repo.automaticSetup.approve(true, epoch = cancelledToken) }.exceptionOrNull()
        assertTrue(stale?.message.orEmpty(), stale?.message?.contains("cancelled") == true)
        compose.runOnUiThread { vm.selectTab(AppTab.OPTIMISE) }
        compose.waitUntil(30_000) { vm.automaticSetup.value.device != null }
        compose.onNodeWithTag("automatic_setup_list").assertIsDisplayed()
        compose.onNodeWithTag("recheck_device").performClick()
        if (vm.automaticSetup.value.pair?.primary != null) {
            compose.onNodeWithTag("automatic_setup_list").performScrollToNode(hasTestTag("automatic_setup_approve"))
            compose.onNodeWithTag("automatic_setup_approve").performClick()
            compose.onNodeWithText("Download selected models?").assertIsDisplayed()
            compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
            assertFalse(repo.automaticSetup.state.value.pending)
            assertTrue(DownloadQueueStore(context).ids().isEmpty())
            val reviewed = checkNotNull(repo.automaticSetup.state.value.pair)
            val changed = reviewed.copy(primary = checkNotNull(reviewed.primary).copy(sha256Checksum = "0".repeat(64)))
            val refusal = runCatching { repo.automaticSetup.approve(true, changed) }.exceptionOrNull()
            assertNotNull("Changed model metadata must require another review", refusal)
            assertTrue(DownloadQueueStore(context).ids().isEmpty())
        }
        val dao = AppDatabase.getDatabase(context).modelDao()
        val model = repo.allModels.first().first { !it.isInstalled && it.filename.endsWith(".gguf") }
        val part = File(repo.downloadManager.modelsDirectory, "${model.filename}.part")
        part.writeBytes(ByteArray(8192) { 7 })
        dao.updateModel(model.copy(downloadState = "PAUSED", downloadProgress = .1f))
        compose.runOnUiThread { vm.selectTab(AppTab.MODELS) }
        compose.onNodeWithTag("model_source_library").performClick()
        compose.onNodeWithTag("models_list").performScrollToNode(hasTestTag("delete_partial_${model.id}"))
        compose.onNodeWithTag("delete_partial_${model.id}").performClick()
        compose.onNodeWithTag("confirm_delete_partial").performClick()
        compose.waitUntil(15_000) { !part.exists() && vm.allModels.value.any { it.id == model.id && it.downloadState == "NOT_DOWNLOADED" } }
        assertFalse(part.exists())
        // A completed, checksum-verified installation must survive deletion of a stale partial.
        repo.allModels.first().firstOrNull { it.isInstalled && !it.isActive }?.let { installed ->
            val complete = File(checkNotNull(installed.localFilePath)); val size = complete.length()
            val unfinished = File(repo.downloadManager.modelsDirectory, "${installed.filename}.part")
            unfinished.writeBytes(byteArrayOf(1, 2, 3))
            repo.deletePartialDownload(installed.id)
            assertFalse(unfinished.exists()); assertEquals(size, complete.length())
            assertTrue(repo.verifyModelChecksum(installed).isMatch)
        }
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "automatic-setup-ui.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }; screen.recycle()
    }

    @Test fun actualNativeQualityAndManualRollback(): Unit = runBlocking {
        assumeTrue(args.getString("automaticNative") == "true")
        repo.awaitModelInitialization()
        val requested = args.getString("automaticModels")?.split(',')
        val models = repo.allModels.first().filter { it.isInstalled && (requested?.contains(it.filename) ?: (it.parameterCount in setOf("0.5B", "360M"))) }
        assertEquals("Stage the requested existing local fixtures first", requested?.size ?: 2, models.size)
        repo.engine.setProcessingMode(ProcessingMode.CPU)
        repo.switchActiveModel(models.first())
        val manual = repo.preferencesRepository.primaryModelId.first()
        val events = JSONArray()
        val reports = repo.evaluateSetupModels(models, true) { stage ->
            events.put(stage)
            File(context.filesDir, "automatic-benchmark-events.json").writeText(events.toString(2))
        }
        File(context.filesDir, "automatic-native-benchmarks.json").writeText(JSONArray().apply { reports.forEach { put(it.json()) } }.toString(2))
        assertTrue("Both required small models must complete; optional larger models may fail safely", reports.map { it.modelId }.containsAll(models.filter { it.parameterCount in setOf("0.5B", "360M") }.map { it.id }))
        if (requested == null) assertEquals(2, reports.size)
        File(context.filesDir, "automatic-benchmark-completion.json").writeText(JSONObject().put("requested", models.size).put("completed", reports.size).put("incomplete", models.size - reports.size).toString())
        assertTrue(reports.all { it.checks.size == 10 && it.loadMs > 0 && it.tokensPerSecond > 0 && it.peakMb > 0 })
        assertNotNull(repo.automaticSetup.cached(models.first()))
        repo.engine.setProcessingMode(ProcessingMode.AUTO)
        assertNull("A CPU-only benchmark cannot masquerade as an AUTO configuration benchmark", repo.automaticSetup.cached(models.first()))
        repo.engine.setProcessingMode(ProcessingMode.CPU)
        assertEquals(manual, repo.preferencesRepository.primaryModelId.first())
        assertEquals(manual, repo.activeModel.first()?.id)
        assertTrue(repo.engine.isReady())
        val measured = AutomaticModelSelector.chooseMeasured(models, reports)
        measured.primary?.let { primary ->
            repo.activateMeasuredPair(primary, measured.secondary)
            assertEquals(primary.id, repo.preferencesRepository.primaryModelId.first())
            repo.switchActiveModel(models.first { it.id == manual })
        }
        val text = repo.engine.generateSync("What is 2 plus 2? Reply briefly.", GenerationParams(contextSize = 1024, maxTokens = 32, temperature = 0f, autoContinue = false))
        assertTrue(text, Regex("4|four", RegexOption.IGNORE_CASE).containsMatchIn(text))
        repo.unloadModel()
    }

    @Test fun stageResumableTransfer(): Unit = runBlocking {
        assumeTrue(args.getString("transferStage") == "true")
        repo.awaitModelInitialization()
        val model = repo.allModels.first().first { it.filename == "smollm2-135m-instruct-q4_k_m.gguf" }
        check(!model.isInstalled)
        DownloadQueueStore(context).setWifiOnly(true)
        repo.startModelDownload(model, this)
        val part = File(repo.downloadManager.modelsDirectory, "${model.filename}.part")
        withTimeout(90_000) { while (part.length() < 1_048_576) delay(100) }
        repo.cancelModelDownload(model.id, this)
        withTimeout(15_000) { while (repo.modelForDownload(model.id)?.downloadState !in setOf("PAUSED", "NOT_DOWNLOADED")) delay(100) }
        assertTrue(part.length() > 0)
        File(context.filesDir, "transfer-restart.json").writeText(JSONObject().put("id", model.id).put("partialBytes", part.length()).toString())
        // Durable user-approved queue, intentionally not scheduled until the next visible app start.
        DownloadQueueStore(context).enqueue(model.id)
    }

    @Test fun recordFixedQualityResponsesForCalibration(): Unit = runBlocking {
        assumeTrue(args.getString("qualityDiagnostics") == "true")
        repo.awaitModelInitialization(); repo.unloadModel()
        val models = repo.allModels.first().filter { it.isInstalled && it.parameterCount in setOf("0.5B", "360M") }
        val rows = JSONArray()
        repo.engine.setProcessingMode(ProcessingMode.CPU)
        try {
            for (model in models) {
                repo.engine.loadCustomGguf(File(checkNotNull(model.localFilePath))).getOrThrow()
                for (probe in ModelQualityBenchmark.probes) {
                    val text = withTimeout(45_000) { repo.engine.generateSync(probe.prompt, GenerationParams(contextSize = ModelLibrary.configuredContext(model, 1024),
                        maxTokens = 96, temperature = 0f, topK = 1, topP = 1f, history = emptyList(), autoContinue = false)) }
                    rows.put(JSONObject().put("model", model.name).put("probe", probe.id).put("syntheticFixedPrompt", true)
                        .put("response", text.take(1600)).put("criterionMatched", probe.accepts(text)))
                    File(context.filesDir, "quality-calibration.json").writeText(rows.toString(2))
                }
            }
        } finally { repo.engine.unloadActiveModel() }
        assertEquals(18, rows.length())
    }

    @Test fun deleteAnInterruptedRealDownloadFromTheUi(): Unit = runBlocking {
        assumeTrue(args.getString("deleteLivePartial") == "true")
        repo.awaitModelInitialization()
        val model = repo.allModels.first().single { it.filename == "qwen2.5-0.5b-instruct-q4_k_m.gguf" }
        check(!model.isInstalled) { "Run this only in the fresh emulator validation app" }
        DownloadQueueStore(context).setWifiOnly(true)
        repo.startModelDownload(model, this)
        val part = File(repo.downloadManager.modelsDirectory, "${model.filename}.part")
        withTimeout(90_000) { while (part.length() < 1_048_576) delay(100) }
        repo.cancelModelDownload(model.id, this)
        withTimeout(15_000) { while (repo.modelForDownload(model.id)?.downloadState != "PAUSED") delay(100) }
        val bytes = part.length(); assertTrue(bytes > 0)
        compose.runOnUiThread { vm.selectTab(AppTab.MODELS) }
        compose.onNodeWithTag("model_source_library").performClick()
        compose.onNodeWithTag("models_list").performScrollToNode(hasTestTag("delete_partial_${model.id}"))
        compose.onNodeWithTag("delete_partial_${model.id}").performClick()
        compose.onNodeWithTag("confirm_delete_partial").performClick()
        compose.waitUntil(15_000) { !part.exists() && vm.allModels.value.any { it.id == model.id && it.downloadState == "NOT_DOWNLOADED" } }
        assertFalse(model.id in DownloadQueueStore(context).ids())
        val completed = repo.allModels.first().single { it.filename == "smollm2-135m-instruct-q4_k_m.gguf" }
        assertTrue(completed.isInstalled); assertTrue(repo.verifyModelChecksum(completed).isMatch)
        File(context.filesDir, "real-partial-deletion.json").writeText(JSONObject().put("deletedPartialBytes", bytes).put("installedModelKept", true).toString())
    }

    @Test fun prepareIsolatedCompatibilityClient(): Unit = runBlocking {
        assumeTrue(args.getString("automaticClient") == "true")
        repo.awaitModelInitialization()
        val models = repo.allModels.first()
        val primary = models.single { it.isInstalled && it.filename == "qwen2.5-0.5b-instruct-q4_k_m.gguf" }
        val backup = models.single { it.isInstalled && it.filename == "SmolLM2-360M-Instruct-Q4_K_M.gguf" }
        repo.switchActiveModel(primary)
        repo.preferencesRepository.saveSecondaryModelId(backup.id)
        repo.updatePolicy(ClientAppPolicyEntity("com.example.betterclient.rustvalidation", "Isolated compatibility client",
            isWhitelisted = true, allowReadMemory = false, allowWriteMemory = false, rateLimitPerMin = 60))
        repo.unloadModel() // The separate client's first request must prepare the shared model.
    }

    @Test fun actualImportedEncoderRanksPassagesAndReleasesResources(): Unit = runBlocking {
        assumeTrue(args.getString("localEncoder") == "true")
        repo.awaitModelInitialization(); repo.unloadModel()
        val source = File(context.filesDir, "embedding-test.gguf")
        check(source.isFile)
        val ranker = LocalEmbeddingRanker(context)
        try {
            ranker.import(android.net.Uri.fromFile(source))
            assertTrue(ranker.available())
            val question = "Which gas do plants absorb during photosynthesis?"
            val sources = listOf(
                com.example.grounding.EvidenceSource(1, "Plants", "https://fixture.example/plants", "Plants absorb carbon dioxide during photosynthesis to grow.", 1),
                com.example.grounding.EvidenceSource(2, "Football", "https://fixture.example/football", "A football game has two teams and a referee.", 1))
            val scores = ranker.scores(question, sources)
            File(context.filesDir, "local-embedding-device.json").writeText(JSONObject(scores).put("syntheticPublicPassages", true).toString())
            assertEquals(2, scores.size)
            assertTrue(scores.toString(), scores.getValue(sources[0].url) > scores.getValue(sources[1].url))
            assertFalse(File("/proc/self/maps").useLines { rows -> rows.any { it.contains("/passage-encoder.gguf") } })
        } finally { ranker.remove() }
        assertFalse(ranker.available())
    }

    @Test fun resumedTransferAfterProcessRecreation(): Unit = runBlocking {
        assumeTrue(args.getString("transferResume") == "true")
        repo.awaitModelInitialization()
        val record = JSONObject(File(context.filesDir, "transfer-restart.json").readText())
        assertTrue(record.getLong("partialBytes") > 0)
        ModelDownloadQueue.ensureScheduled(context)
        withTimeout(240_000) { while (repo.modelForDownload(record.getLong("id"))?.isInstalled != true) delay(300) }
        val model = checkNotNull(repo.modelForDownload(record.getLong("id")))
        assertTrue(repo.verifyModelChecksum(model).isMatch)
        assertFalse(File(repo.downloadManager.modelsDirectory, "${model.filename}.part").exists())
        withTimeout(10_000) { while (model.id in DownloadQueueStore(context).ids()) delay(100) }
        assertFalse(model.id in DownloadQueueStore(context).ids())
        File(context.filesDir, "transfer-complete.json").writeText(JSONObject().put("checksumVerified", true).put("bytes", File(checkNotNull(model.localFilePath)).length()).toString())
    }
}
