package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.*
import com.example.engine.GenerationParams
import com.example.engine.ModelLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ActiveModelDeletionTest {
    @Test fun busyModelIsKeptThenIdleModelCanBeDeletedWithoutLosingChats() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AiCoreRepository.getInstance(context)
        val models = withTimeout(60_000) {
            repository.allModels.first { it.size == ModelLibrary.read(context).size }
        }
        val model = models.first { it.isBaseModel }
        val file = File(context.filesDir, "models/${model.filename}")
        assertTrue("Stage the base GGUF before this test", model.isInstalled && file.isFile)
        repository.switchActiveModel(model)
        val database = AppDatabase.getDatabase(context)
        database.chatDao().insertMessage(ChatMessageEntity(role = "user", content = "Keep this saved chat"))
        val generation = async(Dispatchers.Default) {
            repository.engine.generateSync("Write a long story about a journey.", GenerationParams(maxTokens = 512))
        }
        withTimeout(15_000) { repository.engine.telemetry.first { it.isRunning } }
        assertTrue(runCatching { repository.uninstallModel(model) }.isFailure)
        assertTrue(file.isFile)
        repository.engine.cancelGeneration()
        generation.cancelAndJoin()
        assertTrue(repository.engine.isReady())
        // The stale argument still has isActive=false; deletion must re-read current state.
        repository.uninstallModel(model)
        assertFalse(file.exists())
        assertFalse(repository.engine.isReady())
        assertNull(repository.activeModel.first())
        assertFalse(database.modelDao().getModelById(model.id)!!.isInstalled)
        assertTrue(repository.chatMessages.first().any { it.content == "Keep this saved chat" })
    }
}
