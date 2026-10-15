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
        val status = LocalAiAccess(context, repository).status()
        assertEquals("MODEL_REQUIRED", status.getString("status"))
        assertFalse(status.getBoolean("model_loaded"))
        assertEquals("", status.getString("active_model"))
    }
}
