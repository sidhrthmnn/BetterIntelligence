package com.example

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.engine.*
import com.example.service.DownloadQueueStore
import com.example.grounding.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticSetupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun models() = ModelLibrary.read(context).mapIndexed { index, model -> model.copy(id = index + 1L) }
    private fun profile(ram: Long = 4000, low: Boolean = false, battery: Int = 80, thermal: Boolean = false) = HardwareProfile("Test", "Test", "14", 6144, ram, 50,
        low, ModelMemoryPlanner.budget(6144, ram), 512, 4, "arm64-v8a", true, 20f, 64f,
        battery, 25f, false, if (thermal) "Severe" else "Normal", if (thermal) 4 else 0, thermal, 50, DeviceTier.BALANCED)
    @Test fun preliminaryPairReusesInstalledModelsWithoutInventedBenchmarks() {
        val installed = models().filter { it.parameterCount in setOf("0.5B", "360M") }.map { it.copy(isInstalled = true) }
        val pair = AutomaticModelSelector.recommend(installed + models().filter { it.fileSizeMb > 2000 }, profile())
        assertNotNull(pair.primary); assertTrue(pair.primary!!.isInstalled); assertEquals(0L, pair.downloadMb); assertFalse(pair.measured)
        assertEquals(.36, AutomaticModelSelector.billions(installed.first { it.parameterCount == "360M" }), .00001)
    }
    @Test fun unsafeDevicesAndUnsupportedFilesCannotStartAutomaticDownloads() {
        assertNull(AutomaticModelSelector.recommend(models(), profile(400, low = true)).primary)
        val raw = models().first().copy(filename = "raw.tflite", parameterCount = "2B")
        assertTrue(AutomaticModelSelector.candidates(listOf(raw), profile()).isEmpty())
        assertTrue(AutomaticModelSelector.candidates(listOf(raw.copy(filename = "test.gguf", sha256Checksum = null)), profile()).isEmpty())
    }
    @Test fun benchmarkReadinessRespectsBatteryThermalAndMemory() {
        assertFalse(DeviceIntelligenceSnapshot(profile(battery = 5), AccelerationCapabilities(), "test").canBenchmark)
        assertFalse(DeviceIntelligenceSnapshot(profile(thermal = true), AccelerationCapabilities(), "test").canBenchmark)
        assertFalse(DeviceIntelligenceSnapshot(profile(low = true), AccelerationCapabilities(), "test").canBenchmark)
        assertTrue(DeviceIntelligenceSnapshot(profile(), AccelerationCapabilities(), "test").canBenchmark)
    }
    @Test fun queueSurvivesRecreationWithPauseRemovalAndRetryCounts() {
        context.getSharedPreferences("model_download_queue", Context.MODE_PRIVATE).edit().clear().commit()
        val first = DownloadQueueStore(context); first.enqueue(42); first.enqueue(43); first.enqueue(42)
        val restored = DownloadQueueStore(context); assertEquals(listOf(42L,43L), restored.ids()); assertTrue(restored.wifiOnly())
        restored.setWifiOnly(false); assertFalse(DownloadQueueStore(context).wifiOnly())
        assertEquals(1, restored.failure(42)); assertEquals(2, restored.failure(42)); assertEquals(3, restored.failure(42))
        restored.remove(42); assertEquals(listOf(43L), first.ids()); assertEquals(1, first.failure(42)); first.remove(42); first.remove(43)
    }
    @Test fun benchmarkChecksRejectWrongNumbersUnsupportedObservationsAndMangledJson() {
        assertFalse(ModelQualityBenchmark.probes.first { it.id == "reasoning" }.accepts("12"))
        assertTrue(ModelQualityBenchmark.probes.first { it.id == "reasoning" }.accepts("10"))
        assertFalse(ModelQualityBenchmark.probes.first { it.id == "json" }.accepts("ok: true"))
        assertFalse(ModelQualityBenchmark.probes.first { it.id == "unknown" }.accepts("Your kitchen is 24°C"))
        assertFalse(ModelQualityBenchmark.probes.first { it.id == "evidence" }.accepts("The museum opens at 09:00 [1]."))
        assertTrue(ModelQualityBenchmark.probes.first { it.id == "json" }.accepts("```json\n{\"ok\":true}\n```"))
        assertTrue(ModelQualityBenchmark.probes.first { it.id == "unknown" }.accepts("I don't have access to temperature readings."))
        assertFalse(ModelQualityBenchmark.probes.first { it.id == "unknown" }.accepts("I don't know, perhaps 24 degrees Celsius."))
        assertTrue(ModelQualityBenchmark.probes.first { it.id == "continuity" }.accepts("My pet is called Nori."))
        assertFalse(ModelQualityBenchmark.probes.first { it.id == "continuity" }.accepts("It might be Nori or Max."))
        assertTrue(ModelQualityBenchmark.stable("2", "2"))
        assertFalse(ModelQualityBenchmark.qualifies(90, mapOf("fact" to true, "reasoning" to false, "evidence" to true, "stability" to true)))
    }
    @Test fun failedCandidatesCannotWinMeasuredSelectionAndCacheUsesChecksum() {
        val list = models().filter { it.parameterCount in setOf("0.5B", "360M") }.take(2)
        fun report(id: Long, quality: Int, ok: Boolean, memory: Long) = ModelQualityReport(id, "key", quality, ok, 100, 50, 10f, 40f, memory, "CPU", "Normal", 1000, emptyMap())
        val pair = AutomaticModelSelector.chooseMeasured(list, listOf(report(list[0].id,100,false,400), report(list[1].id,80,true,800)))
        assertEquals(list[1].id, pair.primary?.id); assertTrue(pair.measured); assertNull(pair.secondary)
        assertNotEquals(ModelQualityBenchmark.key(list[0]), ModelQualityBenchmark.key(list[0].copy(sha256Checksum = "a".repeat(64))))
    }
    @Test fun plansCannotReplaceVersionsOrInventSearchSubjects() {
        val q = "Compare Android 16 and Android 15 memory requirements"
        assertNull(LocalRequestPlan.validate(q, "not json"))
        val wrong = """{"intent":"comparison","entities":["Android 14"],"required":["memory"],"queries":["Android 14 memory"]}"""
        assertTrue(LocalRequestPlan.validate(q,wrong)!!.queries.isEmpty())
        assertFalse(ExactIdentity.accepts("Jailer 3", "Jailer 1 received 3 stars"))
        assertTrue(ExactIdentity.accepts(q, "Android 16 documentation"))
        assertFalse(ExactIdentity.acceptsAll(q, "Android 16 documentation"))
        assertTrue(ExactIdentity.labels("Find 3 sources about the current prime minister").isEmpty())
        assertTrue(ExactIdentity.labels("What's happening in London on November 10?").isEmpty())
        assertTrue(ExactIdentity.accepts("Qwen3.5 2B", "Qwen3.5_2B specification"))
        assertFalse(ExactIdentity.accepts("Qwen3.5 2B", "Qwen3.5 4B specification"))
        assertFalse(ExactIdentity.accepts("Android 16", "Android 16.1 specification"))
        assertTrue(ExactIdentity.accepts("LFM3-1.7B", "LFM 3 1.7B documentation"))
        assertFalse(ExactIdentity.accepts("LFM3-1.7B", "LFM 2.5 1.7B documentation"))
    }
    @Test fun absentEncoderUsesLexicalRankingAndNeverOverridesIdentity() {
        assertEquals(-1000.0, HybridPassageRanking.score("Android 16", "Android 15 has sixteen examples", emptyList()), .01)
        assertFalse(LocalEmbeddingRanker(context).available())
        assertTrue(LocalEmbeddingRanker(context).scores("Android 16", emptyList()).isEmpty())
    }
}
