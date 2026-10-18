package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelLibraryTest {
    @Test fun fixedLiteRtExportsDoNotPromiseKvSavingsWhenOnlyTheRequestLimitShrinks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixed = ModelLibrary.inferenceModel(context, "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm")!!
        assertEquals(ModelLibrary.memoryEstimate(fixed, 4096), ModelLibrary.memoryEstimate(fixed, 512))
        assertEquals(ModelLibrary.memoryEstimate(fixed, 4096), ModelLibrary.memoryEstimate(fixed.copy(filename = "renamed.litertlm"), 1024))
        val small = ModelLibrary.inferenceModel(context, "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm")!!
        assertEquals(ModelLibrary.memoryEstimate(small, 1280), ModelLibrary.memoryEstimate(small, 2048))
        val dynamic = ModelLibrary.read(context).single { it.filename == "gemma-4-E2B-it.litertlm" }
        assertTrue(ModelLibrary.memoryEstimate(dynamic, 512).totalMb < ModelLibrary.memoryEstimate(dynamic, 2048).totalMb)
        val different = dynamic.copy(sha256Checksum = "different-content")
        assertTrue(ModelLibrary.memoryEstimate(different).weightsMb >= different.fileSizeMb)
    }
    @Test fun knownLiteRtImportsUseTheirChecksumAndUnsafeExportKeepsCpuAvailable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val e2 = ModelLibrary.read(context).single { it.filename == "gemma-4-E2B-it.litertlm" }
        assertEquals(e2.sha256Checksum, ModelLibrary.inferenceModel(context, "renamed.litertlm", e2.sha256Checksum!!)!!.sha256Checksum)
        assertEquals(ModelLibrary.memoryEstimate(e2), ModelLibrary.memoryEstimate(e2.copy(filename = "renamed.litertlm", isCustom = true)))
        assertNull(ModelLibrary.inferenceModel(context, e2.filename, "different-content"))
        assertTrue(ModelLibrary.liteRtGpuRestriction("7243c33c9c6192150ab6da7ac13a9efdc24ff1a653eabc93b2757de4b44b012a").contains("CPU inference remains available"))
        assertEquals("", ModelLibrary.liteRtGpuRestriction(e2.sha256Checksum!!))
        val gguf = ModelLibrary.read(context).single { it.parameterCount == "4B" }
        assertEquals("", ModelLibrary.liteRtGpuRestriction(gguf.sha256Checksum!!))
    }
    private fun profile(ram: Long = 2048, storage: Float = 10f, abi: String = "arm64-v8a") = HardwareProfile(
        "Test", "Test", "14", 4096, ram, 50, false, (ram - 512).coerceAtLeast(0), 2048,
        4, abi, abi == "arm64-v8a", storage, 64f, 80, 25f, false, "Normal", 0, false, 50, DeviceTier.ULTRA_LIGHT)

    @Test fun recommendationUsesAvailableMemoryAndStorage() {
        val models = ModelLibrary.read(ApplicationProvider.getApplicationContext())
        assertEquals(14, models.size)
        assertTrue(models.all { !it.isInstalled && !it.isActive && !it.isChecksumVerified })
        assertEquals("Qwen 3 0.6B Instruct · LiteRT", ModelLibrary.recommend(models, profile())!!.recommendedModelName)
        assertEquals("SmolLM2 135M Instruct", ModelLibrary.recommend(models, profile(storage = 0.3f))!!.recommendedModelName)
        assertNull(ModelLibrary.recommend(models, profile(ram = 512)))
        assertNull(ModelLibrary.recommend(models, profile(abi = "armeabi-v7a")))
        assertEquals("Gemma 4 E2B Instruct · LiteRT", ModelLibrary.recommend(models, profile(ram = 4096))!!.recommendedModelName)
    }

    @Test fun downloadDoesNotRequireEnoughRamToRunTheModel() {
        val model = ModelLibrary.read(ApplicationProvider.getApplicationContext()).last()
        assertTrue(ModelLibrary.canDownload(model, profile(ram = 512)))
        assertFalse(ModelLibrary.fits(model, profile(ram = 512)))
        assertFalse(ModelLibrary.canDownload(model, profile(storage = 0.1f)))
        assertNull(ModelLibrary.recommend(listOf(model.copy(isCustom = true)), profile(ram = 4096)))
    }

    @Test fun higherCapacityModelsRequireEnoughAvailableMemoryAndStorage() {
        val models = ModelLibrary.read(ApplicationProvider.getApplicationContext())
        val fourB = models.single { it.parameterCount == "4B" }
        val sevenB = models.single { it.parameterCount == "7B" }
        assertTrue(ModelLibrary.fits(fourB, profile(ram = 4096)))
        assertTrue(ModelLibrary.fits(fourB, profile(ram = 6144)))
        assertTrue(ModelLibrary.fits(sevenB, profile(ram = 6144)))
        assertTrue(ModelLibrary.fits(sevenB, profile(ram = 8192)))
        assertFalse(ModelLibrary.canDownload(sevenB, profile(ram = 8192, storage = 4f)))
        assertEquals("Gemma 4 E2B Instruct · LiteRT", ModelLibrary.recommend(models, profile(ram = 6144))!!.recommendedModelName)
        assertEquals("Gemma 4 E2B Instruct · LiteRT", ModelLibrary.recommend(models, profile(ram = 8192))!!.recommendedModelName)
        assertEquals(setOf("FAST", "BALANCED", "ADVANCED"), models.mapNotNull(ModelLibrary::category).toSet())
    }

    @Test fun qwen4BOn12GbPhoneUsesCurrentHeadroomContextAndQuantizedFileSize() {
        val model = ModelLibrary.read(ApplicationProvider.getApplicationContext()).single { it.parameterCount == "4B" }
        val phone = profile(ram = 3800).copy(totalRamMb = 12 * 1024, safeModelRamLimitMb = ModelMemoryPlanner.budget(12 * 1024, 3800))
        assertTrue(ModelLibrary.fits(model, phone))
        assertTrue(ModelLibrary.memoryEstimate(model, 4096).totalMb > ModelLibrary.memoryEstimate(model, 512).totalMb)
        assertNotNull(ModelLibrary.incompatibilityReason(model, phone, 4096))
        val cpu = ModelLibrary.memoryEstimate(model, 2048)
        val gpu = ModelLibrary.memoryEstimate(model, 2048, 16)
        assertTrue(gpu.totalMb > cpu.totalMb) // GPU does not provide additional RAM.
        assertTrue(ModelLibrary.incompatibilityReason(model, phone.copy(isLowMemory = true))!!.contains("low memory"))
    }

    @Test fun catalogHasMatchingPinnedDownloadsAndHonestEstimates() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val asset = context.assets.open("model-library.json").bufferedReader().use { it.readText() }
        assertEquals(java.io.File("../models/library.json").readText().trim(), asset.trim())
        val models = ModelLibrary.read(context)
        assertEquals(14, models.map { it.filename }.distinct().size)
        assertEquals(setOf("LIGHTWEIGHT", "BALANCED", "QUALITY"), models.map { it.recommendedTier }.toSet())
        for (model in models) {
            assertTrue(model.sha256Checksum!!.matches(Regex("[a-f0-9]{64}")))
            assertTrue(model.versionTag.matches(Regex("[a-f0-9]{40}")))
            assertTrue(model.downloadUrl!!.contains("/resolve/${model.versionTag}/"))
            assertEquals(if (model.architecture == "qwen3" && model.filename.endsWith(".litertlm")) "INT4 block32" else if (model.filename.endsWith(".litertlm")) "Mixed 2/4/8-bit" else "Q4_K_M", model.quantization)
            assertEquals(0f, model.speedScoreTokSec)
            assertTrue(model.description.contains("Memory is an estimate"))
        }
    }

    @Test fun availableLibraryFilesHaveRealGgufMetadata() {
        val models = ModelLibrary.read(ApplicationProvider.getApplicationContext())
        for ((index, model) in models.withIndex()) {
            val file = java.io.File("../models", model.filename)
            // Four legacy weights are in Git LFS. New catalog weights are optional local downloads.
            if (index < 4) assertTrue("Missing ${model.filename}; run git lfs pull", file.isFile)
            if (!file.isFile) continue
            if (model.filename.endsWith(".litertlm")) continue // These packages need the real LiteRT runtime, not a GGUF parser.
            val metadata = GgufParser().parseFromFile(file).getOrThrow()
            assertEquals(model.architecture, metadata.architecture)
            assertTrue(metadata.tensorCount > 0)
            assertTrue(metadata.vocabSize > 1000)
            assertEquals(ModelMemoryPlanner.estimate(metadata, 2048).totalMb, model.ramRequiredMb.toLong())
        }
    }

    @Test fun noDefaultModelIsReportedReady() {
        assertFalse(InferenceEngine().isReady())
    }

    @Test fun gemma4HasBothVerifiedFormatsAndDoesNotCountUnloadedAudioVisionWeights() {
        val models = ModelLibrary.read(ApplicationProvider.getApplicationContext()).filter { it.architecture == "gemma4" }
        assertEquals(setOf("E2B", "E4B"), models.map { it.parameterCount }.toSet())
        for (size in listOf("E2B", "E4B")) {
            val pair = models.filter { it.parameterCount == size }
            assertEquals(2, pair.size)
            val lite = pair.single { it.filename.endsWith(".litertlm") }
            assertTrue(ModelLibrary.memoryEstimate(lite).weightsMb < lite.fileSizeMb)
            assertTrue(ModelLibrary.memoryEstimate(lite, 4096).totalMb > ModelLibrary.memoryEstimate(lite, 512).totalMb)
            assertNull(ModelLibrary.incompatibilityReason(lite, profile(ram = 8192).copy(totalRamMb = 12 * 1024)))
            assertTrue(lite.description.contains("apache-2.0"))
        }
    }
}
