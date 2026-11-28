package com.example.tasks

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import com.example.data.AiCoreRepository
import com.example.data.IpcLogEntity
import com.example.data.ModelEntity
import com.example.engine.GenerationParams
import com.example.engine.ModelMemoryPlanner
import com.example.engine.NativeGguf
import com.example.engine.ProcessingMode
import com.example.ipc.IBiTaskCallback
import com.example.ipc.PolishText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One resource-owning task at a time. Priority applies at safe task boundaries. */
internal class TaskRouter(private val context: Context, private val repository: AiCoreRepository, private val scope: CoroutineScope) {
    private val settings = repository.taskSettings
    private val engine = repository.engine
    private val visionAudio = LiteRtMediaBackend(engine)
    private val speech = OfflineSpeechBackend(context)
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pending = mutableListOf<Work>()
    private val completed = LinkedHashMap<String, Finished>()
    private var active: Work? = null

    private data class Caller(val uid: Int, val pkg: String)
    private class Work(val id: String, val caller: Caller, val request: TaskRequest, val input: ParcelFileDescriptor?,
        val callback: IBiTaskCallback) {
        var status = "QUEUED"
        var job: Job? = null
        var selectedModel: Long = 0
        lateinit var death: IBinder.DeathRecipient
    }
    private data class Finished(val caller: Caller, val status: Bundle, val at: Long)

    init {
        scope.launch {
            for (ignored in wake) {
                while (true) {
                    val work = synchronized(lock) {
                        pending.minByOrNull { it.request.type.priority }?.also { chosen ->
                            pending.remove(chosen)
                            active = chosen
                        }
                    } ?: break
                    supervisorScope {
                        val job = launch(start = CoroutineStart.LAZY) { execute(work) }
                        synchronized(lock) {
                            work.job = job
                            if (work.status == "CANCELLING") job.cancel()
                        }
                        job.start()
                        job.join()
                        // A job cancelled while still lazy never reaches execute's finally.
                        if (synchronized(lock) { active === work }) finish(work, taskBundle("CANCELLED", work))
                    }
                }
            }
        }
    }

    suspend fun capabilities(pkg: String): Bundle {
        val device = engine.hardware.resources()
        val memoryCap = settings.resourcePolicy.value.maxMemoryMb
        val batteryLimited = settings.resourcePolicy.value.respectBatterySaver &&
            context.getSystemService(PowerManager::class.java).isPowerSaveMode
        val rows = TaskType.entries.map { type ->
            val configuration = settings[type]
            val access = repository.taskAccessStatus(pkg, type.access, type.intensive)
            val installed = if (type in TaskModels.implemented && type != TaskType.SPEECH_GENERATION)
                repository.orderedTaskModels(type, pkg, null, true, configuration) else emptyList()
            val available = type in TaskModels.implemented
            val cpuOnly = type in TaskModels.mediaTypes || type == TaskType.EMBEDDINGS
            val state = when {
                !available -> "CAPABILITY_UNAVAILABLE"
                !configuration.enabled -> "DISABLED"
                access != "READY" -> access
                type == TaskType.SPEECH_GENERATION -> if (speech.available()) "SYSTEM_VOICE_REQUIRED" else "CAPABILITY_UNAVAILABLE"
                installed.isEmpty() -> "MODEL_REQUIRED"
                cpuOnly && configuration.backend !in setOf(ProcessingMode.AUTO, ProcessingMode.CPU) -> "BACKEND_UNAVAILABLE"
                type.intensive && batteryLimited -> "RESOURCE_LIMIT"
                cpuOnly && (device.lowMemory || device.thermal >= 3 || installed.none {
                    val required = TaskModels.estimateMb(it, type)
                    val reclaimable = if (type in TaskModels.mediaTypes) engine.releasableGpuWeightsMb() else 0L
                    required <= ModelMemoryPlanner.budget(device.totalMb, device.availableMb + reclaimable) &&
                        (memoryCap == 0 || required <= memoryCap)
                }) -> "RESOURCE_LIMIT"
                else -> "READY"
            }
            bundle(state).apply {
                putString("task_type", type.name)
                putString("label", type.label)
                putBoolean("implemented", available)
                putInt("installed_models", installed.size)
                putString("input", type.input)
                putString("output_format", type.output)
                putBoolean("resource_intensive", type.intensive)
                putString("backend_preference", configuration.backend.name)
                when (state) {
                    "MODEL_REQUIRED" -> putString("message", "Install and select a compatible model in Models")
                    "RESOURCE_LIMIT" -> putString("message", if (type.intensive && batteryLimited) "Battery Saver is limiting this task"
                        else "The configured models need more available RAM or thermal headroom")
                    "DENIED" -> putString("message", if (type.intensive && repository.taskAccessStatus(pkg, type.access) == "READY")
                        "Also grant High-memory tasks under Apps" else "Grant ${type.access.label} under Apps")
                }
            }
        }
        return bundle("SUCCESS").apply { putParcelableArrayList("capabilities", ArrayList(rows)) }
    }

    private suspend fun allowedToList(pkg: String): Boolean =
        pkg == context.packageName || repository.isPackageAllowed(pkg)

    suspend fun installed(pkg: String): Bundle {
        repository.registerClient(pkg)
        if (!allowedToList(pkg)) return bundle("APPROVAL_REQUIRED")
        repository.awaitModelInitialization()
        val rows = repository.allModels.first().filter { it.isInstalled }.take(96).map { describe(it) }
        return bundle("SUCCESS").apply { putParcelableArrayList("models", ArrayList(rows)) }
    }

    suspend fun recommended(pkg: String, typeName: String?): Bundle {
        val type = TaskType.parse(typeName) ?: return bundle("INVALID_REQUEST")
        val access = repository.taskAccessStatus(pkg, type.access, type.intensive)
        if (access != "READY") return bundle(access)
        if (type !in TaskModels.implemented) return bundle("CAPABILITY_UNAVAILABLE")
        val state = engine.hardware.resources()
        val cap = settings.resourcePolicy.value.maxMemoryMb
        val memory = com.example.engine.ModelMemoryPlanner.budget(state.totalMb, state.availableMb + engine.releasableGpuWeightsMb())
        val configuration = settings[type].copy(auto = true)
        val candidates = repository.orderedTaskModels(type, pkg, null, false, configuration)
            .filter { TaskModels.estimateMb(it, type) <= memory && (cap == 0 || TaskModels.estimateMb(it, type) <= cap) }
            .take(12).map { describe(it).apply { putLong("estimated_ram_mb", TaskModels.estimateMb(it, type)) } }
        return bundle("SUCCESS").apply { putParcelableArrayList("models", ArrayList(candidates)) }
    }

    suspend fun modelStatus(pkg: String, id: Long): Bundle {
        repository.registerClient(pkg)
        if (!allowedToList(pkg)) return bundle("APPROVAL_REQUIRED")
        repository.awaitModelInitialization()
        return repository.modelForDownload(id)?.let { describe(it) } ?: bundle("MODEL_NOT_FOUND")
    }

    suspend fun requestDownload(pkg: String, id: Long): Bundle {
        val access = repository.taskAccessStatus(pkg, TaskAccess.DOWNLOAD)
        if (access != "READY") return bundle(access)
        if (!repository.checkSecurityAndRateLimit(pkg, TaskAccess.DOWNLOAD.name).first) return bundle("RATE_LIMITED")
        repository.awaitModelInitialization()
        val model = repository.modelForDownload(id) ?: return bundle("MODEL_NOT_FOUND")
        if (model.isInstalled || model.downloadState in setOf("QUEUED", "DOWNLOADING")) return describe(model)
        if (model.downloadUrl == null || model.sha256Checksum.isNullOrBlank()) return bundle("CAPABILITY_UNAVAILABLE")
        return bundle(if (settings.requestDownload(id, pkg)) "USER_DOWNLOAD_APPROVAL_REQUIRED" else "QUEUE_FULL")
    }

    private suspend fun describe(model: ModelEntity): Bundle {
        val telemetry = engine.telemetry.value
        val selected = synchronized(lock) { active?.selectedModel == model.id && active?.status == "PROCESSING" }
        val current = repository.activeModel.first()?.id == model.id
        val status = when {
            selected && telemetry.isRunning -> "PROCESSING"
            current && telemetry.isRunning -> "PROCESSING"
            current && engine.isReady() -> "READY"
            model.isInstalled -> "INSTALLED"
            model.downloadState == "NOT_DOWNLOADED" && model.downloadUrl == null -> "UNAVAILABLE"
            else -> model.downloadState
        }
        return bundle(status).apply {
            putLong("model_id", model.id)
            putString("name", model.name)
            putString("format", model.filename.substringAfterLast('.'))
            putString("quantization", model.quantization)
            putLong("download_mb", model.fileSizeMb)
            putFloat("download_progress", model.downloadProgress)
            putBoolean("downloaded", model.isInstalled)
            putBoolean("installed", model.isInstalled)
            putBoolean("loaded", current && engine.isReady() || selected && telemetry.activeBackend.isNotBlank() && telemetry.isRunning)
            putStringArrayList("capabilities", ArrayList(TaskModels.capabilities(model).map { it.name }))
        }
    }

    suspend fun submit(pkg: String, uid: Int, data: Bundle?, media: ParcelFileDescriptor?, callback: IBiTaskCallback?): Bundle {
        var retained = false
        try {
            if (data == null || callback == null) return bundle("INVALID_REQUEST")
            if (data.getInt("api_version") != TaskRequest.API_VERSION) return bundle("VERSION_UNSUPPORTED")
            val type = TaskType.parse(data.getString("task_type")) ?: return bundle("INVALID_REQUEST")
            if (type !in TaskModels.implemented) return bundle("CAPABILITY_UNAVAILABLE")
            val request = TaskRequest.parse(data, media != null)
            val access = repository.taskAccessStatus(pkg, type.access, type.intensive)
            if (access != "READY") return bundle(access)
            if (!settings[type].enabled) return bundle("DISABLED")
            if (!repository.checkSecurityAndRateLimit(pkg, if (type.access == TaskAccess.TEXT) "INFERENCE" else type.access.name).first) {
                return bundle("RATE_LIMITED")
            }
            val caller = Caller(uid, pkg)
            val work = Work(UUID.randomUUID().toString(), caller, request, media, callback)
            synchronized(lock) {
                if (pending.size >= 8 || pending.count { it.caller == caller } + (if (active?.caller == caller) 1 else 0) >= 2)
                    return bundle("QUEUE_FULL")
                work.death = IBinder.DeathRecipient { cancel(pkg, uid, work.id) }
                try { callback.asBinder().linkToDeath(work.death, 0) }
                catch (_: android.os.RemoteException) { return bundle("CALLER_GONE") }
                pending.add(work)
                retained = true
                wake.trySend(Unit)
            }
            return taskBundle("QUEUED", work)
        } catch (_: IllegalArgumentException) { return bundle("INVALID_REQUEST") }
        catch (_: android.os.BadParcelableException) { return bundle("INVALID_REQUEST") }
        finally { if (!retained) runCatching { media?.close() } }
    }

    fun status(pkg: String, uid: Int, id: String?): Bundle = synchronized(lock) {
        evictFinished()
        val caller = Caller(uid, pkg)
        val work = active?.takeIf { it.id == id && it.caller == caller } ?: pending.find { it.id == id && it.caller == caller }
        if (work != null) taskBundle(work.status, work)
        else completed[id]?.takeIf { it.caller == caller }?.status?.let(::Bundle) ?: bundle("TASK_NOT_FOUND")
    }

    fun cancel(pkg: String, uid: Int, id: String?): Boolean {
        val caller = Caller(uid, pkg)
        val waiting = synchronized(lock) {
            val running = active
            if (running != null && running.id == id && running.caller == caller) {
                running.status = "CANCELLING"
                running.job?.cancel()
                return true
            }
            pending.find { it.id == id && it.caller == caller }?.also { pending.remove(it) }
        } ?: return false
        finish(waiting, taskBundle("CANCELLED", waiting))
        return true
    }

    fun close() {
        val detached = synchronized(lock) {
            wake.close()
            active?.job?.cancel()
            pending.toList().also { pending.clear() }
        }
        detached.forEach { finish(it, taskBundle("CANCELLED", it)) }
    }

    private suspend fun execute(work: Work) {
        val request = work.request
        val started = SystemClock.elapsedRealtime()
        var fd: ParcelFileDescriptor? = null
        var response = taskBundle("ERROR", work)
        try {
            phase(work, "WAITING_FOR_ENGINE")
            val result = repository.clientExecution.withLock {
                val access = repository.taskAccessStatus(work.caller.pkg, request.type.access, request.type.intensive)
                if (access != "READY") throw TaskFailure(access, "Grant this capability in Better Intelligence")
                val configuration = settings[request.type]
                if (!configuration.enabled) throw TaskFailure("DISABLED", "This capability is turned off")
                if (request.type.intensive && settings.resourcePolicy.value.respectBatterySaver &&
                    context.getSystemService(PowerManager::class.java).isPowerSaveMode) {
                    throw TaskFailure("RESOURCE_LIMIT", "Turn off Battery Saver or disable this limit in Better Intelligence")
                }
                val media = work.input?.let { TaskMedia.read(it, request.type, request.mediaMime) }
                when (request.type) {
                    TaskType.SPEECH_GENERATION -> speech.run(request, configuration) { phase(work, it) }
                    TaskType.EMBEDDINGS -> embedding(work, configuration)
                    TaskType.TEXT_GENERATION, TaskType.TEXT_POLISH -> text(work, configuration)
                    in TaskModels.mediaTypes -> media(work, configuration, checkNotNull(media))
                    else -> throw TaskFailure("CAPABILITY_UNAVAILABLE", "No validated runtime for this task")
                }
            }
            fd = result.media
            currentCoroutineContext().ensureActive()
            response = taskBundle("SUCCESS", work).apply {
                putString("text", result.text)
                result.embedding?.let { putFloatArray("embedding", it) }
                putLong("model_id", result.modelId)
                putString("model_name", result.modelName)
                putString("runtime", result.runtime)
                putString("backend", result.backend)
                putLong("load_ms", result.loadMs)
                putLong("first_token_ms", result.firstTokenMs)
                putFloat("tokens_per_second", result.tokensPerSecond)
                putFloat("observed_ram_mb", result.memoryMb)
                putString("thermal_state", result.thermal)
            }
        } catch (_: CancellationException) { response = taskBundle("CANCELLED", work) }
        catch (e: TaskFailure) { response = taskBundle(e.code, work).apply { putString("message", e.message) } }
        catch (_: IllegalArgumentException) { response = taskBundle("INVALID_INPUT", work) }
        catch (error: Exception) {
            android.util.Log.w("BiTaskRouter", "${request.type.name} failed: ${error.javaClass.simpleName}")
            response = taskBundle("INFERENCE_FAILED", work)
        }
        finally {
            response.putLong("latency_ms", SystemClock.elapsedRealtime() - started)
            finish(work, response, fd)
            repository.idleTaskResources(!settings.resourcePolicy.value.keepModelReady)
        }
    }

    private suspend fun text(work: Work, configuration: CapabilitySettings): TaskResult {
        val request = work.request
        val polishing = request.type == TaskType.TEXT_POLISH
        val quality = request.quality ?: configuration.quality
        val size = repository.preferencesRepository.generationPreferences.first().contextSize.coerceIn(512, 4096)
        val model = repository.selectTaskTextModel(work.caller.pkg, request.type, request.modelId,
            configuration.copy(quality = quality), size) {
            synchronized(lock) { work.selectedModel = it.id }
            phase(work, "LOADING")
        }
        synchronized(lock) { work.selectedModel = model.id }
        phase(work, "PROCESSING")
        val params = if (polishing) PolishText.parameters(size, request.text)
        else GenerationParams(contextSize = size, maxTokens = quality.outputTokens,
            history = request.history, systemPrompt = repository.buildClientSystemPrompt(work.caller.pkg, null))
        val text = engine.generateTaskText(if (polishing) PolishText.prompt(request.text) else request.text,
            params.copy(processingOverride = configuration.backend.takeUnless { it == ProcessingMode.AUTO })) {
            work.callback.onText(work.id, it)
        }
        check(text.isNotBlank())
        val t = engine.telemetry.value
        return TaskResult(text, model.id, model.name, if (model.filename.endsWith(".litertlm", true)) "LiteRT-LM" else "llama.cpp",
            t.activeBackend, t.modelLoadTimeMs, t.timeToFirstTokenMs, t.tokensPerSecond, t.peakMemoryUsageMb, t.thermalState)
    }

    private suspend fun media(work: Work, config: CapabilitySettings, data: ByteArray): TaskResult {
        val request = work.request
        val effective = config.copy(quality = request.quality ?: config.quality)
        val models = repository.orderedTaskModels(request.type, work.caller.pkg, request.modelId, true, effective)
        if (models.isEmpty()) throw TaskFailure("MODEL_REQUIRED", if (request.type == TaskType.IMAGE_UNDERSTANDING)
            "Install a compatible vision model in Models, such as LFM 2.5 Vision or Gemma 4 LiteRT"
        else "Install Gemma 4 E2B or E4B LiteRT for audio in Models")
        val cap = settings.resourcePolicy.value.maxMemoryMb
        var rejected: TaskFailure? = null
        for (model in models.take(3)) {
            val estimate = TaskModels.estimateMb(model, request.type)
            if (cap > 0 && estimate > cap) continue
            synchronized(lock) { work.selectedModel = model.id }
            var streamed = false
            try {
                return repository.withTaskModel(model) { file ->
                    visionAudio.run(model, file, request, effective, data, { phase(work, it) }) { delta ->
                        streamed = true
                        work.callback.onText(work.id, delta)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: TaskFailure) { rejected = e }
            catch (e: Exception) {
                if (streamed) throw e
                android.util.Log.w("BiTaskRouter", "Media model initialization failed: ${e.javaClass.simpleName}")
                rejected = TaskFailure("INFERENCE_FAILED", "A compatible media model could not start. Try another installed model.")
            }
        }
        throw rejected ?: TaskFailure("RESOURCE_LIMIT", "Models exceed the configured memory limit")
    }

    private suspend fun embedding(work: Work, config: CapabilitySettings): TaskResult {
        if (config.backend !in setOf(ProcessingMode.AUTO, ProcessingMode.CPU)) {
            throw TaskFailure("BACKEND_UNAVAILABLE", "BGE Small runs on CPU")
        }
        val type = TaskType.EMBEDDINGS
        val model = repository.orderedTaskModels(type, work.caller.pkg, work.request.modelId, true, config).firstOrNull()
            ?: throw TaskFailure("MODEL_REQUIRED", "Install BGE Small English in Models")
        return repository.withTaskModel(model) { file ->
            val resources = engine.hardware.resources()
            val required = TaskModels.estimateMb(model, type)
            val cap = settings.resourcePolicy.value.maxMemoryMb
            if (resources.lowMemory || resources.thermal >= 3 || (cap > 0 && required > cap) ||
                required > ModelMemoryPlanner.budget(resources.totalMb, resources.availableMb)) {
                throw TaskFailure("RESOURCE_LIMIT", "BGE Small needs about $required MB of safe RAM")
            }
            synchronized(lock) { work.selectedModel = model.id }
            phase(work, "PROCESSING")
            currentCoroutineContext().ensureActive()
            // The bounded CPU call loads the encoder, returns a normalized vector, and frees it.
            val vector = NativeGguf.embedText(file.path.toByteArray(), arrayOf(work.request.text.toByteArray()))
            currentCoroutineContext().ensureActive()
            if (vector == null || vector.size != 384) throw TaskFailure("INFERENCE_FAILED", "BGE could not encode the text")
            TaskResult(modelId = model.id, modelName = model.name, runtime = "llama.cpp", backend = "CPU",
                loadMs = 0, embedding = vector)
        }
    }

    private fun phase(work: Work, value: String) {
        val event = synchronized(lock) {
            if (work.status == "CANCELLING") return
            work.status = value
            taskBundle(value, work)
        }
        runCatching { work.callback.onProgress(event) }
    }

    private fun finish(work: Work, status: Bundle, media: ParcelFileDescriptor? = null) {
        val result = synchronized(lock) {
            if (active === work) active = null
            val final = if (work.status == "CANCELLING") taskBundle("CANCELLED", work) else status
            work.status = final.getString("status") ?: "ERROR"
            completed[work.id] = Finished(work.caller, Bundle(final).apply {
                remove("text")
                remove("embedding")
            }, SystemClock.elapsedRealtime())
            evictFinished()
            final
        }
        runCatching { work.input?.close() }
        runCatching { work.callback.asBinder().unlinkToDeath(work.death, 0) }
        try {
            work.callback.onFinished(result, media.takeIf { result.getString("status") == "SUCCESS" })
        } catch (_: Exception) {
            // The client can disconnect between native completion and callback.
        } finally { runCatching { media?.close() } }
        scope.launch(Dispatchers.IO) {
            repository.logIpcCall(IpcLogEntity(callingPackage = work.caller.pkg, callingUid = work.caller.uid,
                requestType = work.request.type.name, promptPreview = "", responsePreview = "",
                latencyMs = result.getLong("latency_ms"), tokensPerSecond = result.getFloat("tokens_per_second"),
                totalTokens = 0, status = work.status))
        }
    }

    private fun evictFinished() {
        val now = SystemClock.elapsedRealtime()
        completed.entries.removeAll { now - it.value.at > 10 * 60 * 1000 }
        while (completed.size > 64) completed.remove(completed.keys.first())
    }

    private fun taskBundle(status: String, work: Work) = bundle(status).apply {
        putString("task_id", work.id)
        putString("request_id", work.request.requestId)
        putString("task_type", work.request.type.name)
        putString("output_format", work.request.type.output)
        putLong("model_id", work.selectedModel)
        putInt("progress", when (status) {
            "QUEUED", "WAITING_FOR_ENGINE" -> 0
            "LOADING" -> 15
            "PROCESSING" -> 35
            "SUCCESS" -> 100
            else -> -1
        })
    }

    companion object {
        fun bundle(status: String) = Bundle().apply {
            putInt("api_version", TaskRequest.API_VERSION)
            putString("status", status)
        }
    }
}
