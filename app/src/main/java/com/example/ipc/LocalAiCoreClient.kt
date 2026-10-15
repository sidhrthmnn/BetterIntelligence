package com.example.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

/**
 * High-level Android client SDK for connecting to the on-device Local AI Core service.
 * External Android applications can embed this single file into their codebase to access
 * on-device GGUF text generation, summarization, and classification. Embeddings are unsupported.
 */
class LocalAiCoreClient(context: Context) {
    private val context = context.applicationContext
    private var isBound = false

    @Volatile private var aiCoreService: ILocalAiCoreService? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            aiCoreService = ILocalAiCoreService.Stub.asInterface(service)
            _isConnected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            aiCoreService = null
            _isConnected.value = false
        }

        override fun onNullBinding(name: ComponentName?) { unbind() }
        override fun onBindingDied(name: ComponentName?) { unbind() }
    }

    /**
     * Connects to Local AI Core on the device via Android IPC Binder.
     */
    fun bind(): Boolean {
        if (isBound) return true
        val intent = Intent("com.aistudio.betterintelligence.mobile.ACTION_BIND_AI_CORE").apply {
            setPackage("com.aistudio.betterintelligence.mobile")
        }
        isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        return isBound
    }

    suspend fun connect(timeoutMs: Long = 5000) {
        check(withContext(Dispatchers.Main.immediate) { bind() }) { "Install Better Intelligence first" }
        try { withTimeout(timeoutMs) { isConnected.first { it } } }
        catch (e: Exception) { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { unbind() }; throw e }
    }

    /**
     * Disconnects from Local AI Core.
     */
    fun unbind() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore if already unlinked
            }
            aiCoreService = null
            isBound = false
            _isConnected.value = false
        }
    }

    /**
     * Checks if a GGUF model is currently loaded in memory.
     */
    fun isModelLoaded(): Boolean {
        return try {
            aiCoreService?.isModelLoaded ?: false
        } catch (e: RemoteException) {
            false
        }
    }

    /**
     * Returns the name of the currently active GGUF model (e.g. "Qwen 2.5 0.5B Instruct").
     */
    fun getActiveModelName(): String {
        return try {
            aiCoreService?.activeModelName ?: "Not connected"
        } catch (e: RemoteException) {
            "Error: ${e.message}"
        }
    }

    /**
     * Streams tokens asynchronously as a Kotlin Coroutines [Flow].
     */
    fun streamGenerate(
        prompt: String,
        systemPrompt: String? = null,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 512
    ): Flow<String> = callbackFlow {
        val service = aiCoreService
        if (service == null) {
            close(IllegalStateException("LocalAiCoreService is not connected. Call bind() first."))
            return@callbackFlow
        }

        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = object : ILocalAiStreamCallback.Stub() {
            override fun onToken(token: String?, tokenIndex: Int) {
                if (token != null) {
                    if (trySend(token).isFailure) {
                        close(IllegalStateException("Consumer could not keep up with token stream"))
                    }
                }
            }

            override fun onComplete(
                fullText: String?,
                latencyMs: Long,
                tokensPerSecond: Float,
                promptTokens: Int,
                completionTokens: Int
            ) {
                finished.set(true)
                close()
            }

            override fun onError(errorCode: Int, errorMessage: String?) {
                finished.set(true)
                close(RuntimeException("AI Core Error [$errorCode]: $errorMessage"))
            }
        }

        try {
            service.generateStream(prompt, systemPrompt, temperature, topP, maxTokens, callback)
        } catch (e: RemoteException) {
            close(e)
        }

        awaitClose {
            try {
                if (!finished.get()) service.cancelActiveGeneration()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Generates text synchronously.
     */
    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
        temperature: Float = 0.7f,
        maxTokens: Int = 256
    ): String = withContext(Dispatchers.IO) {
        val service = checkNotNull(aiCoreService) { "Call connect() first" }
        service.generateTextSync(prompt, systemPrompt, temperature, maxTokens)
    }

    suspend fun polish(text: String): android.os.Bundle = withContext(Dispatchers.IO) {
        checkNotNull(aiCoreService) { "Call connect() first" }.polishText(text)
    }

    suspend fun serviceStatus(): android.os.Bundle = withContext(Dispatchers.IO) {
        checkNotNull(aiCoreService) { "Call connect() first" }.serviceStatus
    }

    /**
     * Unsupported for these text-generation models; the service throws UnsupportedOperationException.
     */
    fun getEmbeddings(text: String): FloatArray {
        return try {
            aiCoreService?.getEmbeddings(text) ?: FloatArray(0)
        } catch (e: RemoteException) {
            FloatArray(0)
        }
    }

    /**
     * Summarizes text on-device.
     */
    fun summarize(text: String, maxWords: Int = 60): String {
        return try {
            aiCoreService?.summarizeText(text, maxWords) ?: "Error: Service not connected"
        } catch (e: RemoteException) {
            "Error: ${e.message}"
        }
    }

    /**
     * Performs zero-shot text classification on-device.
     */
    fun classify(text: String, candidateLabels: Array<String>): String {
        return try {
            aiCoreService?.classifyText(text, candidateLabels) ?: "Unknown"
        } catch (e: RemoteException) {
            "Error: ${e.message}"
        }
    }

    /**
     * Extracts structured JSON from raw text matching the requested schema keys.
     */
    fun extractStructuredJson(text: String, schemaKeys: Array<String>): String {
        return try {
            aiCoreService?.extractStructuredJson(text, schemaKeys) ?: """{"error": "Service not connected"}"""
        } catch (e: RemoteException) {
            """{"error": "${e.message}"}"""
        }
    }

    /**
     * Routes a natural language utterance to the best candidate action/route.
     */
    fun routeIntent(utterance: String, candidateRoutes: Array<String>): String {
        return try {
            aiCoreService?.routeIntent(utterance, candidateRoutes) ?: """{"error": "Service not connected"}"""
        } catch (e: RemoteException) {
            """{"error": "${e.message}"}"""
        }
    }

    /**
     * Writes a memory fact into Better Intelligence's shared cross-app RAG store.
     */
    fun storeSharedMemory(title: String, content: String, category: String = "Cross-App"): Boolean {
        return try {
            aiCoreService?.storeSharedMemory(title, content, category) ?: false
        } catch (e: RemoteException) {
            false
        }
    }

    /**
     * Queries Better Intelligence's shared cross-app RAG memory store as JSON.
     */
    fun querySharedMemoriesJson(query: String = "", limit: Int = 10): String {
        return try {
            aiCoreService?.querySharedMemoriesJson(query, limit) ?: """{"memories": []}"""
        } catch (e: RemoteException) {
            """{"error": "${e.message}", "memories": []}"""
        }
    }
}
