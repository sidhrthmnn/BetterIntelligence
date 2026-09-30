package com.example.data

import android.content.Context
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

    // Rate limiting tracker: package -> list of request timestamps in ms
    private val rateLimitWindows = ConcurrentHashMap<String, MutableList<Long>>()

    // Hardware Telemetry state
    private val _hardwareProfile = MutableStateFlow<HardwareProfile?>(null)
    val hardwareProfile: StateFlow<HardwareProfile?> = _hardwareProfile.asStateFlow()

    private val _modelRecommendation = MutableStateFlow<ModelRecommendation?>(null)
    val modelRecommendation: StateFlow<ModelRecommendation?> = _modelRecommendation.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val modelLock = Mutex()

    init {
        repositoryScope.launch {
            seedDefaultModelsIfEmpty()
            analyzeHardware(appContext)
            val previous = modelDao.getActiveModel().first()
            if (previous != null) {
                try { switchActiveModel(previous) }
                catch (e: Exception) { modelDao.clearActiveModel() }
            }
        }
    }

    fun analyzeHardware(context: Context) {
        val profile = HardwareAnalyzer.analyze(context)
        _hardwareProfile.value = profile
        repositoryScope.launch {
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
        val names = library.map { it.filename }.toSet()
        modelDao.getAllModels().first().filter { !it.isCustom && it.filename !in names }.forEach {
            modelDao.deleteModel(it.id)
        }
        library.forEach { entry ->
            val existing = modelDao.getModelByFilename(entry.filename)
            val file = File(downloadManager.modelsDirectory, entry.filename)
            val installed = file.isFile && downloadManager.verifyLocalFile(file, entry.sha256Checksum).isMatch
            val updated = entry.copy(id = existing?.id ?: 0L, isInstalled = installed,
                isActive = installed && existing?.isActive == true,
                localFilePath = if (installed) file.absolutePath else null,
                downloadState = if (installed) "DOWNLOADED" else "NOT_DOWNLOADED",
                downloadProgress = if (installed) 1f else 0f,
                isChecksumVerified = installed,
                checksumVerificationStatus = if (installed) "VERIFIED" else "NOT_VERIFIED")
            modelDao.insertModel(updated)
        }
    }

    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        modelLock.withLock { engine.unloadActiveModel(); modelDao.clearActiveModel() }
    }

    suspend fun switchActiveModel(model: ModelEntity) = withContext(Dispatchers.IO) {
        modelLock.withLock {
            check(model.isInstalled) { "Download this model first" }
            require(model.filename.endsWith(".gguf", true)) { "Select a GGUF instruction model" }
            val file = File(model.localFilePath ?: File(downloadManager.modelsDirectory, model.filename).absolutePath)
            check(file.isFile) { "Model file is missing; download it again" }
            if (model.architecture.startsWith("gemma")) {
                check(appContext.getSharedPreferences("model_terms", Context.MODE_PRIVATE).getBoolean("gemma", false)) {
                    "Accept the Gemma terms in Models first"
                }
            }
            val profile = HardwareAnalyzer.analyze(appContext)
            check(ModelLibrary.fits(model, profile)) { "Insufficient available memory or unsupported processor" }
            engineManager.selectEngine(SupportedEngine.LLAMA_CPP)
            val result = engine.loadCustomGguf(file)
            if (result.isFailure) { modelDao.clearActiveModel(); result.getOrThrow() }
            modelDao.clearActiveModel()
            modelDao.setActiveModel(model.id)
        }
    }

    fun startModelDownload(model: ModelEntity, scope: CoroutineScope) {
        if (activeDownloadJobs.containsKey(model.id)) return

        val job = repositoryScope.launch(Dispatchers.IO) {
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
                activeDownloadJobs.remove(model.id)
            }
        }
        activeDownloadJobs[model.id] = job
    }

    fun cancelModelDownload(modelId: Long, scope: CoroutineScope) {
        downloadManager.cancelDownload(modelId)
        val job = activeDownloadJobs.remove(modelId)
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            val model = modelDao.getModelById(modelId) ?: return@launch
            val partFile = File(downloadManager.modelsDirectory, "${model.filename}.part")
            if (partFile.exists()) partFile.delete()
            modelDao.updateModel(
                model.copy(
                    downloadState = "NOT_DOWNLOADED",
                    downloadProgress = 0.0f,
                    downloadSpeedMbps = 0f,
                    isInstalled = false,
                    checksumVerificationStatus = "NOT_VERIFIED"
                )
            )
        }
    }

    suspend fun verifyModelChecksum(model: ModelEntity): ChecksumVerificationResult {
        val file = if (!model.localFilePath.isNullOrBlank()) {
            File(model.localFilePath)
        } else {
            File(downloadManager.modelsDirectory, model.filename)
        }

        val result = downloadManager.verifyLocalFile(file, model.sha256Checksum)
        val computed = result.computedSha256
        val updatedModel = model.copy(
            sha256Checksum = model.sha256Checksum,
            isChecksumVerified = result.isMatch,
            checksumVerificationStatus = result.status
        )
        modelDao.updateModel(updatedModel)
        return result
    }

    suspend fun searchHuggingFaceHub(query: String): List<HuggingFaceModelSearchResult> {
        return downloadManager.searchHuggingFaceHub(query)
    }

    suspend fun fetchRepoGgufFiles(repoId: String): List<HuggingFaceGgufFile> {
        return downloadManager.fetchRepoGgufFiles(repoId)
    }

    suspend fun addHuggingFaceModel(repoId: String, file: HuggingFaceGgufFile): ModelEntity {
        val cleanModelName = file.filename
            .removeSuffix(".gguf")
            .removeSuffix(".GGUF")
            .replace("-", " ")
            .replace("_", " ")

        val entity = ModelEntity(
            name = cleanModelName,
            filename = file.filename.substringAfterLast('/'),
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
            speedScoreTokSec = file.estimatedSpeedTokSec,
            recommendedTier = if (file.sizeMb < 600) "ULTRA_LIGHT" else if (file.sizeMb < 1500) "BALANCED" else "PERFORMANCE",
            huggingFaceRepo = repoId,
            sha256Checksum = file.sha256Oid,
            isChecksumVerified = false,
            checksumVerificationStatus = "NOT_VERIFIED"
        )
        val id = modelDao.insertModel(entity)
        return entity.copy(id = id)
    }

    suspend fun uninstallModel(model: ModelEntity) {
        val wasActive = model.isActive
        val modelFile = File(model.localFilePath ?: File(downloadManager.modelsDirectory, model.filename).absolutePath)
        if (wasActive) { engine.unloadActiveModel(); modelDao.clearActiveModel() }
        if (modelFile.exists()) {
            modelFile.delete()
        }
        modelDao.updateModel(
            model.copy(
                isInstalled = false,
                isActive = false,
                downloadState = "NOT_DOWNLOADED",
                downloadProgress = 0f,
                downloadSpeedMbps = 0f,
                localFilePath = null
            )
        )

        // If this model was active in memory, switch to first available installed model
        if (wasActive) {
            val models = modelDao.getAllModels().firstOrNull() ?: emptyList()
            val fallback = models.firstOrNull { it.id != model.id && (it.isInstalled || it.downloadState == "DOWNLOADED") }
            if (fallback != null) {
                switchActiveModel(fallback)
            }
        }
    }

    suspend fun updateModel(model: ModelEntity, scope: CoroutineScope) {
        // Triggers a fresh download / update check
        startModelDownload(model, scope)
    }

    suspend fun registerImportedGguf(file: File, metadata: GgufMetadata): ModelEntity {
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

    suspend fun logIpcCall(log: IpcLogEntity) {
        ipcLogDao.insertLog(log)
        val existing = clientPolicyDao.getPolicyForPackage(log.callingPackage)
        if (existing != null) {
            clientPolicyDao.insertOrUpdatePolicy(
                existing.copy(
                    totalRequests = existing.totalRequests + 1,
                    lastAccessTimestamp = System.currentTimeMillis()
                )
            )
        } else {
            clientPolicyDao.insertOrUpdatePolicy(
                ClientAppPolicyEntity(
                    packageName = log.callingPackage,
                    appName = log.callingPackage.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                    isWhitelisted = false,
                    autoApprove = false,
                    totalRequests = 1,
                    lastAccessTimestamp = System.currentTimeMillis(),
                    rateLimitPerMin = 60
                )
            )
        }
    }

    /**
     * Strict Security Authorization and Rate Limiting Check
     */
    suspend fun checkSecurityAndRateLimit(packageName: String): Pair<Boolean, String> {
        val policy = clientPolicyDao.getPolicyForPackage(packageName)
        if (policy == null) return Pair(false, "Approve this app in Better Intelligence > Connect first")
        if (policy != null) {
            if (policy.isBlocked) {
                return Pair(false, "Package [$packageName] is explicitly blocked by Better Intelligence security policy.")
            }
            if (!policy.isWhitelisted) {
                return Pair(false, "Package [$packageName] has not been granted IPC inference permissions.")
            }

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

    suspend fun insertChatMessage(message: ChatMessageEntity) {
        chatDao.insertMessage(message)
    }

    suspend fun clearChatHistory() {
        chatDao.clearChat()
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
        threads: Int = 4,
        gpuLayers: Int = 16,
        context: Context? = null
    ): HardwarePerformanceLogEntity {
        switchActiveModel(model)
        val benchmark = engine.runBenchmark()
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
            threadCount = threads,
            gpuLayers = gpuLayers,
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
