package com.example.tasks

import android.os.SystemClock
import com.example.data.ModelEntity
import com.example.engine.GenerationParams
import com.example.engine.InferenceEngine
import com.example.engine.LiteRtSession
import com.example.engine.ModelMemoryPlanner
import com.example.engine.ProcessingMode
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Modality
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class TaskResult(
    val text: String = "",
    val modelId: Long = 0,
    val modelName: String = "",
    val runtime: String,
    val backend: String,
    val loadMs: Long,
    val firstTokenMs: Long = 0,
    val tokensPerSecond: Float = 0f,
    val memoryMb: Float = 0f,
    val thermal: String = "",
    val media: android.os.ParcelFileDescriptor? = null,
    val embedding: FloatArray? = null
)

internal class TaskFailure(val code: String, message: String) : Exception(message)

internal class LiteRtMediaBackend(private val engine: InferenceEngine) {
    suspend fun run(model: ModelEntity, file: File, request: TaskRequest, settings: CapabilitySettings,
        samples: ByteArray, phase: (String) -> Unit, stream: (String) -> Unit): TaskResult {
        val modality = if (request.type.input == "image") Modality.VISION else Modality.AUDIO
        if (settings.backend !in setOf(ProcessingMode.AUTO, ProcessingMode.CPU)) {
            throw TaskFailure("BACKEND_UNAVAILABLE", "This media package currently supports CPU only")
        }
        val required = TaskModels.estimateMb(model, request.type)
        val before = engine.hardware.resources()
        if (before.lowMemory || before.thermal >= 3 || required >
            ModelMemoryPlanner.budget(before.totalMb, before.availableMb + engine.releasableGpuWeightsMb())) {
            throw TaskFailure("RESOURCE_LIMIT", "This media model needs about $required MB of safe RAM and a cool device")
        }
        return engine.withMediaEngine(model.name) { context, resources ->
            if (resources.lowMemory || resources.thermal >= 3 || required > ModelMemoryPlanner.budget(resources.totalMb, resources.availableMb)) {
                throw TaskFailure("RESOURCE_LIMIT", "Not enough memory or thermal headroom to load the media model")
            }
            currentCoroutineContext().ensureActive()
            phase("LOADING")
            val threads = engine.hardware.threads(state = resources).coerceAtMost(4)
            val start = SystemClock.elapsedRealtime()
            LiteRtSession(context, file, identity = model.sha256Checksum.orEmpty(), mediaModality = modality).use { session ->
                val contextSize = session.configurationContext(1024)
                session.prepare(ProcessingMode.CPU, threads, contextSize)
                currentCoroutineContext().ensureActive()
                val afterLoad = engine.hardware.resources()
                if (afterLoad.lowMemory || afterLoad.availableMb < 256 || afterLoad.thermal >= 3) {
                    throw TaskFailure("RESOURCE_LIMIT", "Memory or thermal pressure increased while loading the model")
                }
                val load = SystemClock.elapsedRealtime() - start
                engine.mediaTelemetry(model.name, load, threads)
                phase("PROCESSING")
                val prompt = when (request.type) {
                    TaskType.AUDIO_TRANSCRIPTION -> "Transcribe the spoken words exactly. Return only the transcript."
                    TaskType.AUDIO_UNDERSTANDING -> request.text.ifBlank { "Describe the audible sounds in this recording." }
                    else -> request.text.ifBlank { "Describe this image." }
                }
                val content = if (modality == Modality.VISION) Content.ImageBytes(samples) else Content.AudioBytes(samples)
                val result = session.generateMedia(prompt, content, GenerationParams(contextSize = contextSize,
                    temperature = .1f, maxTokens = (request.quality ?: settings.quality).outputTokens.coerceAtMost(256),
                    history = request.history)) { delta, _ -> stream(delta) }
                check(result.text.isNotBlank()) { "The media model returned no visible text" }
                engine.mediaTelemetry(model.name, load, threads, result)
                val telemetry = engine.telemetry.value
                TaskResult(result.text, model.id, model.name, "LiteRT-LM", "CPU", load, result.firstTokenMs,
                    result.tokensPerSecond, telemetry.peakMemoryUsageMb, telemetry.thermalState)
            }
        }
    }
}
