package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.engine.EngineManager
import com.example.ipc.LocalAiAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClientAccessTest {
    @Test fun rejectedRamAdmissionNeverAttemptsToLoadTheInstalledPrimary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.robolectric.shadows.ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a"))
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        org.robolectric.Shadows.shadowOf(activityManager).setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply {
            totalMem = 8L * 1024 * 1024 * 1024
            availMem = 3L * 1024 * 1024 * 1024
        })
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = AiCoreRepository(context, db, EngineManager(context), SettingsPreferencesRepository(context))
        kotlinx.coroutines.withTimeout(10_000) { while (repository.isModelLoading) kotlinx.coroutines.delay(10) }
        val primary = repository.allModels.first().single { it.filename == "gemma-4-E4B-it.litertlm" }
        // This catalog row is installed, but its file is deliberately absent:
        // rejected admission must return LOW_MEMORY before touching the loader.
        db.modelDao().updateModel(primary.copy(isInstalled = true))
        repository.preferencesRepository.savePrimaryModelId(primary.id)
        repository.preferencesRepository.saveAutoRamFallback(false)
        val hardware = com.example.engine.HardwareAnalyzer.analyze(context)
        assertTrue(com.example.engine.ModelLibrary.memoryEstimate(primary, 512).totalMb > hardware.safeModelRamLimitMb)
        repository.clientPreparation.request()
        kotlinx.coroutines.withTimeout(10_000) { while (repository.clientPreparation.isPreparing) kotlinx.coroutines.delay(10) }
        assertEquals("LOW_MEMORY", repository.clientPreparation.state.value.phase)
        assertFalse(repository.engine.isReady())
        assertNull(db.modelDao().getActiveModel().first())
    }

    @Test fun pendingApprovalScopesRateLimitsAndPrivateLogs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = AiCoreRepository(context, db, EngineManager(context), SettingsPreferencesRepository(context))
        val pkg = "example.keyboard"
        assertEquals("APPROVAL_REQUIRED", repository.clientAccessStatus(pkg))
        assertFalse(repository.checkSecurityAndRateLimit(pkg).first)
        val pending = db.clientPolicyDao().getPolicyForPackage(pkg)!!
        assertFalse(pending.isWhitelisted)
        assertFalse(pending.allowReadMemory)
        assertFalse(pending.allowWriteMemory)
        repository.updatePolicy(pending.copy(isWhitelisted = true, rateLimitPerMin = 1))
        assertFalse(repository.checkSecurityAndRateLimit(pkg, "READ_MEMORY").first)
        assertTrue(repository.checkSecurityAndRateLimit(pkg).first)
        assertFalse(repository.checkSecurityAndRateLimit(pkg).first)
        repository.logIpcCall(IpcLogEntity(callingPackage = pkg, callingUid = 1234, requestType = "POLISH",
            promptPreview = "private input", responsePreview = "private output", latencyMs = 1,
            tokensPerSecond = 0f, totalTokens = 0, status = "SUCCESS"))
        val log = repository.recentIpcLogs.first().first()
        assertEquals("", log.promptPreview)
        assertEquals("", log.responsePreview)
        repository.updatePolicy(pending.copy(isWhitelisted = true, isBlocked = true))
        assertEquals("DENIED", repository.clientAccessStatus(pkg))
        kotlinx.coroutines.withTimeout(10_000) { while (repository.isModelLoading) kotlinx.coroutines.delay(10) }
        val status = LocalAiAccess(context, repository).status()
        assertEquals("MODEL_REQUIRED", status.getString("status"))
        assertFalse(status.getBoolean("model_loaded"))
        assertEquals("", status.getString("active_model"))
    }

    @Test fun startupLoadingUsesCompatibleBusyStatusRatherThanAskingForAnotherDownload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).setQueryExecutor { query ->
            executor.execute {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                query.run()
            }
        }.build()
        val repository = AiCoreRepository(context, db, EngineManager(context), SettingsPreferencesRepository(context))
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val loading = LocalAiAccess(context, repository).status()
            assertEquals(2, loading.getInt("api_version"))
            assertEquals("BUSY", loading.getString("status"))
            assertTrue(loading.getBoolean("model_loading"))
            assertEquals("LOADING_MODEL", loading.getString("activity"))
            assertTrue(loading.getBoolean("supports_model_preparation"))
            assertFalse(loading.getBoolean("model_loaded"))
        } finally { release.countDown() }
        kotlinx.coroutines.withTimeout(10_000) { while (repository.isModelLoading) kotlinx.coroutines.delay(10) }
        val settled = LocalAiAccess(context, repository).status()
        assertEquals("MODEL_REQUIRED", settled.getString("status"))
        assertFalse(settled.getBoolean("model_loading"))
        executor.shutdown()
    }
}
