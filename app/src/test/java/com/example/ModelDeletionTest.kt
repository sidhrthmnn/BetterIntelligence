package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.engine.EngineManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDeletionTest {
    @Test fun deletionRemovesOnlyPrivateWeightsAndResetsDownloadAndIntegrityState() = runBlocking {
        withRepository { repository, dao ->
            val model = dao.getAllModels().first().first()
            val file = File(repository.downloadManager.modelsDirectory, model.filename).apply { writeText("test weights") }
            val partial = File(file.parentFile, "${file.name}.part").apply { writeText("partial") }
            val installed = model.copy(isInstalled = true, localFilePath = file.path, downloadState = "DOWNLOADED",
                isChecksumVerified = true, checksumVerificationStatus = "VERIFIED")
            dao.updateModel(installed)
            repository.uninstallModel(model) // Stale UI data must not drive the database update.
            val deleted = dao.getModelById(model.id)!!
            assertFalse(file.exists())
            assertFalse(partial.exists())
            assertFalse(deleted.isInstalled)
            assertFalse(deleted.isChecksumVerified)
            assertNull(deleted.localFilePath)
            assertEquals("NOT_DOWNLOADED", deleted.downloadState)
            assertEquals("NOT_VERIFIED", deleted.checksumVerificationStatus)
            assertEquals(model.downloadUrl, deleted.downloadUrl)
        }
    }

    @Test fun deletingAnImportRemovesItsEntryButPreservesOriginalFile() = runBlocking {
        withRepository { repository, dao ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val original = File(context.cacheDir, "original.gguf").apply { writeText("source") }
            val copy = File(repository.downloadManager.modelsDirectory, "import-test.gguf").apply { writeText("private copy") }
            val model = dao.getAllModels().first().first().copy(id = 0, filename = copy.name, isCustom = true,
                isInstalled = true, localFilePath = copy.path, downloadUrl = null)
            val id = dao.insertModel(model)
            repository.uninstallModel(model.copy(id = id))
            assertFalse(copy.exists())
            assertNull(dao.getModelById(id))
            assertEquals("source", original.readText())
            original.delete()
        }
    }

    @Test fun failedDeletionAndExternalPathsDoNotReportModelAsDeleted() = runBlocking {
        withRepository { repository, dao ->
            val model = dao.getAllModels().first().first()
            val directory = File(repository.downloadManager.modelsDirectory, "not-a-weight.gguf").apply { mkdirs() }
            val installed = model.copy(isInstalled = true, localFilePath = directory.path)
            dao.updateModel(installed)
            assertTrue(runCatching { repository.uninstallModel(installed) }.isFailure)
            assertTrue(dao.getModelById(model.id)!!.isInstalled)
            directory.delete()
            val external = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "external.gguf").apply { writeText("source") }
            dao.updateModel(installed.copy(localFilePath = external.path))
            assertTrue(runCatching { repository.uninstallModel(installed) }.isFailure)
            assertTrue(external.exists())
            assertTrue(dao.getModelById(model.id)!!.isInstalled)
            external.delete()
        }
    }

    private suspend fun withRepository(block: suspend (AiCoreRepository, ModelDao) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = AiCoreRepository(context, database, EngineManager(context), SettingsPreferencesRepository(context))
        withTimeout(15_000) { repository.allModels.first { it.size == com.example.engine.ModelLibrary.read(context).size } }
        try { block(repository, database.modelDao()) } finally { database.close() }
    }
}
