package com.example.ipc

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.example.data.AiCoreRepository
import com.example.data.IpcLogEntity
import com.example.engine.GenerationParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** The same identity, readiness and polish contract for Binder and ContentResolver clients. */
class LocalAiAccess(private val context: Context, private val repository: AiCoreRepository) {
    fun callerPackage(): String {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return context.packageName
        context.enforceCallingPermission(LocalAiCoreService.PERMISSION_BIND, "Local AI permission is required")
        // Never accept an identity from a Bundle, URI or prompt. Shared-UID apps share this grant.
        return context.packageManager.getPackagesForUid(uid)?.sorted()?.firstOrNull()
            ?: throw SecurityException("Cannot identify calling UID")
    }

    fun status(): Bundle {
        val caller = callerPackage()
        val access = runBlocking(Dispatchers.IO) { repository.clientAccessStatus(caller) }
        return Bundle().apply {
            putInt("api_version", 2)
            putBoolean("supports_polish", true)
            putString("polish_language", "en")
            putBoolean("model_loaded", repository.engine.isReady())
            putString("active_model", if (repository.engine.isReady()) repository.engine.activeModelName else "")
            putString("status", if (access != "READY") access else if (!repository.engine.isReady()) "MODEL_REQUIRED"
                else if (repository.engine.telemetry.value.isRunning) "BUSY" else "READY")
            putString("settings_package", context.packageName)
            putInt("max_polish_characters", PolishText.MAX_CHARACTERS)
        }
    }

    fun polish(text: String?): Bundle {
        val caller = callerPackage()
        val uid = Binder.getCallingUid()
        val start = SystemClock.elapsedRealtime()
        var result = status()
        if (result.getString("status") == "READY") {
            result = when {
                text.isNullOrBlank() || text.length > PolishText.MAX_CHARACTERS -> failure("INVALID_REQUEST", "Provide 1–${PolishText.MAX_CHARACTERS} characters")
                else -> runBlocking(Dispatchers.Default) {
                    val (allowed, reason) = repository.checkSecurityAndRateLimit(caller)
                    if (!allowed) failure(if (reason.startsWith("Rate limit")) "RATE_LIMITED" else "DENIED", reason)
                    else try {
                        val output = repository.engine.generateSync(PolishText.prompt(text), GenerationParams(
                            systemPrompt = PolishText.SYSTEM_PROMPT, temperature = 0f, maxTokens = 1024))
                        check(repository.engine.telemetry.value.completionTokens < 1024) { "Output limit reached; polish a shorter passage" }
                        val corrected = PolishText.validateOutput(text, output)
                        Bundle().apply { putString("status", "SUCCESS"); putString("text", corrected) }
                    } catch (e: Exception) {
                        failure(if (e.message?.contains("busy", true) == true) "BUSY" else "ERROR",
                            "The local model could not safely polish this text. Try a shorter passage or another model.")
                    }
                }
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        result.putLong("latency_ms", elapsed)
        runBlocking(Dispatchers.IO) {
            repository.logIpcCall(IpcLogEntity(callingPackage = caller, callingUid = uid,
                requestType = "POLISH", promptPreview = "", responsePreview = "", latencyMs = elapsed,
                tokensPerSecond = 0f, totalTokens = 0, status = result.getString("status") ?: "ERROR"))
        }
        return result
    }

    private fun failure(status: String, message: String) = Bundle().apply {
        putString("status", status)
        putString("error", message)
    }
}

object PolishText {
    const val MAX_CHARACTERS = 4000
    const val SYSTEM_PROMPT = "You are an English grammar teacher."

    fun prompt(text: String): String = "Correct the grammar in this sentence: $text Return only the corrected sentence."

    // This is a conservative guard, not a guarantee of model accuracy. Consumers must show a preview.
    fun validateOutput(original: String, output: String): String {
        val value = output.trim()
        check(value.isNotEmpty() && value.length <= original.length * 3 + 128) { "Invalid polish output" }
        check(!value.startsWith("```") && !value.startsWith("<think>")) { "Unexpected model formatting" }
        val protected = Regex("https?://[^\\s]+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|\\d+(?:[.,:/-]\\d+)*")
        fun literals(text: String) = protected.findAll(text).map { it.value.trimEnd('.', ',', '!', '?', ';', ':') }
            .groupingBy { it }.eachCount()
        check(literals(original) == literals(value)) { "Model changed a number, link or email address" }
        return value
    }
}
