package com.example.data

import android.content.Context
import androidx.room.withTransaction
import com.example.engine.ChecksumVerificationResult
import com.example.engine.DownloadStateUpdate
import com.example.engine.EngineManager
import com.example.engine.GgufMetadata
import com.example.engine.HardwareAnalyzer
import com.example.engine.HardwareProfile
import com.example.engine.HuggingFaceGgufFile
import com.example.engine.HuggingFaceModelSearchResult
import com.example.engine.InferenceEngine
import com.example.engine.ModelDownloadManager
import com.example.engine.ModelRecommendation
import com.example.engine.QuantizationType
import com.example.engine.SupportedEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.example.engine.ModelLibrary
import com.example.engine.ModelMemoryPlanner
import com.example.engine.GenerationParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class AiCoreRepository(
    private val appContext: Context,
    private val database: AppDatabase,
    val engineManager: EngineManager,
    val preferencesRepository: SettingsPreferencesRepository
) {
    val engine: InferenceEngine
        get() = engineManager.llamaCppEngine.coreInferenceEngine
    val downloadManager = ModelDownloadManager(appContext)
    private val modelDao = database.modelDao()
    private val ipcLogDao = database.ipcLogDao()
    private val clientPolicyDao = database.clientPolicyDao()
    private val chatDao = database.chatDao()
    private val conversationDao = database.conversationDao()
    val conversations = conversationDao.all()
    fun messages(conversationId: Long) = chatDao.messages(conversationId)
    suspend fun conversation(id: Long) = conversationDao.get(id)
    suspend fun createConversation() = conversationDao.insert(ConversationEntity())
    suspend fun saveDraft(id: Long, draft: String) = conversationDao.saveDraft(id, draft)
    suspend fun saveGroundingMode(id: Long, mode: String) = conversationDao.saveGroundingMode(id, mode)
    suspend fun deleteConversation(id: Long) = database.withTransaction {
        chatDao.deleteConversationMessages(id)
        conversationDao.delete(id)
    }
    suspend fun deleteMessagesFrom(conversationId: Long, messageId: Long) = chatDao.deleteFrom(conversationId, messageId)
    suspend fun beginChatTurn(message: ChatMessageEntity, replaceFrom: Long?): List<ChatMessageEntity> = database.withTransaction {
        check(conversationDao.get(message.conversationId) != null) { "Conversation is no longer available" }
        if (replaceFrom != null) chatDao.deleteFrom(message.conversationId, replaceFrom)
        val history = chatDao.messageSnapshot(message.conversationId)
        chatDao.insertMessage(message)
        conversationDao.touch(message.conversationId, message.content.replace('\n', ' ').take(70), message.timestamp)
        history
    }
    private val memoryDao = database.memoryDao()
    private val performanceLogDao = database.performanceLogDao()

    val allModels: Flow<List<ModelEntity>> = modelDao.getAllModels()
    val activeModel: Flow<ModelEntity?> = modelDao.getActiveModel()
    val recentIpcLogs: Flow<List<IpcLogEntity>> = ipcLogDao.getRecentLogs()
    val totalIpcCalls: Flow<Int> = ipcLogDao.getTotalCallsCount()
    val clientPolicies: Flow<List<ClientAppPolicyEntity>> = clientPolicyDao.getAllPolicies()
    val chatMessages: Flow<List<ChatMessageEntity>> = chatDao.getAllMessages()
    val allMemories: Flow<List<MemorySnippetEntity>> = memoryDao.getAllMemories()
    val activeMemories: Flow<List<MemorySnippetEntity>> = memoryDao.getActiveMemories()
    val allPerformanceLogs: Flow<List<HardwarePerformanceLogEntity>> = performanceLogDao.getAllLogs()

    val savedEnginePreference: Flow<SupportedEngine> = preferencesRepository.selectedEngineFlow

    // Download jobs map
    private val activeDownloadJobs = ConcurrentHashMap<Long, Job>()
    private val _downloadErrors = MutableStateFlow<Map<Long, String>>(emptyMap())
    val downloadErrors = _downloadErrors.asStateFlow()

    // Rate limiting tracker: package -> list of request timestamps in ms
    private val rateLimitWindows = ConcurrentHashMap<String, MutableList<Long>>()

    // Hardware Telemetry state
    private val _hardwareProfile = MutableStateFlow<HardwareProfile?>(null)
    val hardwareProfile: StateFlow<HardwareProfile?> = _hardwareProfile.asStateFlow()

    private val _modelRecommendation = MutableStateFlow<ModelRecommendation?>(null)
    val modelRecommendation: StateFlow<ModelRecommendation?> = _modelRecommendation.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val modelLock = Mutex()
    private val verifiedFiles = com.example.engine.VerifiedModelFileCache(appContext)
    @Volatile private var explicitModelSelection = false
    private val modelOperations = java.util.concurrent.atomic.AtomicInteger(1) // Startup/restore owns the first lease.
    val isModelLoading: Boolean get() = modelOperations.get() > 0

    private suspend fun <T> withModelLoading(block: suspend () -> T): T {
        modelOperations.incrementAndGet()
        try { return block() } finally { modelOperations.decrementAndGet() }
    }

    init {
        repositoryScope.launch {
            try {
                engine.refreshHardware()
                modelLock.withLock { seedDefaultModelsIfEmpty() }
                analyzeHardware(appContext)
                val previous = modelDao.getActiveModel().first()
                if (previous != null) {
                    try { activateModel(previous, restoring = true) }
                    catch (e: Exception) { if (!explicitModelSelection && !engine.isReady()) modelDao.clearActiveModel() }
                }
            } finally { modelOperations.decrementAndGet() }
        }
        repositoryScope.launch {
            while (true) {
                delay(10_000)
                if (_hardwareProfile.subscriptionCount.value > 0) analyzeHardware(appContext)
            }
        }
    }

    fun analyzeHardware(context: Context) {
        repositoryScope.launch {
            val profile = HardwareAnalyzer.analyze(context.applicationContext)
            _hardwareProfile.value = profile
            _modelRecommendation.value = ModelLibrary.recommend(modelDao.getAllModels().first(), profile)
        }
    }

    suspend fun ensureBaseModelInstalled(): ModelEntity {
        val model = modelDao.getBaseModel() ?: error("Model library is still initializing")
        check(model.isInstalled) { "Download ${model.name} in Models first" }
        switchActiveModel(model)
        return model
    }

    private suspend fun seedDefaultModelsIfEmpty() {
        // Replace old demo entries, preserving user imports and genuine installed files.
        val library = ModelLibrary.read(appContext)
        modelDao.getAllModels().first().filter { it.isCustom && it.isInstalled }.forEach { model ->
            model.localFilePath?.let { path -> com.example.engine.GgufParser().parseFromFile(File(path)).getOrNull()?.let { ModelLibrary.recordMetadata(model.filename, it) } }
        }
        // Catalogue curation never deletes an existing model row or its weights.
        // This also preserves downloads/imports if a publisher removes an entry.
        library.forEach { entry ->
            val existing = modelDao.getModelByFilename(entry.filename)
            val file = File(downloadManager.modelsDirectory, entry.filename)
            val installed = verifiedFiles.verify(file, entry.sha256Checksum) {
                downloadManager.verifyLocalFile(file, entry.sha256Checksum).isMatch
            }
            val updated = entry.copy(id = existing?.id ?: 0L, isInstalled = installed,
                lastUsedTimestamp = existing?.lastUsedTimestamp ?: 0L,
                isActive = installed && existing?.isActive == true,
                localFilePath = if (installed) file.absolutePath else null,
                downloadState = if (installed) "DOWNLOADED" else if (File(downloadManager.modelsDirectory, "${entry.filename}.part").length() > 0) "PAUSED" else "NOT_DOWNLOADED",
                downloadProgress = if (installed) 1f else (File(downloadManager.modelsDirectory, "${entry.filename}.part").length().toFloat() / (entry.fileSizeMb * 1048576).coerceAtLeast(1)).coerceIn(0f, .99f),
                isChecksumVerified = installed,
                checksumVerificationStatus = if (installed) "VERIFIED" else "NOT_VERIFIED")
            modelDao.insertModel(updated)
        }
    }

    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        explicitModelSelection = true
        modelLock.withLock { engine.unloadActiveModel(); modelDao.clearActiveModel() }
    }

    suspend fun switchActiveModel(model: ModelEntity) {
        explicitModelSelection = true
        activateModel(model)
    }

    private suspend fun activateModel(model: ModelEntity, restoring: Boolean = false) = withContext(Dispatchers.IO) {
        modelLock.withLock {
            if (restoring && (explicitModelSelection || engine.isReady())) return@withLock
            withModelLoading {
                check(model.isInstalled) { "Download this model first" }
                require(model.filename.endsWith(".gguf", true) || model.filename.endsWith(".litertlm", true)) { "Select a GGUF or LiteRT-LM instruction model" }
                val file = File(model.localFilePath ?: File(downloadManager.modelsDirectory, model.filename).absolutePath)
                check(file.isFile) { "Model file is missing; download it again" }
                if (model.architecture.startsWith("gemma") && !model.architecture.startsWith("gemma4")) {
                    check(appContext.getSharedPreferences("model_terms", Context.MODE_PRIVATE).getBoolean("gemma", false)) {
                        "Accept the Gemma terms in Models first"
                    }
                }
                val profile = HardwareAnalyzer.analyze(appContext)
                // Do not credit CPU mmap weights: available RAM already includes file cache.
                val oldWeightsMb = engine.releasableGpuWeightsMb()
                val available = (profile.availableRamMb + oldWeightsMb).coerceAtMost(profile.totalRamMb)
                val adjusted = profile.copy(availableRamMb = available,
                    safeModelRamLimitMb = ModelMemoryPlanner.budget(profile.totalRamMb, available))
                val liteRt = model.filename.endsWith(".litertlm", true)
                val requestedContext = preferencesRepository.generationPreferences.first().contextSize
                ModelLibrary.incompatibilityReason(model, adjusted, if (liteRt) minOf(requestedContext, model.contextLength) else 512)?.let { error(it) }
                val result = if (liteRt) engine.loadLiteRt(file, model.name, model.sha256Checksum.orEmpty(), model.quantization, requestedContext)
                    else engine.loadCustomGguf(file, model.sha256Checksum.orEmpty()).map { Unit }
                if (result.isFailure) { modelDao.clearActiveModel(); result.getOrThrow() }
                engineManager.selectEngine(if (liteRt) SupportedEngine.LITERT else SupportedEngine.LLAMA_CPP)
                modelDao.clearActiveModel()
                modelDao.setActiveModel(model.id)
            }
        }
    }

    fun startModelDownload(model: ModelEntity, scope: CoroutineScope) {
        if (activeDownloadJobs.containsKey(model.id)) return
        _downloadErrors.value = _downloadErrors.value - model.id

        val job = repositoryScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // Set state to DOWNLOADING
                modelDao.updateModel(
                    model.copy(
                        downloadState = "DOWNLOADING",
                        downloadProgress = 0.01f,
                        downloadSpeedMbps = 0f,
                        checksumVerificationStatus = "NOT_VERIFIED"
                    )
                )

                downloadManager.downloadAndVerifyModel(model) { update ->
                    run {
                        val current = modelDao.getModelById(model.id) ?: return@run
                        when (update) {
                            is DownloadStateUpdate.Progress -> {
                                if (current.downloadState == "DOWNLOADING") {
                                    modelDao.updateModel(
                                        current.copy(
                                            downloadProgress = update.progress,
                                            downloadSpeedMbps = update.speedMbps
                                        )
                                    )
                                }
                            }
                            is DownloadStateUpdate.VerifyingChecksum -> {
                                if (current.downloadState == "DOWNLOADING") {
                                    modelDao.updateModel(
                                        current.copy(
                                            downloadProgress = 0.99f,
                                            downloadSpeedMbps = 0f,
                                            checksumVerificationStatus = "VERIFYING"
                                        )
                                    )
                                }
                            }
                            is DownloadStateUpdate.Success -> {
                                com.example.engine.GgufParser().parseFromFile(update.file).getOrNull()?.let { ModelLibrary.recordMetadata(current.filename, it) }
                                modelDao.updateModel(
                                    current.copy(
                                        isInstalled = true,
                                        downloadState = "DOWNLOADED",
                                        downloadProgress = 1.0f,
                                        downloadSpeedMbps = 0f,
                                        localFilePath = update.file.absolutePath,
                                        sha256Checksum = update.sha256,
                                        isChecksumVerified = update.isVerified,
                                        checksumVerificationStatus = if (update.isVerified) "VERIFIED" else "UNAVAILABLE"
                                    )
                                )
                            }
                            is DownloadStateUpdate.Failure -> {
                                _downloadErrors.value = _downloadErrors.value + (model.id to update.error.take(300))
                                modelDao.updateModel(
                                    current.copy(
                                        downloadState = "ERROR",
                                        downloadSpeedMbps = 0f,
                                        checksumVerificationStatus = "FAILED"
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _downloadErrors.value = _downloadErrors.value + (model.id to (e.message ?: "Unable to download this model").take(300))
                val current = modelDao.getModelById(model.id)
                if (current != null) {
                    modelDao.updateModel(
                        current.copy(
                            downloadState = "ERROR",
                            downloadSpeedMbps = 0f,
                            checksumVerificationStatus = "FAILED"
                        )
                    )
                }
            } finally {
                activeDownloadJobs.remove(model.id, kotlinx.coroutines.currentCoroutineContext()[Job])
            }
        }
        if (activeDownloadJobs.putIfAbsent(model.id, job) == null) job.start() else job.cancel()
    }

    fun cancelModelDownload(modelId: Long, scope: CoroutineScope) {
        downloadManager.cancelDownload(modelId)
        val job = activeDownloadJobs[modelId]
        job?.cancel()
        repositoryScope.launch(Dispatchers.IO) {
            job?.join()
            val model = modelDao.getModelById(modelId) ?: return@launch
            val partFile = File(downloadManager.modelsDirectory, "${model.filename}.part")
            modelDao.updateModel(
                model.copy(
                    downloadState = if (partFile.length() > 0) "PAUSED" else "NOT_DOWNLOADED",
                    downloadProgress = (partFile.length().toFloat() / (model.fileSizeMb * 1048576).coerceAtLeast(1)).coerceIn(0f, .99f),
                    downloadSpeedMbps = 0f,
                    isInstalled = false,
                    checksumVerificationStatus = "NOT_VERIFIED"
                )
            )
        }
    }

    suspend fun verifyModelChecksum(model: ModelEntity): ChecksumVerificationResult = withContext(Dispatchers.IO) {
        modelLock.withLock {
            val current = modelDao.getModelById(model.id) ?: error("Model was deleted")
            check(current.isInstalled) { "Download or import this model first" }
            val file = File(current.localFilePath ?: File(downloadManager.modelsDirectory, current.filename).path)
            val result = downloadManager.verifyLocalFile(file, current.sha256Checksum)
            verifiedFiles.invalidate(file) // Explicit verification always replaces startup trust.
            modelDao.updateModel(current.copy(
                isChecksumVerified = result.isMatch,
                checksumVerificationStatus = result.status
            ))
            result
        }
    }

    suspend fun searchHuggingFaceHub(query: String): List<HuggingFaceModelSearchResult> {
        return downloadManager.searchHuggingFaceHub(query)
    }

    suspend fun fetchRepoGgufFiles(repoId: String): List<HuggingFaceGgufFile> {
        return downloadManager.fetchRepoGgufFiles(repoId)
    }

    suspend fun addHuggingFaceModel(repoId: String, file: HuggingFaceGgufFile): ModelEntity {
        val localName = com.example.engine.HuggingFaceCatalog.localFilename(repoId, file)
        modelDao.getModelByFilename(localName)?.let { return it }
        val cleanModelName = file.filename
            .removeSuffix(".gguf")
            .removeSuffix(".GGUF")
            .replace("-", " ")
            .replace("_", " ")

        val entity = ModelEntity(
            name = cleanModelName,
            filename = localName,
            architecture = if (file.filename.contains("qwen", ignoreCase = true)) "qwen2" else if (file.filename.contains("gemma", ignoreCase = true)) "gemma2" else "llama",
            quantization = file.quantization,
            parameterCount = "Custom (~${file.sizeMb}MB)",
            contextLength = 4096,
            fileSizeMb = file.sizeMb,
            ramRequiredMb = file.recommendedRamMb,
            isInstalled = false,
            isActive = false,
            isCustom = true,
            description = "Imported from Hugging Face repository '$repoId'. Quantization: ${file.quantization}.",
            capabilities = "Hugging Face, GGUF, On-Device LLM",
            downloadUrl = file.downloadUrl,
            downloadState = "NOT_DOWNLOADED",
            downloadProgress = 0.0f,
            versionTag = "hf-${file.quantization.lowercase()}",
            speedScoreTokSec = 0f,
            recommendedTier = if (file.sizeMb < 600) "ULTRA_LIGHT" else if (file.sizeMb < 1500) "BALANCED" else "PERFORMANCE",
            huggingFaceRepo = repoId,
            sha256Checksum = file.sha256Oid,
            isChecksumVerified = false,
            checksumVerificationStatus = "NOT_VERIFIED"
        )
        val id = modelDao.insertModel(entity)
        return entity.copy(id = id)
    }

    suspend fun uninstallModel(model: ModelEntity) = withContext(Dispatchers.IO) {
        modelLock.withLock {
            // Re-read inside the same lock used for activation: the UI's model may be stale.
            val current = modelDao.getModelById(model.id) ?: return@withLock
            check(current.downloadState != "DOWNLOADING" && !activeDownloadJobs.containsKey(current.id)) {
                "Cancel the download before deleting this model"
            }
            val directory = downloadManager.modelsDirectory.canonicalFile
            val modelFile = File(current.localFilePath ?: File(directory, current.filename).path).canonicalFile
            val partialFile = File(directory, "${current.filename}.part").canonicalFile
            require(modelFile.parentFile == directory && partialFile.parentFile == directory) {
                "Model file is outside the app's model storage"
            }
            if (current.isActive) {
                // The engine refuses to unload while chat or a connected app is generating.
                engine.unloadActiveModel()
                modelDao.clearActiveModel()
            }
            for (file in listOf(modelFile, partialFile)) {
                check(!file.exists() || (file.isFile && file.delete())) { "Could not delete ${file.name}. Try again." }
            }
            if (current.isCustom && current.downloadUrl.isNullOrBlank()) {
                // Imported source files are untouched; only the app's private copy is removed.
                modelDao.deleteModel(current.id)
            } else modelDao.updateModel(current.copy(
                isInstalled = false,
                isActive = false,
                downloadState = "NOT_DOWNLOADED",
                downloadProgress = 0f,
                downloadSpeedMbps = 0f,
                localFilePath = null,
                isChecksumVerified = false,
                checksumVerificationStatus = "NOT_VERIFIED"
            ))
        }
        analyzeHardware(appContext)
    }

    suspend fun updateModel(model: ModelEntity, scope: CoroutineScope) {
        check(!model.isActive) { "Select another model before replacing this model's file" }
        // Triggers a fresh download / update check
        startModelDownload(model, scope)
    }

    suspend fun registerImportedGguf(file: File, metadata: GgufMetadata): ModelEntity {
        ModelLibrary.recordMetadata(file.name, metadata)
        val entity = ModelEntity(
            name = metadata.modelName.ifBlank { file.nameWithoutExtension },
            filename = file.name,
            architecture = metadata.architecture,
            quantization = metadata.primaryQuantization.typeName,
            parameterCount = "${metadata.tensorCount} Tensors",
            contextLength = metadata.contextLength,
            fileSizeMb = (file.length() / (1024 * 1024)).coerceAtLeast(1L),
            ramRequiredMb = metadata.ramFootprintEstimateMb.toInt(),
            isInstalled = true,
            isActive = false,
            isCustom = true,
            description = "Custom imported GGUF v${metadata.version} with ${metadata.blockCount} layers and ${metadata.headCount} attention heads.",
            capabilities = "Custom GGUF, Imported Model, User Weights",
            localFilePath = file.absolutePath,
            downloadState = "DOWNLOADED",
            downloadProgress = 1.0f
        )
        val id = modelDao.insertModel(entity)
        return entity.copy(id = id)
    }

    suspend fun registerImportedLiteRt(file: File, displayName: String): ModelEntity {
        val maximumContext = com.google.ai.edge.litertlm.ModelInfo.from(file.absolutePath).use { info ->
            require(info is com.google.ai.edge.litertlm.ModelInfo.Llm) { "Import a text-generation .litertlm model" }
            require(com.google.ai.edge.litertlm.BackendType.CPU in info.supportedBackends(com.google.ai.edge.litertlm.Modality.TEXT)) {
                "This package has no compatible CPU fallback. Vendor-only NPU and GPU packages are not supported."
            }
            info.maxContextTokens().takeIf { it > 0 } ?: 2048
        }
        val checksum = downloadManager.verifyLocalFile(file, null).computedSha256
        val known = ModelLibrary.inferenceModel(appContext, file.name, checksum)
        val entity = (known ?: ModelEntity(name = displayName.take(120), filename = file.name, architecture = "litertlm-import",
            quantization = "Model-defined", parameterCount = "Custom", contextLength = maximumContext,
            fileSizeMb = (file.length() + 1048575) / 1048576,
            ramRequiredMb = ModelMemoryPlanner.estimate(file.length(), minOf(maximumContext, 2048), 524288).totalMb.toInt(),
            isInstalled = true, isActive = false, isCustom = true,
            description = "Imported LiteRT-LM package. Review its publisher's licence. Unknown architectures use a fresh executor per request for context isolation; loading may take longer.",
            capabilities = "Custom LiteRT-LM, Text generation", localFilePath = file.absolutePath,
            downloadState = "DOWNLOADED", downloadProgress = 1f, sha256Checksum = checksum,
            isChecksumVerified = false, checksumVerificationStatus = "LOCAL_HASH")).copy(
                name = displayName.take(120), filename = file.name, isInstalled = true, isActive = false, isCustom = true,
                localFilePath = file.absolutePath, downloadUrl = null, downloadState = "DOWNLOADED", downloadProgress = 1f,
                sha256Checksum = checksum, isChecksumVerified = known != null,
                checksumVerificationStatus = if (known != null) "VERIFIED" else "LOCAL_HASH")
        return entity.copy(id = modelDao.insertModel(entity))
    }

    suspend fun logIpcCall(log: IpcLogEntity) {
        // Never retain editor text or model output in cross-app diagnostics.
        ipcLogDao.insertLog(log.copy(promptPreview = "", responsePreview = ""))
        registerClient(log.callingPackage)
        clientPolicyDao.recordRequest(log.callingPackage, System.currentTimeMillis())
    }

    suspend fun registerClient(packageName: String) {
        if (packageName == appContext.packageName) return
        val label = runCatching {
            appContext.packageManager.getApplicationLabel(
                appContext.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        clientPolicyDao.insertPendingPolicy(ClientAppPolicyEntity(packageName, label))
    }

    suspend fun clientAccessStatus(packageName: String): String {
        if (packageName == appContext.packageName) return "READY"
        registerClient(packageName)
        val policy = clientPolicyDao.getPolicyForPackage(packageName)!!
        return when {
            policy.isBlocked -> "DENIED"
            !policy.isWhitelisted -> "APPROVAL_REQUIRED"
            !policy.allowInference -> "DENIED"
            else -> "READY"
        }
    }

    /**
     * Strict Security Authorization and Rate Limiting Check
     */
    suspend fun checkSecurityAndRateLimit(packageName: String, scope: String = "INFERENCE"): Pair<Boolean, String> {
        if (packageName == appContext.packageName) return true to "OK"
        registerClient(packageName)
        val policy = clientPolicyDao.getPolicyForPackage(packageName)
        if (policy == null) return Pair(false, "Approve this app in Better Intelligence > Connect first")
        if (policy != null) {
            if (policy.isBlocked) {
                return Pair(false, "Package [$packageName] is explicitly blocked by Better Intelligence security policy.")
            }
            if (!policy.isWhitelisted) {
                return Pair(false, "Package [$packageName] has not been granted IPC inference permissions.")
            }
            val allowed = when (scope) {
                "INFERENCE" -> policy.allowInference
                "STRUCTURED_JSON" -> policy.allowInference && policy.allowStructuredJson
                "READ_MEMORY" -> policy.allowReadMemory
                "WRITE_MEMORY" -> policy.allowWriteMemory
                else -> false
            }
            if (!allowed) return false to "Scope Denied: Access to $scope is disabled in Apps"

            // Rate limit check: sliding 60 second window
            val now = System.currentTimeMillis()
            val window = rateLimitWindows.getOrPut(packageName) { mutableListOf() }
            synchronized(window) {
                window.removeAll { now - it > 60_000 }
                if (window.size >= policy.rateLimitPerMin) {
                    return Pair(false, "Rate limit exceeded for [$packageName] (${policy.rateLimitPerMin} req/min). Try again later.")
                }
                window.add(now)
            }
        }
        return Pair(true, "OK")
    }

    suspend fun isPackageAllowed(packageName: String): Boolean {
        val policy = clientPolicyDao.getPolicyForPackage(packageName)
        return policy != null && policy.isWhitelisted && !policy.isBlocked
    }

    suspend fun updatePolicy(policy: ClientAppPolicyEntity) {
        clientPolicyDao.insertOrUpdatePolicy(policy)
    }

    suspend fun deletePolicy(pkg: String) {
        clientPolicyDao.deletePolicy(pkg)
    }

    suspend fun clearIpcLogs() {
        ipcLogDao.clearLogs()
    }

    suspend fun insertChatMessage(message: ChatMessageEntity): Long = database.withTransaction {
            check(conversationDao.get(message.conversationId) != null) { "Conversation is no longer available" }
            val id = chatDao.insertMessage(message)
            conversationDao.touch(message.conversationId,
                if (message.role == "user") message.content.replace('\n', ' ').take(70) else "New conversation",
                message.timestamp)
            id
    }
    suspend fun updateChatMessage(message: ChatMessageEntity) = chatDao.updateMessage(message)
    suspend fun recoverInterruptedMessages() = chatDao.recoverInterruptedMessages()

    suspend fun clearChatHistory() {
        database.withTransaction { chatDao.clearChat(); conversationDao.clear() }
    }

    suspend fun insertMemory(snippet: MemorySnippetEntity): Long {
        return memoryDao.insertMemory(snippet)
    }

    suspend fun updateMemory(snippet: MemorySnippetEntity) {
        memoryDao.updateMemory(snippet)
    }

    suspend fun deleteMemory(id: Long) {
        memoryDao.deleteMemory(id)
    }

    suspend fun clearAllMemories() {
        memoryDao.clearAllMemories()
    }

    suspend fun buildMemoryContextPrompt(): String {
        val memories = memoryDao.getActiveMemories().firstOrNull() ?: emptyList()
        if (memories.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n[ON-DEVICE USER CONTEXT & MEMORY (BETTER INTELLIGENCE)]\n")
        memories.forEach { mem ->
            sb.append("- [${mem.category.uppercase()}] ${mem.title}: ${mem.content}\n")
        }
        sb.append("[Use this persistent contextual awareness to give accurate, personalized, and relevant responses]\n\n")
        return sb.toString()
    }

    suspend fun buildClientSystemPrompt(packageName: String, requested: String?): String {
        val policy = clientPolicyDao.getPolicyForPackage(packageName)
        val system = listOfNotNull(
            policy?.customSystemPrompt?.take(4000)?.takeIf { it.isNotBlank() },
            requested?.take(4000)?.takeIf { it.isNotBlank() }
        ).joinToString("\n").ifBlank { "You are a helpful on-device assistant." } +
            (policy?.let { "\nClient: ${it.appName.take(200)}" } ?: "")
        // Sharing inference does not imply sharing personal memories.
        if (policy?.allowReadMemory != true || !policy.isWhitelisted || policy.isBlocked) return system
        val memories = permittedMemories(packageName).take(10)
        return system + memories.joinToString("\n", prefix = "\nUser context:\n") {
            "${it.title}: ${it.content.take(500)}"
        }
    }

    private suspend fun permittedMemories(packageName: String): List<MemorySnippetEntity> {
        val policy = clientPolicyDao.getPolicyForPackage(packageName) ?: return emptyList()
        if (!policy.isWhitelisted || policy.isBlocked || !policy.allowReadMemory) return emptyList()
        val categories = policy.allowedMemoryCategories.split(',').map { it.trim() }
        return memoryDao.getActiveMemories().first().filter { "*" in categories || it.category in categories }
    }

    suspend fun querySharedMemoriesAsJson(packageName: String, query: String, limit: Int): String {
        val items = permittedMemories(packageName).filter {
            it.title.contains(query.take(1000), true) || it.content.contains(query.take(1000), true)
        }.take(limit.coerceIn(1, 50))
        return org.json.JSONObject().put("memories", org.json.JSONArray().apply {
            items.forEach { put(org.json.JSONObject().put("id", it.id).put("title", it.title)
                .put("content", it.content).put("category", it.category)) }
        }).toString()
    }

    suspend fun writeSharedMemoryFromApp(packageName: String, title: String, content: String, category: String): Long {
        val policy = clientPolicyDao.getPolicyForPackage(packageName)
        if (packageName != appContext.packageName) {
            check(policy != null && policy.isWhitelisted && !policy.isBlocked && policy.allowWriteMemory)
            val categories = policy.allowedMemoryCategories.split(',').map { it.trim() }
            require("*" in categories || category in categories) { "Memory category is not allowed" }
        }
        require(content.isNotBlank() && content.length <= 8000)
        return memoryDao.insertMemory(MemorySnippetEntity(title = title.take(200), content = content,
            category = category.take(100), sourceApp = packageName))
    }

    suspend fun deleteModel(id: Long) {
        modelDao.deleteModel(id)
    }

    suspend fun logPerformance(log: HardwarePerformanceLogEntity): Long {
        return performanceLogDao.insertLog(log)
    }

    suspend fun deletePerformanceLog(id: Long) {
        performanceLogDao.deleteLog(id)
    }

    suspend fun clearPerformanceLogs() {
        performanceLogDao.clearAllLogs()
    }

    suspend fun runAndSaveBenchmarkForModel(
        model: ModelEntity,
        contextSize: Int = 2048
    ): HardwarePerformanceLogEntity {
        switchActiveModel(model)
        val benchmark = engine.runBenchmark(contextLength = contextSize)
        val profile = HardwareAnalyzer.analyze(appContext)
        val ramAvail = profile.availableRamMb
        val devModel = profile.deviceModel
        val abi = profile.cpuAbi
        val temp = profile.batteryTemperatureC
        val thermal = profile.thermalStatus
        val measuredTokSec = benchmark.generationTokSec
        val promptTokSec = benchmark.promptProcessingTokSec
        val ttft = benchmark.promptLatencyMs
        val peakRam = benchmark.ramUsageMb.toLong()
        val log = HardwarePerformanceLogEntity(
            modelName = model.name,
            filename = model.filename,
            architecture = model.architecture,
            quantization = model.quantization,
            parameterCount = model.parameterCount,
            tokensPerSecond = measuredTokSec,
            promptProcessingTokSec = promptTokSec,
            timeToFirstTokenMs = ttft,
            peakRamMb = peakRam,
            ramAvailableAtTestMb = ramAvail,
            batteryTemperatureC = temp,
            thermalState = thermal,
            threadCount = benchmark.recommendedThreads,
            gpuLayers = benchmark.recommendedGpuLayers,
            deviceModel = devModel,
            chipsetAbi = abi,
            testType = "BENCHMARK_RUN",
            efficiencyScore = if (peakRam > 0) (measuredTokSec / (peakRam / 1024f)) else measuredTokSec,
            timestamp = System.currentTimeMillis()
        )

        val id = performanceLogDao.insertLog(log)
        return log.copy(id = id)
    }

    companion object {
        @Volatile
        private var INSTANCE: AiCoreRepository? = null

        fun getInstance(context: Context): AiCoreRepository {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val db = AppDatabase.getDatabase(appContext)
                val engineManager = EngineManager(appContext)
                val prefsRepo = SettingsPreferencesRepository(appContext)
                val instance = AiCoreRepository(appContext, db, engineManager, prefsRepo)
                INSTANCE = instance
                instance
            }
        }
    }
}
