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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * High-level Android client SDK for connecting to the on-device Local AI Core service.
 * External Android applications can embed this single file into their codebase to access
 * on-device GGUF LLM generation, vector embeddings, summarization, and zero-shot classification.
 */
class LocalAiCoreClient(private val context: Context) {

    private var aiCoreService: ILocalAiCoreService? = null
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
    }

    /**
     * Connects to Local AI Core on the device via Android IPC Binder.
     */
    fun bind(): Boolean {
        val intent = Intent(LocalAiCoreService.ACTION_BIND).apply {
            setPackage("com.aistudio.localaicore.engine")
        }
        return context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Disconnects from Local AI Core.
     */
    fun unbind() {
        if (_isConnected.value) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore if already unlinked
            }
            aiCoreService = null
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

        val callback = object : ILocalAiStreamCallback.Stub() {
            override fun onToken(token: String?, tokenIndex: Int) {
                if (token != null) {
                    trySend(token)
                }
            }

            override fun onComplete(
                fullText: String?,
                latencyMs: Long,
                tokensPerSecond: Float,
                promptTokens: Int,
                completionTokens: Int
            ) {
                close()
            }

            override fun onError(errorCode: Int, errorMessage: String?) {
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
                service.cancelActiveGeneration()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Generates text synchronously.
     */
    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
        temperature: Float = 0.7f,
        maxTokens: Int = 256
    ): String = suspendCancellableCoroutine { continuation ->
        val service = aiCoreService
        if (service == null) {
            continuation.resume("Error: Service not connected")
            return@suspendCancellableCoroutine
        }
        try {
            val response = service.generateTextSync(prompt, systemPrompt, temperature, maxTokens)
            continuation.resume(response)
        } catch (e: RemoteException) {
            continuation.resume("IPC Error: ${e.message}")
        }
    }

    /**
     * Computes on-device dense vector embeddings for RAG or semantic search.
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
}
