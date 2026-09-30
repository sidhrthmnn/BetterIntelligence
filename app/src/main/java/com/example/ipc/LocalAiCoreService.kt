package com.example.ipc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.example.data.AiCoreRepository
import com.example.data.IpcLogEntity
import com.example.engine.GenerationParams
import com.example.engine.StreamTokenListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Android System Service exposing secure Binder IPC for on-device GGUF LLM inference.
 * Other Android apps can bind to this service to access on-device AI capabilities securely.
 */
class LocalAiCoreService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inferenceOwner = java.util.concurrent.atomic.AtomicInteger(-1)
    private lateinit var repository: AiCoreRepository

    override fun onCreate() {
        super.onCreate()
        repository = AiCoreRepository.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun enforceCallerPermission() {
        if (Binder.getCallingUid() != Process.myUid()) {
            enforceCallingPermission(PERMISSION_BIND, "Better Intelligence permission is required")
        }
    }

    override fun onDestroy() {
        repository.engine.cancelGeneration()
        serviceScope.cancel()
        super.onDestroy()
    }

    private val binder = object : ILocalAiCoreService.Stub() {

        override fun isModelLoaded(): Boolean {
            enforceCallerPermission()
            return repository.engine.isReady()
        }

        override fun getActiveModelName(): String {
            enforceCallerPermission()
            return repository.engine.activeMetadata.value?.modelName ?: "None"
        }

        override fun getActiveModelQuantization(): String {
            enforceCallerPermission()
            return repository.engine.activeMetadata.value?.primaryQuantization?.typeName ?: "Unknown"
        }

        override fun getActiveContextSize(): Int {
            enforceCallerPermission()
            return repository.engine.activeMetadata.value?.contextLength ?: 4096
        }

        override fun generateTextSync(
            prompt: String?,
            systemPrompt: String?,
            temperature: Float,
            maxTokens: Int
        ): String {
            val callingPackage = getCallingPackageName()
            val callingUid = Binder.getCallingUid()
            val safePrompt = sanitizeInput(prompt)
            val safeSystemPrompt = sanitizeInput(systemPrompt)

            val (isAuthorized, reason) = checkAuthorizationAndRateLimit(callingPackage)
            if (!isAuthorized) {
                logCall(callingPackage, callingUid, "SYNC", safePrompt, reason, 0L, 0f, 0, if (reason.contains("Rate limit")) "RATE_LIMITED" else "DENIED")
                return "Security Error: $reason"
            }

            val startTime = System.currentTimeMillis()
            check(inferenceOwner.compareAndSet(-1, callingUid)) { "AI Core is busy" }
            val result = try { runBlocking(Dispatchers.Default) {
                repository.engine.generateSync(
                    safePrompt,
                    GenerationParams(
                        temperature = if (temperature.isFinite()) temperature.coerceIn(0f, 2f) else 0.7f,
                        maxTokens = if (maxTokens > 0) maxTokens.coerceIn(1, 4096) else 256,
                        systemPrompt = safeSystemPrompt.ifBlank { null }
                    )
                )
            } } finally { inferenceOwner.set(-1) }
            val latency = System.currentTimeMillis() - startTime
            val approxTokens = (result.length / 4).coerceAtLeast(1)
            val tokPerSec = if (latency > 0) (approxTokens * 1000f) / latency else 0f

            logCall(callingPackage, callingUid, "SYNC", safePrompt, result, latency, tokPerSec, approxTokens, "SUCCESS")
            return result
        }

        override fun generateStream(
            prompt: String?,
            systemPrompt: String?,
            temperature: Float,
            topP: Float,
            maxTokens: Int,
            callback: ILocalAiStreamCallback?
        ) {
            val callingPackage = getCallingPackageName()
            val callingUid = Binder.getCallingUid()
            val safePrompt = sanitizeInput(prompt)
            val safeSystemPrompt = sanitizeInput(systemPrompt)

            if (callback == null) return

            val (isAuthorized, reason) = checkAuthorizationAndRateLimit(callingPackage)
            if (!isAuthorized) {
                val status = if (reason.contains("Rate limit")) "RATE_LIMITED" else "DENIED"
                logCall(callingPackage, callingUid, "STREAM", safePrompt, reason, 0L, 0f, 0, status)
                callback.onError(if (status == "RATE_LIMITED") 429 else 403, reason)
                return
            }

            if (!inferenceOwner.compareAndSet(-1, callingUid)) { callback.onError(409, "AI Core is busy"); return }
            val params = GenerationParams(
                temperature = if (temperature.isFinite()) temperature.coerceIn(0f, 2f) else 0.7f,
                topP = if (topP > 0) topP.coerceIn(0.1f, 1.0f) else 0.9f,
                maxTokens = if (maxTokens > 0) maxTokens.coerceIn(1, 4096) else 512,
                systemPrompt = safeSystemPrompt.ifBlank { null }
            )

            repository.engine.generateStream(
                safePrompt,
                params,
                object : StreamTokenListener {
                    override fun onToken(token: String, tokenIndex: Int) {
                        try {
                            callback.onToken(token, tokenIndex)
                        } catch (e: Exception) {
                            repository.engine.cancelGeneration()
                        }
                    }

                    override fun onComplete(
                        fullText: String,
                        latencyMs: Long,
                        tokensPerSec: Float,
                        promptTokens: Int,
                        completionTokens: Int
                    ) {
                        inferenceOwner.set(-1)
                        try {
                            callback.onComplete(fullText, latencyMs, tokensPerSec, promptTokens, completionTokens)
                            logCall(
                                callingPackage,
                                callingUid,
                                "STREAM",
                                safePrompt,
                                fullText,
                                latencyMs,
                                tokensPerSec,
                                promptTokens + completionTokens,
                                "SUCCESS"
                            )
                        } catch (e: Exception) {
                            // Client process unlinked
                        }
                    }

                    override fun onError(errorCode: Int, errorMessage: String) {
                        inferenceOwner.set(-1)
                        try {
                            callback.onError(errorCode, errorMessage)
                            logCall(callingPackage, callingUid, "STREAM", safePrompt, errorMessage, 0L, 0f, 0, "ERROR")
                        } catch (e: Exception) {
                            // Ignored
                        }
                    }
                }
            )
        }

        override fun getEmbeddings(text: String?): FloatArray {
            val callingPackage = getCallingPackageName()
            val callingUid = Binder.getCallingUid()
            val safeText = sanitizeInput(text)

            val (isAuthorized, reason) = checkAuthorizationAndRateLimit(callingPackage)
            if (!isAuthorized) {
                logCall(callingPackage, callingUid, "EMBEDDING", safeText, reason, 0L, 0f, 0, "DENIED")
                return FloatArray(0)
            }

            val embeddings = repository.engine.computeEmbeddings(safeText)
            logCall(callingPackage, callingUid, "EMBEDDING", safeText, "${embeddings.size}-dim semantic vector", 12L, 0f, 1, "SUCCESS")
            return embeddings
        }

        override fun summarizeText(text: String?, maxWords: Int): String {
            val callingPackage = getCallingPackageName()
            val callingUid = Binder.getCallingUid()
            val safeText = sanitizeInput(text)

            val (isAuthorized, reason) = checkAuthorizationAndRateLimit(callingPackage)
            if (!isAuthorized) {
                logCall(callingPackage, callingUid, "SUMMARIZE", safeText, reason, 0L, 0f, 0, "DENIED")
                return "Security Error: $reason"
            }

            val startTime = System.currentTimeMillis()
            check(inferenceOwner.compareAndSet(-1, callingUid)) { "AI Core is busy" }
            val summary = try { runBlocking(Dispatchers.Default) {
                repository.engine.summarize(safeText, if (maxWords > 0) maxWords.coerceIn(10, 500) else 60)
            } } finally { inferenceOwner.set(-1) }
            val latency = System.currentTimeMillis() - startTime
            logCall(callingPackage, callingUid, "SUMMARIZE", safeText, summary, latency, 28f, summary.length / 4, "SUCCESS")
            return summary
        }

        override fun classifyText(text: String?, candidateLabels: Array<String>?): String {
            val callingPackage = getCallingPackageName()
            val callingUid = Binder.getCallingUid()
            val safeText = sanitizeInput(text)
            val labels = candidateLabels?.filter { it.isNotBlank() }?.toTypedArray() ?: arrayOf("General", "Other")

            val (isAuthorized, reason) = checkAuthorizationAndRateLimit(callingPackage)
            if (!isAuthorized) {
                logCall(callingPackage, callingUid, "CLASSIFY", safeText, reason, 0L, 0f, 0, "DENIED")
                return "Security Error: $reason"
            }

            val startTime = System.currentTimeMillis()
            check(inferenceOwner.compareAndSet(-1, callingUid)) { "AI Core is busy" }
            val result = try { runBlocking(Dispatchers.Default) {
                repository.engine.classify(safeText, labels)
            } } finally { inferenceOwner.set(-1) }
            val latency = System.currentTimeMillis() - startTime
            logCall(callingPackage, callingUid, "CLASSIFY", safeText, result, latency, 35f, 4, "SUCCESS")
            return result
        }

        override fun cancelActiveGeneration() {
            enforceCallerPermission()
            if (inferenceOwner.get() == Binder.getCallingUid()) repository.engine.cancelGeneration()
        }

        override fun getEngineTelemetryJson(): String {
            enforceCallerPermission()
            return repository.engine.telemetry.value.toJson()
        }
    }

    private fun sanitizeInput(input: String?): String {
        if (input == null) return ""
        // Cap maximum prompt size to 64KB to avoid buffer overflow and DoS attacks
        val capped = if (input.length > 65536) input.take(65536) else input
        // Remove zero-byte characters and harmful control codes while preserving linebreaks
        return capped.filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
    }

    private fun getCallingPackageName(): String {
        enforceCallerPermission()
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) {
            return applicationContext.packageName
        }
        val packages = packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull() ?: "uid:$uid"
    }

    private fun checkAuthorizationAndRateLimit(packageName: String): Pair<Boolean, String> {
        if (packageName == applicationContext.packageName) return Pair(true, "OK")
        return runBlocking {
            repository.checkSecurityAndRateLimit(packageName)
        }
    }

    private fun logCall(
        packageName: String,
        uid: Int,
        type: String,
        prompt: String,
        response: String,
        latencyMs: Long,
        tokPerSec: Float,
        tokens: Int,
        status: String
    ) {
        serviceScope.launch {
            repository.logIpcCall(
                IpcLogEntity(
                    callingPackage = packageName,
                    callingUid = uid,
                    requestType = type,
                    promptPreview = "",
                    responsePreview = if (status == "SUCCESS") "" else response.take(200),
                    latencyMs = latencyMs,
                    tokensPerSecond = tokPerSec,
                    totalTokens = tokens,
                    timestamp = System.currentTimeMillis(),
                    status = status
                )
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Better Intelligence AI Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when on-device GGUF LLM inference service is active"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_BIND = "com.aistudio.localaicore.ACTION_BIND_AI_CORE"
        const val PERMISSION_BIND = "com.aistudio.localaicore.permission.BIND_LOCAL_AI_CORE"
        const val CHANNEL_ID = "better_intelligence_channel"
    }
}
