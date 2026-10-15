package com.example.ipc

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copy this one file into a consumer app. No AIDL files or model weights are needed. */
class LocalAiPolishClient(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    data class Result(val status: String, val text: String? = null, val error: String? = null) {
        val isSuccess: Boolean get() = status == "SUCCESS" && !text.isNullOrBlank()
    }

    /** Also registers the consumer as pending in Better Intelligence > Apps. */
    suspend fun status(): Bundle = call("status", null)

    /** Proofreads English text. Returns replacement text only on SUCCESS; preview before applying. */
    suspend fun polish(text: String): Result {
        val result = call("polish", text)
        val status = result.getString("status") ?: "ERROR"
        return Result(status, if (status == "SUCCESS") result.getString("text") else null, result.getString("error"))
    }

    private suspend fun call(method: String, text: String?): Bundle = withContext(Dispatchers.IO) {
        try {
            resolver.call(URI, method, text, null) ?: failure("UNAVAILABLE", "Install Better Intelligence first")
        } catch (e: SecurityException) {
            failure("DENIED", "Declare the Better Intelligence local AI permission")
        } catch (e: IllegalArgumentException) {
            failure("UNAVAILABLE", "Install Better Intelligence first")
        } catch (e: android.os.RemoteException) {
            failure("UNAVAILABLE", "Better Intelligence disconnected")
        } catch (e: IllegalStateException) {
            failure("UNAVAILABLE", "Better Intelligence is unavailable")
        }
    }

    private fun failure(status: String, error: String) = Bundle().apply {
        putString("status", status); putString("error", error)
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.aistudio.betterintelligence.mobile.provider")
    }
}
