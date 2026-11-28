package com.example.tasks

import android.content.Context
import android.os.Bundle
import com.example.data.ModelEntity
import com.example.engine.ChatTurn
import com.example.engine.ModelLibrary
import com.example.engine.ProcessingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskAccess(val bit: Int, val label: String) {
    TEXT(0, "Text inference"),
    VISION(1, "Image understanding"),
    IMAGE_GENERATION(2, "Image generation and editing"),
    AUDIO(4, "Audio processing"),
    VOICE(8, "Voice generation"),
    VIDEO(16, "Video processing"),
    MEDIA_GENERATION(32, "Music, video and sound generation"),
    DOWNLOAD(64, "Request model downloads"),
    INTENSIVE(128, "Resource-intensive tasks")
}

enum class TaskType(val label: String, val filter: String, val access: TaskAccess,
    val input: String? = null, val output: String = "text/plain", val intensive: Boolean = false) {
    TEXT_GENERATION("Text generation", "Text", TaskAccess.TEXT),
    TEXT_POLISH("Proofreading", "Text", TaskAccess.TEXT),
    IMAGE_UNDERSTANDING("Image understanding", "Vision", TaskAccess.VISION, "image", intensive = true),
    IMAGE_GENERATION("Image generation", "Image", TaskAccess.IMAGE_GENERATION, intensive = true),
    IMAGE_EDITING("Image editing", "Image", TaskAccess.IMAGE_GENERATION, "image", intensive = true),
    IMAGE_UPSCALING("Image upscaling", "Image", TaskAccess.IMAGE_GENERATION, "image", intensive = true),
    BACKGROUND_REMOVAL("Background removal", "Image", TaskAccess.IMAGE_GENERATION, "image", intensive = true),
    AUDIO_TRANSCRIPTION("Transcription", "Voice", TaskAccess.AUDIO, "audio", intensive = true),
    AUDIO_UNDERSTANDING("Audio understanding", "Audio", TaskAccess.AUDIO, "audio", intensive = true),
    SPEECH_GENERATION("Speech generation", "Voice", TaskAccess.VOICE, output = "audio/wav"),
    VOICE_CONVERSATION("Voice conversation", "Voice", TaskAccess.VOICE, "audio", intensive = true),
    VIDEO_UNDERSTANDING("Video understanding", "Video", TaskAccess.VIDEO, "video", intensive = true),
    VIDEO_GENERATION("Video generation", "Video", TaskAccess.MEDIA_GENERATION, intensive = true),
    MUSIC_GENERATION("Music generation", "Audio", TaskAccess.MEDIA_GENERATION, intensive = true),
    SOUND_GENERATION("Sound generation", "Audio", TaskAccess.MEDIA_GENERATION, intensive = true),
    EMBEDDINGS("Embeddings", "Text", TaskAccess.TEXT, output = "application/x-bi-embedding-f32");

    val priority: Int get() = when {
        this == TEXT_POLISH -> 0
        intensive -> 2
        else -> 1
    }

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name == value }
    }
}

enum class TaskQuality(val label: String, val outputTokens: Int) {
    FAST("Fast", 128), BALANCED("Balanced", 256), QUALITY("Quality", 512)
}

data class TaskRequest(
    val type: TaskType,
    val text: String,
    val requestId: String,
    val history: List<ChatTurn>,
    val modelId: Long?,
    val quality: TaskQuality?,
    val mediaMime: String?,
    val languageTag: String?
) {
    companion object {
        const val API_VERSION = 3

        fun parse(bundle: Bundle, hasMedia: Boolean): TaskRequest {
            require(bundle.getInt("api_version") == API_VERSION) { "Use task API version 3" }
            val type = requireNotNull(TaskType.parse(bundle.getString("task_type"))) { "Unknown task type" }
            val id = bundle.getString("request_id").orEmpty()
            require(id.length <= 80 && id.all { it.isLetterOrDigit() || it in "-_." }) { "Invalid request ID" }
            val text = bundle.getString("text").orEmpty()
            val maxText = if (type == TaskType.TEXT_POLISH) 4000 else if (type.intensive) 1600 else 8192
            require(text.length <= maxText && (text.isNotBlank() || type.input != null)) { "Invalid text length" }
            require(type != TaskType.EMBEDDINGS || text.toByteArray().size <= 4096) { "Embedding input exceeds 4096 UTF-8 bytes" }
            val roles = bundle.getStringArrayList("context_roles").orEmpty()
            val messages = bundle.getStringArrayList("context_text").orEmpty()
            require(roles.size == messages.size && roles.size <= 12 && messages.sumOf { it.length } <= 6000 &&
                roles.all { it in setOf("user", "assistant") }) { "Invalid conversation context" }
            require((type == TaskType.TEXT_GENERATION || type == TaskType.IMAGE_UNDERSTANDING) || roles.isEmpty()) {
                "This task does not accept conversation context"
            }
            require(hasMedia == (type.input != null)) { "Incorrect number of media inputs" }
            val mime = bundle.getString("media_mime")
            require(!hasMedia || mime != null && mime.substringBefore('/') == type.input && mime.length <= 64) { "Invalid media type" }
            val format = bundle.getString("output_format")
            require(format == null || format == type.output) { "Unsupported output format" }
            val modelId = bundle.getLong("model_id").takeIf { it > 0 }
            val quality = bundle.getString("quality")?.let { value ->
                requireNotNull(TaskQuality.entries.firstOrNull { it.name == value }) { "Unknown quality preference" }
            }
            val language = bundle.getString("language_tag")?.also {
                require(it.length in 2..35 && it.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid language tag" }
            }
            return TaskRequest(type, text, id, roles.zip(messages).map { (role, body) -> ChatTurn(role, body) },
                modelId, quality, mime, language)
        }
    }
}

data class CapabilitySettings(
    val primaryId: Long? = null,
    val fallbackId: Long? = null,
    val auto: Boolean = true,
    val enabled: Boolean = true,
    val backend: ProcessingMode = ProcessingMode.AUTO,
    val quality: TaskQuality = TaskQuality.BALANCED
)

data class TaskResourcePolicy(val maxMemoryMb: Int = 0, val keepModelReady: Boolean = true, val respectBatterySaver: Boolean = true)

data class ModelDownloadApproval(val modelId: Long, val packageName: String)

internal class TaskSettings(context: Context) {
    private val prefs = context.getSharedPreferences("task_router_v3", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(TaskType.entries.associateWith(::read))
    val values = current.asStateFlow()
    private val resources = MutableStateFlow(TaskResourcePolicy(prefs.getInt("max_ram_mb", 0),
        prefs.getBoolean("keep_ready", true), prefs.getBoolean("respect_battery_saver", true)))
    val resourcePolicy = resources.asStateFlow()
    @Synchronized fun setResources(value: TaskResourcePolicy) {
        val adjusted = value.copy(maxMemoryMb = value.maxMemoryMb.coerceIn(0, 16384))
        check(prefs.edit().putInt("max_ram_mb", adjusted.maxMemoryMb).putBoolean("keep_ready", adjusted.keepModelReady)
            .putBoolean("respect_battery_saver", adjusted.respectBatterySaver).commit())
        resources.value = adjusted
    }

    private val approvals = MutableStateFlow(readApprovals())
    val downloadApprovals = approvals.asStateFlow()

    private fun readApprovals() = prefs.getStringSet("download_approvals", emptySet()).orEmpty().mapNotNull { value ->
        val id = value.substringBefore('#').toLongOrNull() ?: return@mapNotNull null
        ModelDownloadApproval(id, value.substringAfter('#'))
    }.toSet()

    @Synchronized fun requestDownload(modelId: Long, pkg: String): Boolean {
        val request = ModelDownloadApproval(modelId, pkg)
        if (request in approvals.value) return true
        if (approvals.value.size >= 16 || approvals.value.count { it.packageName == pkg } >= 2) return false
        saveApprovals(approvals.value + request)
        return true
    }

    @Synchronized fun clearDownload(request: ModelDownloadApproval) = saveApprovals(approvals.value - request)

    @Synchronized fun clearDownloadsFrom(pkg: String) = saveApprovals(approvals.value.filterNot { it.packageName == pkg }.toSet())

    private fun saveApprovals(updated: Set<ModelDownloadApproval>) {
        check(prefs.edit().putStringSet("download_approvals", updated.map { "${it.modelId}#${it.packageName}" }.toSet()).commit())
        approvals.value = updated
    }
    operator fun get(type: TaskType) = current.value.getValue(type)

    private fun read(type: TaskType): CapabilitySettings {
        val key = type.name
        return CapabilitySettings(
            prefs.getLong("${key}_primary", 0).takeIf { it > 0 },
            prefs.getLong("${key}_fallback", 0).takeIf { it > 0 },
            prefs.getBoolean("${key}_auto", true), prefs.getBoolean("${key}_enabled", true),
            ProcessingMode.entries.firstOrNull { it.name == prefs.getString("${key}_backend", "") } ?: ProcessingMode.AUTO,
            TaskQuality.entries.firstOrNull { it.name == prefs.getString("${key}_quality", "") } ?: TaskQuality.BALANCED
        )
    }

    @Synchronized fun set(type: TaskType, settings: CapabilitySettings) {
        val key = type.name
        check(prefs.edit().putLong("${key}_primary", settings.primaryId ?: 0)
            .putLong("${key}_fallback", settings.fallbackId ?: 0)
            .putBoolean("${key}_auto", settings.auto).putBoolean("${key}_enabled", settings.enabled)
            .putString("${key}_backend", settings.backend.name).putString("${key}_quality", settings.quality.name).commit())
        current.value = current.value + (type to settings)
    }

    @Synchronized fun update(type: TaskType, transform: (CapabilitySettings) -> CapabilitySettings) {
        set(type, transform(get(type)))
    }
}

/** Treat exact weights and validated runtime support as the source of capability, not Hub tags. */
internal object TaskModels {
    const val GEMMA_E2B = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val GEMMA_E4B = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
    const val LFM_VISION = "18cb1f93cf35cc8a7c3ea47a9854bacecccf2e80cca574e49e6da1bbd940fcec"
    const val BGE_SMALL = "f0b2fef971e8366438bfd2d9aefea1b0115919389448806d290237f638bae999"
    // Revisions enabled here have device validation for the enabled modality.
    val mediaTypes = setOf(TaskType.IMAGE_UNDERSTANDING, TaskType.AUDIO_UNDERSTANDING, TaskType.AUDIO_TRANSCRIPTION)
    val text = setOf(TaskType.TEXT_GENERATION, TaskType.TEXT_POLISH)
    val implemented get() = text + mediaTypes + setOf(TaskType.SPEECH_GENERATION, TaskType.EMBEDDINGS)

    fun capabilities(model: ModelEntity): Set<TaskType> {
        if (model.filename.endsWith(".gguf", true) && model.sha256Checksum == BGE_SMALL) return setOf(TaskType.EMBEDDINGS)
        val language = !model.architecture.contains("embedding", true) && !model.architecture.contains("bert", true) &&
            (model.filename.endsWith(".gguf", true) || model.filename.endsWith(".litertlm", true))
        if (!language) return emptySet()
        if (!model.filename.endsWith(".litertlm", true)) return text
        return text + when (model.sha256Checksum) {
            GEMMA_E2B, GEMMA_E4B -> mediaTypes
            LFM_VISION -> setOf(TaskType.IMAGE_UNDERSTANDING)
            else -> emptySet()
        }
    }

    fun estimateMb(model: ModelEntity, type: TaskType): Long =
        when {
            type in mediaTypes -> when (model.sha256Checksum) {
                GEMMA_E4B -> if (type == TaskType.IMAGE_UNDERSTANDING) 4350 else 3750
                LFM_VISION -> 1100
                else -> 3000
            }
            type == TaskType.EMBEDDINGS -> 600
            else -> ModelLibrary.memoryEstimate(model, 1024).totalMb
        }
}
