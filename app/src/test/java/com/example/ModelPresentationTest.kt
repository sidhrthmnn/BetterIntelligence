package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import com.example.ui.screens.ModelPresentation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelPresentationTest {
    private fun model() = ModelLibrary.read(ApplicationProvider.getApplicationContext()).first().copy(id = 1)
    private fun profile(ram: Long, total: Long = 6144) = HardwareProfile("Test", "Test", "14", total, ram, 50,
        false, ModelMemoryPlanner.budget(total, ram), 512, 4, "arm64-v8a", true, 20f, 64f,
        80, 25f, false, "Normal", 0, false, 50, DeviceTier.ULTRA_LIGHT)

    @Test fun progressNeverReordersCardsAndCompletionStaysInPlace() {
        val base = model()
        val downloading = base.copy(id = 3, downloadState = "DOWNLOADING", downloadProgress = .1f)
        val installed = base.copy(id = 2, isInstalled = true)
        val initial = ModelPresentation.ordered(listOf(base, installed, downloading), listOf(3), 2, 1).map { it.id }
        assertEquals(listOf(3L, 2L, 1L), initial)
        assertEquals(initial, ModelPresentation.ordered(listOf(base, installed, downloading.copy(downloadProgress = .9f)), listOf(3), 2, 1).map { it.id })
        assertEquals(initial, ModelPresentation.ordered(listOf(base, installed, downloading.copy(isInstalled = true, downloadState = "DOWNLOADED")), listOf(3), 2, 1).map { it.id })
    }

    @Test fun myModelsIncludesIncompleteDownloadsAndClearCompletion() {
        val base = model()
        for (state in listOf("DOWNLOADING", "PAUSED", "ERROR")) assertTrue(ModelPresentation.hasDownload(base.copy(downloadState = state)))
        assertFalse(ModelPresentation.hasDownload(base))
        assertEquals("Downloaded · Ready to use", ModelPresentation.status(base.copy(isInstalled = true)))
        assertEquals("Checking file before installing…", ModelPresentation.status(base.copy(downloadState = "DOWNLOADING", checksumVerificationStatus = "VERIFYING")))
        assertEquals("Paused · 65% saved", ModelPresentation.status(base.copy(downloadState = "PAUSED", downloadProgress = .65f)))
    }

    @Test fun memoryDistinguishesPressureFromInsufficientDeviceCapacity() {
        val base = model()
        assertEquals("Checking available RAM…", ModelPresentation.memoryLabel(base, null, false))
        assertEquals("Enough RAM to use this model", ModelPresentation.memoryLabel(base, profile(4000), false))
        assertEquals("Free some RAM to use this model", ModelPresentation.memoryLabel(base, profile(550), false))
        assertEquals("Needs more RAM than this phone has", ModelPresentation.memoryLabel(base.copy(fileSizeMb = 12000), profile(4000), false))
        assertEquals("Running on this phone", ModelPresentation.memoryLabel(base, profile(550), true))
    }

    @Test fun liteRtMemoryUsesTheContextActuallyRequestedOnLoad() {
        val base = model()
        assertEquals(512, ModelPresentation.loadContext(base, 2048))
        assertEquals(2048, ModelPresentation.loadContext(base.copy(filename = "model.litertlm"), 2048))
        assertEquals(1024, ModelPresentation.loadContext(base.copy(filename = "model.litertlm", contextLength = 1024), 2048))
    }
}
