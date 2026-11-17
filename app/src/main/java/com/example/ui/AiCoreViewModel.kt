package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.data.AiCoreRepository
import com.example.data.ClientAppPolicyEntity
import com.example.data.HardwarePerformanceLogEntity
import com.example.data.IpcLogEntity
import com.example.data.ModelEntity
import com.example.engine.BackendType
import com.example.engine.DeviceTier
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
import com.example.engine.ModelMemoryPlanner
import com.example.engine.ModelRecommendation
import com.example.engine.ModelTierDecision
import com.example.engine.ModelTierRole
import com.example.engine.QuantizationType
import com.example.engine.StreamTokenListener
import com.example.engine.SupportedEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream

enum class AppTab(val title: String) {
    OPTIMISE("Optimise your AI"),
    MODELS("Models"),
    CONNECT("Connected apps"),
    SETTINGS("Settings"),
    APPEARANCE("Appearance"),
    PRIVACY("Privacy & data"),
    ADVANCED("Advanced & about")
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiCoreViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {

    private val repository = AiCoreRepository.getInstance(application)
    private val parser = GgufParser()

    val automaticSetup = repository.automaticSetup.state
    fun setDownloadsWifiOnly(value: Boolean) {
        com.example.service.DownloadQueueStore(getApplication()).setWifiOnly(value)
        com.example.service.ModelDownloadQueue.schedule(getApplication(), true)
    }
    val downloadsWifiOnly get() = com.example.service.DownloadQueueStore(getApplication()).wifiOnly()
    val backgroundDownloadsEnabled get() = com.example.service.DownloadQueueStore(getApplication()).backgroundDownloadsEnabled()
    fun setBackgroundDownloadsEnabled(value: Boolean) {
        com.example.service.DownloadQueueStore(getApplication()).setBackgroundDownloadsEnabled(value)
        com.example.service.ModelDownloadQueue.schedule(getApplication(), true)
    }
    val autoResumeDownloads get() = com.example.service.DownloadQueueStore(getApplication()).autoResumeEnabled()
    fun setAutoResumeDownloads(value: Boolean) {
        com.example.service.DownloadQueueStore(getApplication()).setAutoResumeEnabled(value)
    }
    val downloadNotificationsEnabled get() = com.example.service.DownloadQueueStore(getApplication()).notificationsEnabled()
    fun setDownloadNotificationsEnabled(value: Boolean) {
        com.example.service.DownloadQueueStore(getApplication()).setNotificationsEnabled(value)
    }
    fun togglePolicyAppContext(policy: ClientAppPolicyEntity) {
        viewModelScope.launch { repository.updatePolicy(policy.copy(allowAppContext = !policy.allowAppContext)) }
    }
    fun updateClientPrimaryModel(policy: ClientAppPolicyEntity, modelId: Long?) {
        viewModelScope.launch { repository.updatePolicy(policy.copy(primaryModelId = modelId)) }
    }
    fun updateClientFallbackModel(policy: ClientAppPolicyEntity, modelId: Long?) {
        viewModelScope.launch { repository.updatePolicy(policy.copy(fallbackModelId = modelId)) }
    }
    fun revokeClientPermissions(policy: ClientAppPolicyEntity) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(
                isWhitelisted = false,
                allowInference = false,
                allowAppContext = false,
                allowReadMemory = false,
                allowWriteMemory = false,
                primaryModelId = null,
                fallbackModelId = null
            ))
        }
    }
    fun recheckDevice() { viewModelScope.launch(Dispatchers.IO) { repository.automaticSetup.checkDevice() } }
    fun setUpAutomatically(wifiOnly: Boolean, reviewed: com.example.engine.AutomaticModelPair?) {
        val token = repository.automaticSetup.approvalToken()
        viewModelScope.launch(Dispatchers.IO) {
        runCatching { repository.automaticSetup.approve(wifiOnly, reviewed, token) }.onFailure { _userMessage.value = it.message }
    } }
    fun chooseModelsManually() { repository.automaticSetup.cancelPending(); repository.automaticSetup.dismiss(); selectTab(AppTab.MODELS) }
    fun rerunModelBenchmarks() { val token = repository.automaticSetup.approvalToken(); viewModelScope.launch(Dispatchers.IO) { repository.automaticSetup.rerun(token) } }
    fun deletePartialDownload(model: ModelEntity) {
        if (_deletingModelId.value != null) return
        _deletingModelId.value = model.id
        viewModelScope.launch(Dispatchers.IO) { try {
        runCatching { repository.deletePartialDownload(model.id) }.onSuccess { _userMessage.value = "Partial download deleted. Installed models were kept." }.onFailure { _userMessage.value = it.message }
        } finally { _deletingModelId.value = null } }
    }
    val allModels: StateFlow<List<ModelEntity>> = repository.allModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val modelDownloadErrors = repository.downloadErrors

    val activeModel: StateFlow<ModelEntity?> = repository.activeModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val primaryModelId: StateFlow<Long?> = repository.preferencesRepository.primaryModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val secondaryModelId: StateFlow<Long?> = repository.preferencesRepository.secondaryModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val autoRamFallback: StateFlow<Boolean> = repository.preferencesRepository.autoRamFallback
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val secondaryModel: StateFlow<ModelEntity?> = combine(allModels, activeModel, primaryModelId, secondaryModelId) { models, active, primId, secId ->
        val primary = models.find { it.id == primId && it.isInstalled } ?: active
        models.find { it.id == secId && it.isInstalled && it.id != primary?.id }
            ?: ModelMemoryPlanner.pickDefaultSecondary(models, primary)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _activeTierDecision = MutableStateFlow<ModelTierDecision?>(null)
    val activeTierDecision: StateFlow<ModelTierDecision?> = _activeTierDecision.asStateFlow()

    val telemetry: StateFlow<HardwareTelemetry> = repository.engine.telemetry

    val recentIpcLogs: StateFlow<List<IpcLogEntity>> = repository.recentIpcLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalIpcCalls: StateFlow<Int> = repository.totalIpcCalls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val clientPolicies: StateFlow<List<ClientAppPolicyEntity>> = repository.clientPolicies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appearance = repository.preferencesRepository.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, "AMOLED")
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
    private val navigation = (savedState.get<ArrayList<String>>("navigation")?.mapNotNull { runCatching { AppTab.valueOf(it) }.getOrNull() }
        ?.takeIf { it.firstOrNull() == AppTab.MODELS } ?: listOf(AppTab.MODELS)).toMutableList()
    private val _currentTab = MutableStateFlow(navigation.last())
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()
    private val _navigationDepth = MutableStateFlow(navigation.size)
    val navigationDepth: StateFlow<Int> = _navigationDepth.asStateFlow()

    // Model Filter state
    private val _modelSearchQuery = MutableStateFlow("")
    val modelSearchQuery: StateFlow<String> = _modelSearchQuery.asStateFlow()

    private val _modelCategoryFilter = MutableStateFlow("ALL")
    val modelCategoryFilter: StateFlow<String> = _modelCategoryFilter.asStateFlow()

    // Default inference settings shared with connected apps.
    private val _generationParams = MutableStateFlow(GenerationParams(contextSize = 2048, maxTokens = 256))
    val generationParams: StateFlow<GenerationParams> = _generationParams.asStateFlow()

    // Engine Manager state: llama.cpp (GGUF) vs LiteRT (TFLite)
    val selectedEngine: StateFlow<SupportedEngine> = repository.engineManager.selectedEngineType
    val processingMode = repository.engine.hardware.mode
    val accelerationCapabilities = repository.engine.hardware.capabilities
    val gpuLayerLimit = repository.engine.hardware.layerLimit

    fun setProcessingMode(mode: com.example.engine.ProcessingMode, layers: Int = gpuLayerLimit.value) {
        viewModelScope.launch(Dispatchers.IO) {
        runCatching { repository.engine.setProcessingMode(mode, layers) }
            .onSuccess { _userMessage.value = "Processing setting saved. Applies to the next request." }
            .onFailure { _userMessage.value = it.message }
        }
    }
    
    // Hardware Pre-flight Validation
    val hardwareValidation = HardwareValidator.validateEnvironment(application)

    // Benchmark state
    private val _benchmarkResult = MutableStateFlow<com.example.engine.BenchmarkResult?>(null)
    val benchmarkResult: StateFlow<com.example.engine.BenchmarkResult?> = _benchmarkResult.asStateFlow()

    private val _isBenchmarking = MutableStateFlow(false)
    val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    // Quality Preset: "FAST" (Q4_K_M), "BALANCED" (Q5_K_M), "QUALITY" (Q6_K / Q8_0)
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

    private val _deletingModelId = MutableStateFlow<Long?>(null)
    val deletingModelId: StateFlow<Long?> = _deletingModelId.asStateFlow()

    // Developer Panel Visibility
    private val _isDeveloperPanelExpanded = MutableStateFlow(false)
    val isDeveloperPanelExpanded: StateFlow<Boolean> = _isDeveloperPanelExpanded.asStateFlow()

    // GGUF Inspector state
    private val _inspectingGguf = MutableStateFlow<GgufMetadata?>(null)
    val inspectingGguf: StateFlow<GgufMetadata?> = _inspectingGguf.asStateFlow()

    // IPC Simulator state
    // Toast / Action notification
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()
    private val _loadingModelId = MutableStateFlow<Long?>(null)
    val loadingModelId: StateFlow<Long?> = _loadingModelId.asStateFlow()

    init {
        viewModelScope.launch { repository.preferencesRepository.foregroundEnabled.collect { _isForegroundServiceEnabled.value = it } }
        viewModelScope.launch { repository.preferencesRepository.generationPreferences.collect { _generationParams.value = it } }
        viewModelScope.launch {
            repository.preferencesRepository.darkThemeFlow.collect { _isDarkTheme.value = it }
        }
        viewModelScope.launch {
            repository.awaitModelInitialization()
            withContext(Dispatchers.IO) {
                repository.automaticSetup.checkDevice()
                com.example.service.ModelDownloadQueue.ensureScheduled(application)
            }
            if (!repository.automaticSetup.seen()) {
                repository.automaticSetup.dismiss()
            }
        }
        // Initial hardware analysis
        repository.analyzeHardware(application)

        // Observe and apply saved engine preference from DataStore
        viewModelScope.launch {
            repository.savedEnginePreference.collect { savedEngine ->
                if (!repository.engine.isReady()) repository.engineManager.selectEngine(savedEngine)
            }
        }
    }

    fun selectEngine(engine: SupportedEngine) {
        viewModelScope.launch {
            if (repository.engine.isReady() && (engine == SupportedEngine.LITERT) != repository.engine.isLiteRtLoaded()) {
                _userMessage.value = "Select a ${engine.formatBadge} model in Models to change inference engine."
                return@launch
            }
            repository.preferencesRepository.saveEnginePreference(engine)
            repository.engineManager.selectEngine(engine)
            _userMessage.value = "Active engine switched to ${engine.displayName} and saved to DataStore."
        }
    }

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
        viewModelScope.launch { repository.preferencesRepository.saveDarkTheme(isDark) }
    }

    fun setAppearance(value: String) { viewModelScope.launch { repository.preferencesRepository.saveAppearance(value) } }
    fun refreshHardwareDiagnostics() {
        repository.analyzeHardware(getApplication())
        val profile = repository.hardwareProfile.value
        val ram = profile?.availableRamMb ?: 0L
        val thermal = profile?.thermalStatus ?: "Nominal"
        _userMessage.value = "Hardware diagnostics updated: ${ram}MB RAM free • $thermal"
    }

    fun selectTab(tab: AppTab) {
        if (tab == _currentTab.value) return
        if (tab in setOf(AppTab.MODELS, AppTab.CONNECT, AppTab.SETTINGS)) { navigation.clear(); if (tab != AppTab.MODELS) navigation += AppTab.MODELS }
        navigation += tab
        _currentTab.value = tab
        _navigationDepth.value = navigation.size
        savedState["navigation"] = ArrayList(navigation.map { it.name })
    }
    fun navigateBack() {
        if (navigation.size > 1) navigation.removeAt(navigation.lastIndex)
        _currentTab.value = navigation.last()
        _navigationDepth.value = navigation.size
        savedState["navigation"] = ArrayList(navigation.map { it.name })
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
        _generationParams.value = params
        viewModelScope.launch { repository.preferencesRepository.saveGenerationPreferences(params) }
    }

    fun toggleDeveloperPanel() {
        _isDeveloperPanelExpanded.value = !_isDeveloperPanelExpanded.value
    }

    fun unloadActiveModel() {
        if (repository.engine.telemetry.value.isRunning) { _userMessage.value = "Wait for the connected app to finish before unloading its model."; return }
        viewModelScope.launch(Dispatchers.IO) {
            try { repository.unloadModel(); _activeTierDecision.value = null }
            catch (e: Exception) { _userMessage.value = e.message }
        }
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

    fun releasableGpuWeightsMb(): Long = repository.engine.releasableGpuWeightsMb()
    fun resetBackendDecisions() {
        viewModelScope.launch(Dispatchers.IO) {
        runCatching { repository.engine.resetBackendDecisions() }
            .onSuccess { _userMessage.value = "Backend measurements reset. Automatic will retest when resources allow." }
            .onFailure { _userMessage.value = it.message }
        }
    }

    fun runBenchmark() {
        if (_isBenchmarking.value || repository.engine.telemetry.value.isRunning) return
        _isBenchmarking.value = true
        _userMessage.value = "Running automated device benchmark..."
        
        viewModelScope.launch {
            try {
                val result = repository.engine.runBenchmark(
                    contextLength = _generationParams.value.contextSize
                )
                _benchmarkResult.value = result
                
                // Automatically tune runtime parameters to identified sweet spot
                _generationParams.value = _generationParams.value.copy(
                    threads = result.recommendedThreads,
                    contextSize = result.recommendedContext
                )

                // Persist benchmark metric log to Room database
                val active = activeModel.value
                val profile = com.example.engine.HardwareAnalyzer.analyze(getApplication())
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
                    ramAvailableAtTestMb = profile.availableRamMb,
                    batteryTemperatureC = profile.batteryTemperatureC,
                    thermalState = profile.thermalStatus,
                    threadCount = result.recommendedThreads,
                    gpuLayers = result.recommendedGpuLayers,
                    deviceModel = profile.deviceModel,
                    chipsetAbi = profile.cpuAbi,
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
        if (_isBenchmarking.value || repository.engine.telemetry.value.isRunning) return
        _isBenchmarking.value = true
        _userMessage.value = "Benchmarking ${model.name} (${model.quantization}) on hardware..."

        viewModelScope.launch {
            try {
                val log = repository.runAndSaveBenchmarkForModel(
                    model = model,
                    contextSize = _generationParams.value.contextSize
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
        _generationParams.value = _generationParams.value.copy(
            threads = bench.recommendedThreads,
            contextSize = bench.recommendedContext
        )
        _userMessage.value = "Saved ${bench.recommendedThreads} CPU threads and ${bench.recommendedContext} context. AI Processing Mode manages GPU offloading."
    }

    fun switchModel(model: ModelEntity) {
        if (repository.engine.telemetry.value.isRunning) { _userMessage.value = "Wait for the connected app to finish before switching models."; return }
        if (_loadingModelId.value != null || _deletingModelId.value != null) return
        if (needsTerms(model)) { _licenseModel.value = model; return }
        _loadingModelId.value = model.id
        viewModelScope.launch {
            try {
                repository.switchActiveModel(model)
                _activeTierDecision.value = null
                _userMessage.value = "Loaded ${model.name} as Primary model. On-device inference is ready."
            } catch (e: Exception) { _userMessage.value = e.message ?: "Unable to load model" }
            finally { _loadingModelId.value = null }
        }
    }

    fun setSecondaryModel(model: ModelEntity?) {
        viewModelScope.launch {
            repository.setSecondaryModel(model)
            _userMessage.value = if (model != null) "Set ${model.name.substringBefore(" · ")} as Secondary fallback model."
                else "Secondary model reset to automatic selection."
        }
    }

    fun setAutoRamFallback(enabled: Boolean) {
        viewModelScope.launch {
            repository.preferencesRepository.saveAutoRamFallback(enabled)
            _userMessage.value = if (enabled) "Automatic Secondary model fallback enabled for low RAM."
                else "Automatic Secondary model fallback disabled."
        }
    }

    fun restoreBaseModel() {
        viewModelScope.launch {
            val base = repository.allModels.first().firstOrNull { it.isBaseModel } ?: return@launch
            if (base.isInstalled) switchModel(base) else downloadModel(base)
        }
    }

    private val _licenseModel = MutableStateFlow<ModelEntity?>(null)
    val licenseModel: StateFlow<ModelEntity?> = _licenseModel.asStateFlow()
    private fun needsTerms(model: ModelEntity): Boolean = model.architecture.startsWith("gemma") && !model.architecture.startsWith("gemma4") &&
        !getApplication<Application>().getSharedPreferences("model_terms", Context.MODE_PRIVATE).getBoolean("gemma", false)
    fun dismissModelTerms() { _licenseModel.value = null }
    fun acceptModelTerms() {
        val model = _licenseModel.value ?: return
        getApplication<Application>().getSharedPreferences("model_terms", Context.MODE_PRIVATE).edit().putBoolean("gemma", true).apply()
        _licenseModel.value = null
        if (model.isInstalled) switchModel(model) else downloadModel(model)
    }

    fun downloadModel(model: ModelEntity) {
        if (needsTerms(model)) { _licenseModel.value = model; return }
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
        val directRepo = runCatching { com.example.engine.HuggingFaceCatalog.normalizeRepo(q) }.getOrNull()
        if (directRepo != null) {
            selectHfRepo(directRepo)
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
        _hfRepoGgufFiles.value = emptyList()
        viewModelScope.launch {
            _isLoadingRepoFiles.value = true
            try {
                val files = repository.fetchRepoGgufFiles(repoId)
                if (_selectedHfRepo.value != repoId) return@launch
                _hfRepoGgufFiles.value = files
                if (files.isEmpty()) {
                    _userMessage.value = "No .gguf files detected in $repoId."
                } else {
                    _userMessage.value = "Discovered ${files.size} GGUF quantization files."
                }
            } catch (e: Exception) {
                if (_selectedHfRepo.value == repoId) _userMessage.value = "Failed to load repo files: ${e.message}"
            } finally {
                if (_selectedHfRepo.value == repoId) _isLoadingRepoFiles.value = false
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
                if (addedModel.isInstalled) _userMessage.value = "Already downloaded. Select it in My models."
                else if (addedModel.downloadState != "DOWNLOADING") downloadModel(addedModel)
                clearHfRepoSelection()
            } catch (e: Exception) {
                _userMessage.value = "Failed to add model: ${e.message}"
            }
        }
    }

    fun verifyModelChecksum(model: ModelEntity) {
        if (_deletingModelId.value != null || _verifyingModelId.value != null) return
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
        if (repository.engine.telemetry.value.isRunning) { _userMessage.value = "Wait for the connected app to finish before deleting models."; return }
        if (_deletingModelId.value != null || _loadingModelId.value != null || _verifyingModelId.value != null) return
        _deletingModelId.value = model.id
        viewModelScope.launch {
            try {
                repository.uninstallModel(model)
                _userMessage.value = "Deleted ${model.name} from this device."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _userMessage.value = "Could not delete model: ${e.message}"
            } finally {
                _deletingModelId.value = null
            }
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
            val match = repository.allModels.first().find { it.filename == recommendation.recommendedFilename } ?: return@launch
            if (match.isInstalled) switchModel(match) else downloadModel(match)
            selectTab(AppTab.MODELS)
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
            val directory = repository.downloadManager.modelsDirectory
            val tempFile = File(directory, "import-${java.util.UUID.randomUUID()}.part")
            var installedFile: File? = null
            try {
                val displayName = runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } }.getOrNull().orEmpty()
                var liteRt = displayName.endsWith(".litertlm", true)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(65536)
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            check(directory.usableSpace > count + 128L * 1024 * 1024) { "Insufficient storage" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Cannot open file")
                liteRt = liteRt || tempFile.inputStream().use { input ->
                    val header = ByteArray(8); input.read(header) == 8 && String(header, Charsets.US_ASCII) == "LITERTLM"
                }
                val metadata = if (!liteRt) parser.parseFromFile(tempFile).getOrThrow() else null
                if (liteRt) com.google.ai.edge.litertlm.ModelInfo.from(tempFile.absolutePath).use {
                    require(it is com.google.ai.edge.litertlm.ModelInfo.Llm) { "Import a compatible .litertlm text-generation model" }
                }
                val installed = File(directory, tempFile.name.removeSuffix(".part") + if (liteRt) ".litertlm" else ".gguf")
                check(tempFile.renameTo(installed)) { "Could not install the model" }
                installedFile = installed
                if (liteRt) repository.registerImportedLiteRt(installed, displayName.replace(Regex("(?i)\\.litertlm$"), "").ifBlank { "Imported LiteRT-LM model" })
                else repository.registerImportedGguf(installed, checkNotNull(metadata))
                _inspectingGguf.value = metadata
                _userMessage.value = "Model imported. Select it in Settings > Models to use it."
            } catch (e: Exception) {
                tempFile.delete()
                installedFile?.delete()
                if (e is kotlinx.coroutines.CancellationException) throw e
                _userMessage.value = "Could not import this model: ${e.message}. Use GGUF or LiteRT-LM, rather than raw TFLite, task or safetensors files."
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

    fun togglePolicyInference(policy: ClientAppPolicyEntity) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(allowInference = !policy.allowInference))
        }
    }

    fun togglePolicyReadMemory(policy: ClientAppPolicyEntity) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(allowReadMemory = !policy.allowReadMemory))
        }
    }

    fun togglePolicyWriteMemory(policy: ClientAppPolicyEntity) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(allowWriteMemory = !policy.allowWriteMemory))
        }
    }

    fun togglePolicyStructuredJson(policy: ClientAppPolicyEntity) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(allowStructuredJson = !policy.allowStructuredJson))
        }
    }

    fun updatePolicyCustomSystemPrompt(policy: ClientAppPolicyEntity, prompt: String) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(customSystemPrompt = prompt))
        }
    }

    fun updatePolicyAllowedCategories(policy: ClientAppPolicyEntity, categories: String) {
        viewModelScope.launch {
            repository.updatePolicy(policy.copy(allowedMemoryCategories = categories))
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

    private val _ipcLogFilterStatus = MutableStateFlow("ALL")
    val ipcLogFilterStatus: StateFlow<String> = _ipcLogFilterStatus.asStateFlow()

    fun setIpcLogFilterStatus(status: String) {
        _ipcLogFilterStatus.value = status
    }

}
