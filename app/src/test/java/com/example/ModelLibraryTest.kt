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
        assertEquals(4, models.size)
        assertTrue(models.all { !it.isInstalled && !it.isActive && !it.isChecksumVerified })
        assertEquals("Qwen 2.5 0.5B Instruct", ModelLibrary.recommend(models, profile())!!.recommendedModelName)
        assertEquals("SmolLM2 135M Instruct", ModelLibrary.recommend(models, profile(storage = 0.3f))!!.recommendedModelName)
        assertNull(ModelLibrary.recommend(models, profile(ram = 512)))
        assertNull(ModelLibrary.recommend(models, profile(abi = "armeabi-v7a")))
    }

    @Test fun everyLibraryFileHasRealGgufMetadata() {
        val models = ModelLibrary.read(ApplicationProvider.getApplicationContext())
        for (model in models) {
            val file = java.io.File("../models", model.filename)
            assertTrue("Missing ${model.filename}; run scripts/download_models.py", file.isFile)
            val metadata = GgufParser().parseFromFile(file).getOrThrow()
            assertEquals(model.architecture, metadata.architecture)
            assertTrue(metadata.tensorCount > 0)
            assertTrue(metadata.vocabSize > 1000)
        }
    }

    @Test fun noDefaultModelIsReportedReady() {
        assertFalse(InferenceEngine().isReady())
    }
}
