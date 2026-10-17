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
    private fun profile(ram: Long = 2048, storage: Float = 10f, abi: String = "arm64-v8a") = HardwareProfile(
        "Test", "Test", "14", 4096, ram, 50, false, (ram - 512).coerceAtLeast(0), 2048,
        4, abi, abi == "arm64-v8a", storage, 64f, 80, 25f, false, "Normal", 0, false, 50, DeviceTier.ULTRA_LIGHT)

    @Test fun recommendationUsesAvailableMemoryAndStorage() {
        val models = ModelLibrary.read(ApplicationProvider.getApplicationContext())
        assertEquals(9, models.size)
        assertTrue(models.all { !it.isInstalled && !it.isActive && !it.isChecksumVerified })
        assertEquals("Qwen 2.5 1.5B Instruct", ModelLibrary.recommend(models, profile())!!.recommendedModelName)
        assertEquals("SmolLM2 135M Instruct", ModelLibrary.recommend(models, profile(storage = 0.3f))!!.recommendedModelName)
        assertNull(ModelLibrary.recommend(models, profile(ram = 512)))
        assertNull(ModelLibrary.recommend(models, profile(abi = "armeabi-v7a")))
        assertEquals("Qwen 3 4B Instruct 2507", ModelLibrary.recommend(models, profile(ram = 4096))!!.recommendedModelName)
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
        assertEquals(sevenB.name, ModelLibrary.recommend(models, profile(ram = 6144))!!.recommendedModelName)
        assertEquals(sevenB.name, ModelLibrary.recommend(models, profile(ram = 8192))!!.recommendedModelName)
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
        assertEquals(9, models.map { it.filename }.distinct().size)
        assertEquals(setOf("LIGHTWEIGHT", "BALANCED", "QUALITY"), models.map { it.recommendedTier }.toSet())
        for (model in models) {
            assertTrue(model.sha256Checksum!!.matches(Regex("[a-f0-9]{64}")))
            assertTrue(model.versionTag.matches(Regex("[a-f0-9]{40}")))
            assertTrue(model.downloadUrl!!.contains("/resolve/${model.versionTag}/"))
            assertEquals("Q4_K_M", model.quantization)
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
}
