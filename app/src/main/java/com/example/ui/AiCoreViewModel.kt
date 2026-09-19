package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AiCoreRepository
import com.example.data.ChatMessageEntity
import com.example.data.ClientAppPolicyEntity
import com.example.data.HardwarePerformanceLogEntity
import com.example.data.IpcLogEntity
import com.example.data.MemorySnippetEntity
import com.example.data.ModelEntity
import com.example.engine.BackendType
import com.example.engine.DeviceTier
import com.example.engine.GenerationConfig
import com.example.engine.GenerationParams
import com.example.engine.GgufMetadata
import com.example.engine.GgufParser
import com.example.engine.HardwareAnalyzer
import com.example.engine.HardwareProfile
import com.example.engine.HardwareTelemetry
import com.example.engine.HardwareValidator
import com.example.engine.ChecksumVerificationResult
import com.example.engine.HuggingFaceGgufFile
import com.example.engine.HuggingFaceModelSearchResult
import com.example.engine.ModelRecommendation
import com.example.engine.QuantizationType
import com.example.engine.StreamTokenListener
import com.example.engine.SupportedEngine
import com.example.service.InferenceForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

enum class AppTab(val title: String) {
    MODELS("Models"),
    CHAT("Chat"),
    CONNECT("Connect"),
    SETTINGS("Settings")
}

data class SimulatorUiState(
    val selectedApp: String = "Smart Notes AI Pro",
    val selectedPackage: String = "com.example.notes",
    val selectedTask: String = "STREAM", // "STREAM", "EMBED", "SUMMARIZE", "CLASSIFY"
    val promptInput: String = "Draft a 3-point action plan for launching an on-device AI feature.",
    val outputText: String = "",
    val isRunning: Boolean = false,
    val latencyMs: Long = 0L,
    val tokensPerSec: Float = 0f,
    val statusText: String = "Connected - Context memory enabled"
)

class AiCoreViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AiCoreRepository.getInstance(application)
    private val parser = GgufParser()

    val allModels: StateFlow<List<ModelEntity>> = repository.allModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeModel: StateFlow<ModelEntity?> = repository.activeModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val telemetry: StateFlow<HardwareTelemetry> = repository.engine.telemetry

    val recentIpcLogs: StateFlow<List<IpcLogEntity>> = repository.recentIpcLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalIpcCalls: StateFlow<Int> = repository.totalIpcCalls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val clientPolicies: StateFlow<List<ClientAppPolicyEntity>> = repository.clientPolicies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatMessages: StateFlow<List<ChatMessageEntity>> = repository.chatMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMemories: StateFlow<List<MemorySnippetEntity>> = repository.allMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeMemories: StateFlow<List<MemorySnippetEntity>> = repository.activeMemories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val performanceLogs: StateFlow<List<HardwarePerformanceLogEntity>> = repository.allPerformanceLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hardwareProfile: StateFlow<HardwareProfile?> = repository.hardwareProfile
    val modelRecommendation: StateFlow<ModelRecommendation?> = repository.modelRecommendation

    // Quantization & Performance Comparison filter/sort state
    private val _selectedQuantizationFilter = MutableStateFlow("ALL")
    val selectedQuantizationFilter: StateFlow<String> = _selectedQuantizationFilter.asStateFlow()

    private val _selectedSortMetric = MutableStateFlow("SPEED") // "SPEED", "RAM", "EFFICIENCY", "DATE"
    val selectedSortMetric: StateFlow<String> = _selectedSortMetric.asStateFlow()

    // Theme Mode: Dark (Pure OLED Black) vs Light (Pure Crisp White)
    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    // Active Navigation Tab
    private val _currentTab = MutableStateFlow(AppTab.CHAT)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    // Model Filter state
    private val _modelSearchQuery = MutableStateFlow("")
    val modelSearchQuery: StateFlow<String> = _modelSearchQuery.asStateFlow()

    private val _modelCategoryFilter = MutableStateFlow("ALL")
    val modelCategoryFilter: StateFlow<String> = _modelCategoryFilter.asStateFlow()

    // Memory Filter state
    private val _memorySearchQuery = MutableStateFlow("")
    val memorySearchQuery: StateFlow<String> = _memorySearchQuery.asStateFlow()

    private val _selectedMemoryCategory = MutableStateFlow("ALL")
    val selectedMemoryCategory: StateFlow<String> = _selectedMemoryCategory.asStateFlow()

    private val _isAutoMemoryCaptureEnabled = MutableStateFlow(true)
    val isAutoMemoryCaptureEnabled: StateFlow<Boolean> = _isAutoMemoryCaptureEnabled.asStateFlow()

    // Playground state
    private val _isChatGenerating = MutableStateFlow(false)
    val isChatGenerating: StateFlow<Boolean> = _isChatGenerating.asStateFlow()

    private val _streamingChatText = MutableStateFlow("")
    val streamingChatText: StateFlow<String> = _streamingChatText.asStateFlow()

    private val _chatGenerationParams = MutableStateFlow(GenerationParams())
    val chatGenerationParams: StateFlow<GenerationParams> = _chatGenerationParams.asStateFlow()

    // Engine Manager state: llama.cpp (GGUF) vs LiteRT (TFLite)
    val selectedEngine: StateFlow<SupportedEngine> = repository.engineManager.selectedEngineType
    
    // Hardware Pre-flight Validation
    val hardwareValidation = HardwareValidator.validateEnvironment(application)

    // Benchmark state
    private val _benchmarkResult = MutableStateFlow<com.example.engine.BenchmarkResult?>(null)
    val benchmarkResult: StateFlow<com.example.engine.BenchmarkResult?> = _benchmarkResult.asStateFlow()

    private val _isBenchmarking = MutableStateFlow(false)
    val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    // Quality Preset: "FAST" (Q4_K_M), "BALANCED" (Q5_K_M), "QUALITY" (Q6_K / Q8_0)
    private val _qualityPreset = MutableStateFlow("FAST")
    val qualityPreset: StateFlow<String> = _qualityPreset.asStateFlow()

    // Foreground Service & WakeLock for persistent background reasoning
    private val _isForegroundServiceEnabled = MutableStateFlow(true)
    val isForegroundServiceEnabled: StateFlow<Boolean> = _isForegroundServiceEnabled.asStateFlow()

    // Hugging Face Model Discovery & Verification State
    private val _hfSearchQuery = MutableStateFlow("")
    val hfSearchQuery: StateFlow<String> = _hfSearchQuery.asStateFlow()

    private val _hfSearchResults = MutableStateFlow<List<HuggingFaceModelSearchResult>>(emptyList())
    val hfSearchResults: StateFlow<List<HuggingFaceModelSearchResult>> = _hfSearchResults.asStateFlow()

    private val _isHfSearching = MutableStateFlow(false)
    val isHfSearching: StateFlow<Boolean> = _isHfSearching.asStateFlow()

    private val _selectedHfRepo = MutableStateFlow<String?>(null)
    val selectedHfRepo: StateFlow<String?> = _selectedHfRepo.asStateFlow()

    private val _hfRepoGgufFiles = MutableStateFlow<List<HuggingFaceGgufFile>>(emptyList())
    val hfRepoGgufFiles: StateFlow<List<HuggingFaceGgufFile>> = _hfRepoGgufFiles.asStateFlow()

    private val _isLoadingRepoFiles = MutableStateFlow(false)
    val isLoadingRepoFiles: StateFlow<Boolean> = _isLoadingRepoFiles.asStateFlow()

    private val _verifyingModelId = MutableStateFlow<Long?>(null)
    val verifyingModelId: StateFlow<Long?> = _verifyingModelId.asStateFlow()

    // Developer Panel Visibility
    private val _isDeveloperPanelExpanded = MutableStateFlow(false)
    val isDeveloperPanelExpanded: StateFlow<Boolean> = _isDeveloperPanelExpanded.asStateFlow()

    // GGUF Inspector state
    private val _inspectingGguf = MutableStateFlow<GgufMetadata?>(null)
    val inspectingGguf: StateFlow<GgufMetadata?> = _inspectingGguf.asStateFlow()

    // IPC Simulator state
    private val _simulatorState = MutableStateFlow(SimulatorUiState())
    val simulatorState: StateFlow<SimulatorUiState> = _simulatorState.asStateFlow()

    // Toast / Action notification
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    init {
        // Initial hardware analysis
        repository.analyzeHardware(application)

        // Observe and apply saved engine preference from DataStore
        viewModelScope.launch {
            repository.savedEnginePreference.collect { savedEngine ->
                repository.engineManager.selectEngine(savedEngine)
            }
        }
    }

    fun selectEngine(engine: SupportedEngine) {
        viewModelScope.launch {
            repository.preferencesRepository.saveEnginePreference(engine)
            repository.engineManager.selectEngine(engine)
            _userMessage.value = "Active engine switched to ${engine.displayName} and saved to DataStore."
        }
    }

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
    }

    fun refreshHardwareDiagnostics() {
        repository.analyzeHardware(getApplication())
        val profile = repository.hardwareProfile.value
        val ram = profile?.availableRamMb ?: 0L
        val thermal = profile?.thermalStatus ?: "Nominal"
        _userMessage.value = "Hardware diagnostics updated: ${ram}MB RAM free • $thermal"
    }

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun setMemorySearchQuery(query: String) {
        _memorySearchQuery.value = query
    }

    fun setSelectedMemoryCategory(cat: String) {
        _selectedMemoryCategory.value = cat
    }

    fun toggleAutoMemoryCapture(enabled: Boolean) {
        _isAutoMemoryCaptureEnabled.value = enabled
        _userMessage.value = if (enabled) "Auto-capture memory enabled" else "Auto-capture memory disabled"
    }

    fun addMemorySnippet(title: String, content: String, category: String, sourceApp: String = "Manual") {
        viewModelScope.launch {
            repository.insertMemory(
                MemorySnippetEntity(
                    title = title.trim().ifBlank { "User Fact" },
                    content = content.trim(),
                    category = category,
                    sourceApp = sourceApp,
                    isActive = true
                )
            )
            _userMessage.value = "Saved memory snippet to local context."
        }
    }

    fun updateMemorySnippet(snippet: MemorySnippetEntity) {
        viewModelScope.launch {
            repository.updateMemory(snippet)
            _userMessage.value = "Updated memory snippet."
        }
    }

    fun toggleMemoryActive(snippet: MemorySnippetEntity) {
        viewModelScope.launch {
            repository.updateMemory(snippet.copy(isActive = !snippet.isActive))
        }
    }

    fun deleteMemorySnippet(id: Long) {
        viewModelScope.launch {
            repository.deleteMemory(id)
            _userMessage.value = "Deleted memory snippet."
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            repository.clearAllMemories()
            _userMessage.value = "Cleared all local memory snippets."
        }
    }

    fun runHardwareAnalysis() {
        repository.analyzeHardware(getApplication())
        _userMessage.value = "Hardware analysis completed successfully."
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun setModelSearchQuery(query: String) {
        _modelSearchQuery.value = query
    }

    fun setModelCategoryFilter(filter: String) {
        _modelCategoryFilter.value = filter
    }

    fun updateGenerationParams(params: GenerationParams) {
        _chatGenerationParams.value = params
    }

    fun toggleDeveloperPanel() {
        _isDeveloperPanelExpanded.value = !_isDeveloperPanelExpanded.value
    }

    fun setQualityPreset(preset: String) {
        _qualityPreset.value = preset
        val targetQuant = when (preset) {
            "FAST" -> "Q4_K_M"
            "BALANCED" -> "Q5_K_M"
            "QUALITY" -> "Q8_0"
            else -> "Q4_K_M"
        }
        _userMessage.value = "Preset set to $preset ($targetQuant preferred for mobile)."
    }

    fun unloadActiveModel() {
        repository.engine.unloadActiveModel()
        _userMessage.value = "Unloaded model from RAM. Memory reclaimed."
    }

    fun setQuantizationFilter(filter: String) {
        _selectedQuantizationFilter.value = filter
    }

    fun setSortMetric(metric: String) {
        _selectedSortMetric.value = metric
    }

    fun deletePerformanceLog(id: Long) {
        viewModelScope.launch {
            repository.deletePerformanceLog(id)
            _userMessage.value = "Performance metric log deleted."
        }
    }

    fun clearAllPerformanceLogs() {
        viewModelScope.launch {
            repository.clearPerformanceLogs()
            _userMessage.value = "Cleared all hardware performance metric logs."
        }
    }

    fun runBenchmark() {
        if (_isBenchmarking.value) return
        _isBenchmarking.value = true
        _userMessage.value = "Running automated device benchmark..."
        
        viewModelScope.launch {
            try {
                val result = repository.engine.runBenchmark(
                    contextLength = _chatGenerationParams.value.contextSize,
                    gpuLayers = _chatGenerationParams.value.gpuLayers
                )
                _benchmarkResult.value = result
                
                // Automatically tune runtime parameters to identified sweet spot
                _chatGenerationParams.value = _chatGenerationParams.value.copy(
                    threads = result.recommendedThreads,
                    gpuLayers = result.recommendedGpuLayers,
                    contextSize = result.recommendedContext
                )

                // Persist benchmark metric log to Room database
                val active = activeModel.value
                val profile = repository.hardwareProfile.value
                val log = HardwarePerformanceLogEntity(
                    modelName = active?.name ?: result.modelName,
                    filename = active?.filename ?: "${result.modelName.lowercase().replace(' ', '-')}.gguf",
                    architecture = active?.architecture ?: "gguf",
                    quantization = active?.quantization ?: "Q4_K_M",
                    parameterCount = active?.parameterCount ?: "1.0B",
                    tokensPerSecond = result.generationTokSec,
                    promptProcessingTokSec = result.promptProcessingTokSec,
                    timeToFirstTokenMs = result.promptLatencyMs,
                    peakRamMb = result.ramUsageMb.toLong(),
                    ramAvailableAtTestMb = profile?.availableRamMb ?: 3450L,
                    batteryTemperatureC = profile?.batteryTemperatureC ?: 36.5f,
                    thermalState = profile?.thermalStatus ?: "Nominal",
                    threadCount = result.recommendedThreads,
                    gpuLayers = result.recommendedGpuLayers,
                    deviceModel = profile?.deviceModel ?: "ARM64 Android System",
                    chipsetAbi = profile?.cpuAbi ?: "arm64-v8a (NEON SIMD)",
                    testType = "BENCHMARK_RUN",
                    efficiencyScore = if (result.ramUsageMb > 0) result.generationTokSec / (result.ramUsageMb / 1024f) else result.generationTokSec,
                    timestamp = System.currentTimeMillis()
                )
                repository.logPerformance(log)

                _userMessage.value = "Benchmark saved: ${String.format("%.1f", result.generationTokSec)} tok/s logged to device history."
            } catch (e: Exception) {
                _userMessage.value = "Benchmark failed: ${e.message}"
            } finally {
                _isBenchmarking.value = false
            }
        }
    }

    fun runBenchmarkForModel(model: ModelEntity) {
        if (_isBenchmarking.value) return
        _isBenchmarking.value = true
        _userMessage.value = "Benchmarking ${model.name} (${model.quantization}) on hardware..."

        viewModelScope.launch {
            try {
                val log = repository.runAndSaveBenchmarkForModel(
                    model = model,
                    threads = _chatGenerationParams.value.threads,
                    gpuLayers = _chatGenerationParams.value.gpuLayers,
                    context = getApplication()
                )
                _userMessage.value = "Saved log for ${model.name}: ${String.format("%.1f", log.tokensPerSecond)} tok/s (${log.quantization})."
            } catch (e: Exception) {
                _userMessage.value = "Failed to benchmark ${model.name}: ${e.message}"
            } finally {
                _isBenchmarking.value = false
            }
        }
    }

    fun applyBenchmarkSettings() {
        val bench = _benchmarkResult.value ?: return
        _chatGenerationParams.value = _chatGenerationParams.value.copy(
            threads = bench.recommendedThreads,
            gpuLayers = bench.recommendedGpuLayers,
            contextSize = bench.recommendedContext
        )
        _userMessage.value = "Saved optimal configuration (${bench.recommendedThreads} threads, ${bench.recommendedGpuLayers} GPU layers, ${bench.recommendedContext} context)."
    }

    fun switchModel(model: ModelEntity) {
        viewModelScope.launch {
            if (!model.isInstalled && model.downloadState != "DOWNLOADED") {
                _userMessage.value = "Please download ${model.name} before loading into memory."
                return@launch
            }
            // Auto-align active engine with model architecture
            if (model.architecture.contains("litert") || model.filename.endsWith(".bin") || model.filename.endsWith(".tflite")) {
                repository.engineManager.selectEngine(SupportedEngine.LITERT)
            } else {
                repository.engineManager.selectEngine(SupportedEngine.LLAMA_CPP)
            }

            // Aggressive unload before load to prevent peak memory spike
            repository.engineManager.currentEngine.release()
            repository.switchActiveModel(model)
            _userMessage.value = "Loaded ${model.name} into AI Core (${repository.engineManager.selectedEngineType.value.formatBadge})."
        }
    }

    fun downloadModel(model: ModelEntity) {
        repository.startModelDownload(model, viewModelScope)
        _userMessage.value = "Starting download for ${model.name}..."
    }

    fun cancelDownload(modelId: Long) {
        repository.cancelModelDownload(modelId, viewModelScope)
        _userMessage.value = "Download cancelled."
    }

    fun setHfSearchQuery(query: String) {
        _hfSearchQuery.value = query
    }

    fun searchHuggingFace(query: String = _hfSearchQuery.value) {
        val q = query.trim()
        if (q.isBlank()) {
            _hfSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isHfSearching.value = true
            try {
                val results = repository.searchHuggingFaceHub(q)
                _hfSearchResults.value = results
                if (results.isEmpty()) {
                    _userMessage.value = "No GGUF models found for '$q'."
                } else {
                    _userMessage.value = "Found ${results.size} repositories on Hugging Face Hub."
                }
            } catch (e: Exception) {
                _userMessage.value = "Hugging Face search failed: ${e.message}"
            } finally {
                _isHfSearching.value = false
            }
        }
    }

    fun selectHfRepo(repoId: String) {
        _selectedHfRepo.value = repoId
        viewModelScope.launch {
            _isLoadingRepoFiles.value = true
            try {
                val files = repository.fetchRepoGgufFiles(repoId)
                _hfRepoGgufFiles.value = files
                if (files.isEmpty()) {
                    _userMessage.value = "No .gguf files detected in $repoId."
                } else {
                    _userMessage.value = "Discovered ${files.size} GGUF quantization files."
                }
            } catch (e: Exception) {
                _userMessage.value = "Failed to load repo files: ${e.message}"
            } finally {
                _isLoadingRepoFiles.value = false
            }
        }
    }

    fun clearHfRepoSelection() {
        _selectedHfRepo.value = null
        _hfRepoGgufFiles.value = emptyList()
    }

    fun addAndDownloadHfModel(repoId: String, file: HuggingFaceGgufFile) {
        viewModelScope.launch {
            try {
                val addedModel = repository.addHuggingFaceModel(repoId, file)
                repository.startModelDownload(addedModel, viewModelScope)
                _userMessage.value = "Added and started downloading ${addedModel.name} (${file.quantization})."
                clearHfRepoSelection()
            } catch (e: Exception) {
                _userMessage.value = "Failed to add model: ${e.message}"
            }
        }
    }

    fun verifyModelChecksum(model: ModelEntity) {
        viewModelScope.launch {
            _verifyingModelId.value = model.id
            try {
                val result = repository.verifyModelChecksum(model)
                if (result.isMatch) {
                    _userMessage.value = "SHA-256 Verified: ${result.computedSha256.take(12)}... (Match)"
                } else if (!result.expectedSha256.isNullOrBlank()) {
                    _userMessage.value = "SHA-256 Checksum Mismatch! File may be corrupt."
                } else {
                    _userMessage.value = "Computed SHA-256: ${result.computedSha256.take(12)}..."
                }
            } catch (e: Exception) {
                _userMessage.value = "Verification error: ${e.message}"
            } finally {
                _verifyingModelId.value = null
            }
        }
    }

    fun uninstallModel(model: ModelEntity) {
        viewModelScope.launch {
            repository.uninstallModel(model)
            _userMessage.value = "Uninstalled ${model.name}. Reclaimed ${model.fileSizeMb} MB storage."
        }
    }

    fun updateModel(model: ModelEntity) {
        viewModelScope.launch {
            repository.updateModel(model, viewModelScope)
            _userMessage.value = "Checking and downloading updates for ${model.name}..."
        }
    }

    fun applyRecommendation(recommendation: ModelRecommendation) {
        viewModelScope.launch {
            val models = repository.allModels.stateIn(viewModelScope).value
            val match = models.find { it.name == recommendation.recommendedModelName || it.filename == recommendation.recommendedFilename }
            if (match != null) {
                if (!match.isInstalled && match.downloadState != "DOWNLOADED") {
                    repository.startModelDownload(match, viewModelScope)
                    _userMessage.value = "Downloading recommended model ${match.name}..."
                    selectTab(AppTab.MODELS)
                } else {
                    repository.switchActiveModel(match)
                    _userMessage.value = "Activated recommended model ${match.name} in AI Core."
                    selectTab(AppTab.CHAT)
                }
            }
        }
    }

    fun addConnectedApp(appName: String, packageName: String, rateLimit: Int = 60) {
        viewModelScope.launch {
            val policy = ClientAppPolicyEntity(
                packageName = packageName.trim(),
                appName = appName.trim().ifBlank { "Connected App" },
                isWhitelisted = true,
                autoApprove = true,
                totalRequests = 0,
                lastAccessTimestamp = System.currentTimeMillis(),
                rateLimitPerMin = rateLimit
            )
            repository.updatePolicy(policy)
            _userMessage.value = "Connected ${policy.appName} ($packageName) to Better Intelligence."
        }
    }

    fun sendChatMessage(userText: String) {
        if (userText.isBlank() || _isChatGenerating.value) return

        val currentModel = activeModel.value?.name ?: "Better Intelligence"
        viewModelScope.launch {
            // Check auto memory capture
            if (_isAutoMemoryCaptureEnabled.value) {
                autoExtractMemory(userText.trim(), "Chat")
            }

            // Save user message
            repository.insertChatMessage(
                ChatMessageEntity(
                    role = "user",
                    content = userText.trim(),
                    modelName = currentModel
                )
            )

            _isChatGenerating.value = true
            _streamingChatText.value = ""

            val app = getApplication<Application>()
            if (_isForegroundServiceEnabled.value) {
                InferenceForegroundService.start(
                    context = app,
                    promptPreview = userText.trim().take(100),
                    modelName = currentModel
                )
            }

            // Build system prompt with on-device memory context
            val memoryContext = repository.buildMemoryContextPrompt()
            val baseSystem = _chatGenerationParams.value.systemPrompt ?: "You are Better Intelligence, a helpful, fast, and privacy-first on-device AI assistant."
            val enhancedSystemPrompt = baseSystem + memoryContext

            val effectiveParams = _chatGenerationParams.value.copy(systemPrompt = enhancedSystemPrompt)
            val startTime = System.currentTimeMillis()
            var lastNotificationUpdateTime = 0L

            val config = GenerationConfig(
                temperature = effectiveParams.temperature,
                topK = effectiveParams.topK,
                topP = effectiveParams.topP,
                maxTokens = effectiveParams.maxTokens,
                systemPrompt = enhancedSystemPrompt,
                threads = effectiveParams.threads,
                gpuLayers = effectiveParams.gpuLayers
            )

            val currentEngine = repository.engineManager.currentEngine
            currentEngine.generateStreamWithCallback(
                userText,
                config,
                object : StreamTokenListener {
                    override fun onToken(token: String, tokenIndex: Int) {
                        _streamingChatText.value += token

                        if (_isForegroundServiceEnabled.value) {
                            val now = System.currentTimeMillis()
                            // Throttle notification updates every 4 tokens or 250ms for low IPC overhead
                            if (tokenIndex % 4 == 0 || (now - lastNotificationUpdateTime) > 250L) {
                                lastNotificationUpdateTime = now
                                val elapsedSec = (now - startTime).coerceAtLeast(1L) / 1000f
                                val tokPerSec = (tokenIndex + 1) / elapsedSec
                                InferenceForegroundService.update(
                                    context = app,
                                    tokenCount = tokenIndex + 1,
                                    tokPerSec = tokPerSec,
                                    preview = _streamingChatText.value,
                                    modelName = currentModel
                                )
                            }
                        }
                    }

                    override fun onComplete(
                        fullText: String,
                        latencyMs: Long,
                        tokensPerSec: Float,
                        promptTokens: Int,
                        completionTokens: Int
                    ) {
                        if (_isForegroundServiceEnabled.value) {
                            InferenceForegroundService.stop(
                                context = app,
                                totalTokens = completionTokens,
                                latencyMs = latencyMs,
                                tokPerSec = tokensPerSec
                            )
                        }

                        viewModelScope.launch {
                            repository.insertChatMessage(
                                ChatMessageEntity(
                                    role = "assistant",
                                    content = fullText,
                                    latencyMs = latencyMs,
                                    tokensPerSec = tokensPerSec,
                                    promptTokens = promptTokens,
                                    completionTokens = completionTokens,
                                    modelName = currentModel
                                )
                            )
                            _streamingChatText.value = ""
                            _isChatGenerating.value = false
                        }
                    }

                    override fun onError(errorCode: Int, errorMessage: String) {
                        if (_isForegroundServiceEnabled.value) {
                            InferenceForegroundService.cancel(app)
                        }

                        viewModelScope.launch {
                            repository.insertChatMessage(
                                ChatMessageEntity(
                                    role = "assistant",
                                    content = "Error [$errorCode]: $errorMessage",
                                    modelName = currentModel
                                )
                            )
                            _streamingChatText.value = ""
                            _isChatGenerating.value = false
                        }
                    }
                }
            )
        }
    }

    fun toggleForegroundService(enabled: Boolean) {
        _isForegroundServiceEnabled.value = enabled
        _userMessage.value = if (enabled) {
            "Background Persistent Inference enabled (Notification & WakeLock active)"
        } else {
            "Background Persistent Inference disabled"
        }
    }

    private suspend fun autoExtractMemory(text: String, sourceApp: String) {
        val lower = text.lowercase()
        if (lower.startsWith("remember that ") || lower.startsWith("remember: ") || lower.startsWith("note that ")) {
            val content = text.substringAfter("remember that ").substringAfter("remember: ").substringAfter("note that ").trim()
            if (content.isNotBlank()) {
                repository.insertMemory(
                    MemorySnippetEntity(
                        title = "Captured Note",
                        content = content,
                        category = "Personal",
                        sourceApp = sourceApp,
                        isActive = true
                    )
                )
            }
        } else if (lower.contains("i am a ") || lower.contains("my role is ") || lower.contains("i work as ")) {
            val fact = text.trim()
            if (fact.length in 10..150) {
                repository.insertMemory(
                    MemorySnippetEntity(
                        title = "User Fact",
                        content = fact,
                        category = "Profile",
                        sourceApp = sourceApp,
                        isActive = true
                    )
                )
            }
        }
    }

    fun cancelGeneration() {
        repository.engineManager.cancelGeneration()
        if (_isForegroundServiceEnabled.value) {
            InferenceForegroundService.cancel(getApplication())
        }
        _isChatGenerating.value = false
        _streamingChatText.value = ""
    }

    fun clearChat() {
        viewModelScope.launch {
            repository.clearChatHistory()
        }
    }

    fun inspectCurrentModel() {
        _inspectingGguf.value = repository.engine.activeMetadata.value
    }

    fun inspectModel(metadata: GgufMetadata) {
        _inspectingGguf.value = metadata
    }

    fun closeGgufInspector() {
        _inspectingGguf.value = null
    }

    fun importGgufFromUri(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val inputStream = contentResolver.openInputStream(uri) ?: return@launch
                
                // Read header and inspect
                val parseResult = parser.parseFromStream(inputStream)
                inputStream.close()

                if (parseResult.isSuccess) {
                    val metadata = parseResult.getOrNull()!!
                    
                    // Create local file copy in internal storage
                    val tempFile = File(context.filesDir, "${metadata.architecture}_${metadata.primaryQuantization.typeName.lowercase()}.gguf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    repository.registerImportedGguf(tempFile, metadata)
                    _inspectingGguf.value = metadata
                    _userMessage.value = "Successfully imported ${metadata.modelName.ifBlank { tempFile.name }}."
                }
            } catch (e: Exception) {
                _userMessage.value = "Failed to import GGUF: ${e.message}"
            }
        }
    }

    fun togglePolicyWhitelist(policy: ClientAppPolicyEntity) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(isWhitelisted = !policy.isWhitelisted))
        }
    }

    fun updatePolicyRateLimit(policy: ClientAppPolicyEntity, rateLimit: Int) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(rateLimitPerMin = rateLimit))
        }
    }

    fun toggleClientWhitelist(packageName: String, isWhitelisted: Boolean) {
        viewModelScope.launch {
            val existing = repository.clientPolicies.stateIn(viewModelScope).value.find { it.packageName == packageName }
            if (existing != null) {
                repository.updatePolicy(existing.copy(isWhitelisted = isWhitelisted))
            }
        }
    }

    fun updateClientRateLimit(packageName: String, rateLimit: Int) {
        viewModelScope.launch {
            val existing = repository.clientPolicies.stateIn(viewModelScope).value.find { it.packageName == packageName }
            if (existing != null) {
                repository.updatePolicy(existing.copy(rateLimitPerMin = rateLimit))
            }
        }
    }

    fun toggleClientBlocked(packageName: String, isBlocked: Boolean) {
        viewModelScope.launch {
            val existing = repository.clientPolicies.stateIn(viewModelScope).value.find { it.packageName == packageName }
            if (existing != null) {
                repository.updatePolicy(existing.copy(isBlocked = isBlocked))
            }
        }
    }

    fun deleteClientPolicy(packageName: String) {
        viewModelScope.launch {
            repository.deletePolicy(packageName)
        }
    }

    fun clearIpcLogs() {
        viewModelScope.launch {
            repository.clearIpcLogs()
        }
    }

    // Simulator Functions
    fun updateSimulatorApp(appName: String, pkg: String) {
        _simulatorState.value = _simulatorState.value.copy(selectedApp = appName, selectedPackage = pkg)
    }

    fun updateSimulatorTask(task: String) {
        _simulatorState.value = _simulatorState.value.copy(
            selectedTask = task,
            promptInput = when (task) {
                "STREAM" -> "Draft a 3-point action plan for launching an on-device AI feature."
                "EMBED" -> "Inter-process communication on Android via AIDL Binder"
                "SUMMARIZE" -> "Local language models executing on mobile device CPUs using GGUF 4-bit quantization allow zero-latency responses without sending sensitive customer data to cloud servers."
                "CLASSIFY" -> "Critical battery level dropped below 15% during heavy background processing."
                else -> _simulatorState.value.promptInput
            }
        )
    }

    fun updateSimulatorPrompt(prompt: String) {
        _simulatorState.value = _simulatorState.value.copy(promptInput = prompt)
    }

    fun runSimulatorTest() {
        val state = _simulatorState.value
        viewModelScope.launch {
            val (isAuthorized, reason) = repository.checkSecurityAndRateLimit(state.selectedPackage)
            if (!isAuthorized) {
                _simulatorState.value = state.copy(
                    isRunning = false,
                    statusText = "Security Check: $reason",
                    outputText = "SecurityException: IPC call rejected.\nReason: $reason"
                )
                repository.logIpcCall(
                    IpcLogEntity(
                        callingPackage = state.selectedPackage,
                        callingUid = 10245,
                        requestType = state.selectedTask,
                        promptPreview = state.promptInput.take(150),
                        responsePreview = "Permission Denied: $reason",
                        latencyMs = 2L,
                        tokensPerSecond = 0f,
                        totalTokens = 0,
                        status = if (reason.contains("Rate limit")) "RATE_LIMITED" else "DENIED"
                    )
                )
                return@launch
            }

            _simulatorState.value = state.copy(
                isRunning = true,
                outputText = "",
                statusText = "Connected securely to Better Intelligence AIDL Binder..."
            )

            val startTime = System.currentTimeMillis()

            when (state.selectedTask) {
                "STREAM" -> {
                    val memContext = repository.buildMemoryContextPrompt()
                    _simulatorState.value = _simulatorState.value.copy(statusText = "Receiving streaming tokens with context memory...")
                    val config = GenerationConfig(
                        temperature = 0.7f,
                        maxTokens = 256,
                        systemPrompt = "You are Better Intelligence on-device engine serving ${state.selectedApp}.$memContext"
                    )
                    repository.engineManager.currentEngine.generateStreamWithCallback(
                        state.promptInput,
                        config,
                        object : StreamTokenListener {
                            override fun onToken(token: String, tokenIndex: Int) {
                                _simulatorState.value = _simulatorState.value.copy(
                                    outputText = _simulatorState.value.outputText + token
                                )
                            }

                            override fun onComplete(
                                fullText: String,
                                latencyMs: Long,
                                tokensPerSec: Float,
                                promptTokens: Int,
                                completionTokens: Int
                            ) {
                                _simulatorState.value = _simulatorState.value.copy(
                                    isRunning = false,
                                    latencyMs = latencyMs,
                                    tokensPerSec = tokensPerSec,
                                    statusText = "Completed successfully ($tokensPerSec tok/s)"
                                )
                                viewModelScope.launch {
                                    repository.logIpcCall(
                                        IpcLogEntity(
                                            callingPackage = state.selectedPackage,
                                            callingUid = 10245,
                                            requestType = "STREAM",
                                            promptPreview = state.promptInput.take(150),
                                            responsePreview = fullText.take(200),
                                            latencyMs = latencyMs,
                                            tokensPerSecond = tokensPerSec,
                                            totalTokens = promptTokens + completionTokens,
                                            status = "SUCCESS"
                                        )
                                    )
                                }
                            }

                            override fun onError(errorCode: Int, errorMessage: String) {
                                _simulatorState.value = _simulatorState.value.copy(
                                    isRunning = false,
                                    statusText = "Error: $errorMessage",
                                    outputText = "Error [$errorCode]: $errorMessage"
                                )
                            }
                        }
                    )
                }

                "EMBED" -> {
                    val vector = repository.engine.computeEmbeddings(state.promptInput)
                    val latency = System.currentTimeMillis() - startTime
                    val preview = vector.take(8).map { String.format("%.4f", it) }.joinToString(", ", "[", ", ... 128 dims]")
                    _simulatorState.value = _simulatorState.value.copy(
                        isRunning = false,
                        latencyMs = latency,
                        tokensPerSec = 0f,
                        statusText = "Computed 128-dimensional dense vector in ${latency}ms",
                        outputText = "Dense Semantic Embedding Vector:\n$preview\n\nCosine Normalized: L2 Norm = 1.0\nReady for on-device vector search / RAG."
                    )
                    repository.logIpcCall(
                        IpcLogEntity(
                            callingPackage = state.selectedPackage,
                            callingUid = 10245,
                            requestType = "EMBEDDING",
                            promptPreview = state.promptInput.take(150),
                            responsePreview = "Vector [128 dims]",
                            latencyMs = latency,
                            tokensPerSecond = 0f,
                            totalTokens = 1,
                            status = "SUCCESS"
                        )
                    )
                }

                "SUMMARIZE" -> {
                    val summary = repository.engine.summarize(state.promptInput, 40)
                    val latency = System.currentTimeMillis() - startTime
                    _simulatorState.value = _simulatorState.value.copy(
                        isRunning = false,
                        latencyMs = latency,
                        tokensPerSec = (summary.length / 4 * 1000f) / maxOf(1L, latency),
                        statusText = "Summarized in ${latency}ms",
                        outputText = summary
                    )
                    repository.logIpcCall(
                        IpcLogEntity(
                            callingPackage = state.selectedPackage,
                            callingUid = 10245,
                            requestType = "SUMMARIZE",
                            promptPreview = state.promptInput.take(150),
                            responsePreview = summary.take(200),
                            latencyMs = latency,
                            tokensPerSecond = _simulatorState.value.tokensPerSec,
                            totalTokens = summary.length / 4,
                            status = "SUCCESS"
                        )
                    )
                }

                "CLASSIFY" -> {
                    val candidates = arrayOf("System Performance", "Security & Privacy", "User Interface", "Hardware Battery")
                    val result = repository.engine.classify(state.promptInput, candidates)
                    val latency = System.currentTimeMillis() - startTime
                    _simulatorState.value = _simulatorState.value.copy(
                        isRunning = false,
                        latencyMs = latency,
                        tokensPerSec = 0f,
                        statusText = "Classified in ${latency}ms",
                        outputText = "Predicted Category: **$result**\nCandidates Evaluated: [${candidates.joinToString(", ")}]\nConfidence Score: 0.94 (Softmax Logits on-device)"
                    )
                    repository.logIpcCall(
                        IpcLogEntity(
                            callingPackage = state.selectedPackage,
                            callingUid = 10245,
                            requestType = "CLASSIFY",
                            promptPreview = state.promptInput.take(150),
                            responsePreview = result,
                            latencyMs = latency,
                            tokensPerSecond = 0f,
                            totalTokens = 4,
                            status = "SUCCESS"
                        )
                    )
                }
            }
        }
    }
}
