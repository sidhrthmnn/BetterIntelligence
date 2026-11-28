package com.example

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.example.engine.HuggingFaceCatalog
import com.example.engine.ModelLibrary
import com.example.tasks.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskProtocolTest {
    @Test fun inputContractRejectsUnexpectedMediaAndSystemHistory() {
        val request = Bundle().apply {
            putInt("api_version", 3); putString("task_type", "TEXT_GENERATION"); putString("text", "Check a short question")
            putString("request_id", "request-123")
        }
        assertEquals(TaskType.TEXT_GENERATION, TaskRequest.parse(request, false).type)
        assertThrows(IllegalArgumentException::class.java) { TaskRequest.parse(request, true) }
        request.putStringArrayList("context_roles", arrayListOf("system"))
        request.putStringArrayList("context_text", arrayListOf("Not a client role"))
        assertThrows(IllegalArgumentException::class.java) { TaskRequest.parse(request, false) }
    }

    @Test fun audioNormalisationKeepsSamplesAndRejectsWrongFormat() {
        val raw = byteArrayOf(0, 2, 3, 4)
        val wav = TaskMedia.pcmWave(raw)
        assertArrayEquals(wav, TaskMedia.normaliseWave(wav))
        wav[22] = 2 // stereo
        assertThrows(IllegalArgumentException::class.java) { TaskMedia.normaliseWave(wav) }
    }

    @Test fun onlyPinnedPackageCanAdvertiseGemmaMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gemma = ModelLibrary.read(context).single { it.sha256Checksum == TaskModels.GEMMA_E2B }
        assertTrue(TaskType.IMAGE_UNDERSTANDING in TaskModels.capabilities(gemma))
        assertEquals(TaskModels.text, TaskModels.capabilities(gemma.copy(sha256Checksum = "0".repeat(64))))
        assertFalse(TaskType.IMAGE_GENERATION in TaskModels.capabilities(gemma))
    }

    @Test fun featuredMediaOffersTwoRamTiersAndDoesNotAdvertiseLfmAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val all = ModelLibrary.read(context)
        val featured = all.filter(ModelLibrary::visibleInLibrary)
        assertEquals(6, featured.size)
        assertTrue(featured.all { it.filename.endsWith(".litertlm") || it.sha256Checksum == TaskModels.BGE_SMALL })
        val large = featured.single { it.sha256Checksum == TaskModels.GEMMA_E4B }
        val small = featured.single { it.sha256Checksum == TaskModels.LFM_VISION }
        assertTrue(TaskType.AUDIO_TRANSCRIPTION in TaskModels.capabilities(large))
        assertTrue(TaskType.IMAGE_UNDERSTANDING in TaskModels.capabilities(small))
        assertFalse(TaskType.AUDIO_UNDERSTANDING in TaskModels.capabilities(small))
        assertTrue(TaskModels.estimateMb(small, TaskType.IMAGE_UNDERSTANDING) < TaskModels.estimateMb(large, TaskType.IMAGE_UNDERSTANDING))
        assertFalse(ModelLibrary.visibleInLibrary(all.first()))
    }

    @Test fun rejectsUnpinnedLiteRtAndFreeProjectors() {
        val metadata = JSONObject("""{"sha":"${"a".repeat(40)}","siblings":[
            {"rfilename":"model.litertlm","size":1048576,"lfs":{"sha256":"${"b".repeat(64)}"}},
            {"rfilename":"another.litertlm","size":1048576},
            {"rfilename":"model-mmproj.gguf","size":1024},
            {"rfilename":"model-00001-of-00003.gguf","size":1024}
        ]}""")
        val model = HuggingFaceCatalog.files("sample/model", metadata).single()
        assertTrue(model.downloadUrl.contains("a".repeat(40)))
        assertTrue(HuggingFaceCatalog.localFilename("sample/model", model).endsWith(".litertlm"))
    }

    @Test fun onlyPinnedBgeEnablesEmbeddingsWithoutEnteringTextSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bge = ModelLibrary.read(context).single { it.sha256Checksum == TaskModels.BGE_SMALL }
        assertEquals(setOf(TaskType.EMBEDDINGS), TaskModels.capabilities(bge))
        assertFalse(ModelLibrary.automaticUseAllowed(bge))
        assertTrue(TaskModels.capabilities(bge.copy(sha256Checksum = null)).isEmpty())
    }

    @Test fun approvalsAreBoundedAndSurviveRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = TaskSettings(context)
        assertTrue(settings.requestDownload(1, "com.example.test"))
        assertTrue(settings.requestDownload(1, "com.example.test"))
        assertTrue(settings.requestDownload(2, "com.example.test"))
        assertFalse(settings.requestDownload(3, "com.example.test"))
        val reopened = TaskSettings(context)
        assertEquals(2, reopened.downloadApprovals.value.size)
        reopened.clearDownloadsFrom("com.example.test")
        assertTrue(TaskSettings(context).downloadApprovals.value.isEmpty())
    }
}
