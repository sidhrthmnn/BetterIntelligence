package com.example.tasks

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Android's installed offline voices. No remote voice or app-provided engine is selected. */
internal class OfflineSpeechBackend(private val context: Context) {
    fun available(): Boolean = enginePackages().isNotEmpty()

    private fun enginePackages(): Set<String> =
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.applicationInfo }
            .filter { it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            .map { it.packageName }.toSet()

    suspend fun run(request: TaskRequest, settings: CapabilitySettings, phase: (String) -> Unit): TaskResult {
        if (!available()) throw TaskFailure("CAPABILITY_UNAVAILABLE", "No system speech engine is installed")
        if (settings.backend !in setOf(com.example.engine.ProcessingMode.AUTO, com.example.engine.ProcessingMode.CPU)) {
            throw TaskFailure("BACKEND_UNAVAILABLE", "System speech does not expose a GPU or NPU selection")
        }
        require(request.text.length <= TextToSpeech.getMaxSpeechInputLength()) { "Speech text is too long" }
        phase("LOADING")
        val started = SystemClock.elapsedRealtime()
        val init = CompletableDeferred<Int>()
        var engine: TextToSpeech? = null
        val output = File.createTempFile("speech-", ".wav", context.cacheDir)
        try {
            withContext(Dispatchers.Main.immediate) {
                engine = TextToSpeech(context) { value -> init.complete(value) }
            }
            val tts = checkNotNull(engine)
            if (withTimeoutOrNull(10_000) { init.await() } != TextToSpeech.SUCCESS || tts.defaultEngine !in enginePackages()) {
                throw TaskFailure("CAPABILITY_UNAVAILABLE", "The selected speech engine is unavailable for offline synthesis")
            }
            val locale = request.languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            val voice = tts.voices.orEmpty().filter {
                !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features &&
                    it.locale.language == locale.language
            }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == locale.country }
                .thenByDescending { it.quality }.thenBy { it.name }).firstOrNull()
                ?: throw TaskFailure("MODEL_REQUIRED", "Install an offline voice for ${locale.displayLanguage} in Android text-to-speech settings")
            if (tts.setVoice(voice) != TextToSpeech.SUCCESS || tts.voice?.name != voice.name) {
                throw TaskFailure("MODEL_REQUIRED", "Android could not activate the installed offline voice")
            }
            val loadMs = SystemClock.elapsedRealtime() - started
            val id = UUID.randomUUID().toString()
            val completion = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id) completion.complete(true) }
                @Deprecated("Android callback")
                override fun onError(utteranceId: String?) { if (utteranceId == id) completion.complete(false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id) completion.complete(false)
                }
            })
            phase("PROCESSING")
            check(tts.synthesizeToFile(request.text, Bundle(), output, id) == TextToSpeech.SUCCESS &&
                withTimeoutOrNull(60_000) { completion.await() } == true) { "Offline speech synthesis failed" }
            check(output.length() in 45..(16 * 1024 * 1024)) { "Speech output is missing or exceeds its size limit" }
            val header = output.inputStream().use { stream -> ByteArray(12).also { check(stream.read(it) == it.size) } }
            check(header.copyOfRange(0, 4).decodeToString() == "RIFF" && header.copyOfRange(8, 12).decodeToString() == "WAVE")
            return TaskResult(modelName = voice.name, runtime = "Android offline speech", backend = "System-managed",
                loadMs = loadMs, media = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY))
        } finally {
            withContext(NonCancellable) {
                engine?.stop()
                engine?.shutdown()
                output.delete()
            }
        }
    }
}
