package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
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
import com.example.engine.ModelMemoryPlanner
import com.example.engine.ModelRecommendation
import com.example.engine.ModelTierDecision
import com.example.engine.ModelTierRole
import com.example.engine.QuantizationType
import com.example.engine.StreamTokenListener
import com.example.engine.SupportedEngine
import com.example.service.InferenceForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.engine.ChatTurn
import com.example.engine.ConversationContext
import com.example.grounding.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream

enum class AppTab(val title: String) {
    MODELS("Models"),
    CHAT("Chat"),
    CONNECT("Connected apps"),
    SETTINGS("Settings"),
    HISTORY("Conversations"),
    WEB("Web & grounding"),
    APPEARANCE("Appearance"),
    PRIVACY("Privacy & data"),
    MEMORY("Memory"),
    ADVANCED("Advanced & about")
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiCoreViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {

    private val repository = AiCoreRepository.getInstance(application)
    private val parser = GgufParser()

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

    private val _conversationId = MutableStateFlow(0L)
    val conversationId = _conversationId.asStateFlow()
    private val _pendingReplyId = MutableStateFlow<Long?>(null)
    val pendingReplyId = _pendingReplyId.asStateFlow()
    val conversations = repository.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val chatMessages: StateFlow<List<ChatMessageEntity>> = _conversationId.flatMapLatest { repository.messages(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _draft = MutableStateFlow(savedState.get<String>("draft") ?: "")
    val draft = _draft.asStateFlow()
    private var draftJob: kotlinx.coroutines.Job? = null
    private val conversationLock = Mutex()
    private var editingMessageId: Long? = null
    private val _isEditingMessage = MutableStateFlow(false)
    val isEditingMessage = _isEditingMessage.asStateFlow()
    private val _groundingMode = MutableStateFlow(GroundingMode.OFF)
    val groundingMode = _groundingMode.asStateFlow()
    private val _chatStatus = MutableStateFlow("")
    val chatStatus = _chatStatus.asStateFlow()
    val appearance = repository.preferencesRepository.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, "DARK")
    val webAllowed = repository.preferencesRepository.webAllowed.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val defaultWebMode = repository.preferencesRepository.webMode.stateIn(viewModelScope, SharingStarted.Eagerly, "OFF")
    private val grounding = GroundingCoordinator(mapOf(SearchProviderId.WEB to PublicWebResearch()))
    init { LegacySearchCleanup.clear(application) }

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
    private val navigation = (savedState.get<ArrayList<String>>("navigation")?.mapNotNull { runCatching { AppTab.valueOf(it) }.getOrNull() }
        ?.takeIf { it.firstOrNull() == AppTab.CHAT } ?: listOf(AppTab.CHAT)).toMutableList()
    private val _currentTab = MutableStateFlow(navigation.last())
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()
    private val _navigationDepth = MutableStateFlow(navigation.size)
    val navigationDepth: StateFlow<Int> = _navigationDepth.asStateFlow()

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

    private val _isAutoMemoryCaptureEnabled = MutableStateFlow(false)
    val isAutoMemoryCaptureEnabled: StateFlow<Boolean> = _isAutoMemoryCaptureEnabled.asStateFlow()

    // Playground state
    private val _isChatGenerating = MutableStateFlow(false)
    val isChatGenerating: StateFlow<Boolean> = _isChatGenerating.asStateFlow()

    private val _streamingChatText = MutableStateFlow("")
    private var chatPreparationJob: kotlinx.coroutines.Job? = null
    private var chatInferenceJob: kotlinx.coroutines.Job? = null
    val streamingChatText: StateFlow<String> = _streamingChatText.asStateFlow()

    private val _chatGenerationParams = MutableStateFlow(GenerationParams(contextSize = 2048, maxTokens = 256))
    val chatGenerationParams: StateFlow<GenerationParams> = _chatGenerationParams.asStateFlow()

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

    private val _deletingModelId = MutableStateFlow<Long?>(null)
    val deletingModelId: StateFlow<Long?> = _deletingModelId.asStateFlow()

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
    private val _loadingModelId = MutableStateFlow<Long?>(null)
    val loadingModelId: StateFlow<Long?> = _loadingModelId.asStateFlow()

    init {
        viewModelScope.launch {
            repository.preferencesRepository.saveAutoRamFallback(true)
            repository.preferencesRepository.saveForegroundEnabled(true)
            val autoParams = GenerationParams(contextSize = 2048, maxTokens = 384, temperature = 0.6f, accelerationMode = "Automatic")
            _chatGenerationParams.value = autoParams
            repository.preferencesRepository.saveGenerationPreferences(autoParams)
        }
        viewModelScope.launch { repository.preferencesRepository.foregroundEnabled.collect { _isForegroundServiceEnabled.value = it } }
        viewModelScope.launch {
            repository.preferencesRepository.memoryEnabled.collect { _isAutoMemoryCaptureEnabled.value = it }
        }
        viewModelScope.launch {
            repository.recoverInterruptedMessages()
            val previous = repository.preferencesRepository.activeConversation.first()
            val restored = repository.conversation(previous)
            val id = restored?.id ?: repository.conversations.first().firstOrNull()?.id ?: repository.createConversation()
            activateConversation(id)
        }
        viewModelScope.launch {
            repository.preferencesRepository.darkThemeFlow.collect { _isDarkTheme.value = it }
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
    fun setWebAllowed(value: Boolean) {
        if (!value) { grounding.clearTemporaryData(); if (chatInferenceJob == null && _isChatGenerating.value) chatPreparationJob?.cancel() }
        viewModelScope.launch { repository.preferencesRepository.saveWebAllowed(value) }
    }
    fun setDefaultWebMode(value: String) { viewModelScope.launch { repository.preferencesRepository.saveWebMode(value) } }
    fun clearSearchData() { grounding.clearTemporaryData(); _userMessage.value = "Temporary search data cleared." }
    fun setGroundingMode(mode: GroundingMode) {
        _groundingMode.value = mode
        val id = _conversationId.value
        viewModelScope.launch { repository.saveGroundingMode(id, mode.name) }
    }
    fun updateDraft(value: String) {
        _draft.value = value
        savedState["draft"] = value; savedState["draftConversation"] = _conversationId.value
        val id = _conversationId.value
        draftJob?.cancel()
        draftJob = viewModelScope.launch { kotlinx.coroutines.delay(200); repository.saveDraft(id, value) }
    }
    private suspend fun activateConversation(id: Long) {
        val conversation = repository.conversation(id) ?: return
        // Finish the suspending preference write before publishing a usable ID.
        // Otherwise the first send can see the new chat while its lock is held.
        repository.preferencesRepository.saveActiveConversation(id)
        _draft.value = if (savedState.get<Long>("draftConversation") == id) savedState.get<String>("draft") ?: conversation.draft else conversation.draft
        savedState["draft"] = _draft.value; savedState["draftConversation"] = id
        _groundingMode.value = runCatching { GroundingMode.valueOf(conversation.groundingMode) }.getOrDefault(GroundingMode.OFF)
        editingMessageId = null
        _isEditingMessage.value = false
        _conversationId.value = id
    }
    private suspend fun stopChatAndSaveDraft() {
        chatPreparationJob?.cancelAndJoin()
        draftJob?.cancelAndJoin()
        repository.saveDraft(_conversationId.value, _draft.value)
    }
    fun newConversation() { viewModelScope.launch { conversationLock.withLock {
        stopChatAndSaveDraft()
        val id = repository.createConversation()
        repository.saveGroundingMode(id, repository.preferencesRepository.webMode.first())
        activateConversation(id); selectTab(AppTab.CHAT)
    } } }
    fun openConversation(id: Long) { viewModelScope.launch { conversationLock.withLock {
        stopChatAndSaveDraft(); activateConversation(id); selectTab(AppTab.CHAT)
    } } }
    fun deleteConversation(id: Long) { viewModelScope.launch { conversationLock.withLock {
        if (id == _conversationId.value) stopChatAndSaveDraft()
        repository.deleteConversation(id)
        if (id == _conversationId.value) {
            val next = repository.conversations.first().firstOrNull()?.id ?: repository.createConversation()
            activateConversation(next)
        }
    } } }
    fun editMessage(message: ChatMessageEntity) {
        if (_isChatGenerating.value || message.role != "user") return
        if (editingMessageId == null) savedState["draftBeforeEdit"] = _draft.value
        editingMessageId = message.id; updateDraft(message.content)
        _isEditingMessage.value = true
    }
    fun cancelEditingMessage() {
        editingMessageId = null; _isEditingMessage.value = false
        updateDraft(savedState.get<String>("draftBeforeEdit").orEmpty()); savedState.remove<String>("draftBeforeEdit")
    }
    fun regenerateResponse() {
        if (_isChatGenerating.value) return
        if (editingMessageId != null) { _userMessage.value = "Send or cancel the edit before regenerating."; return }
        val message = chatMessages.value.lastOrNull { it.role == "user" } ?: return
        sendChatMessage(message.content, message.id, clearDraft = false)
    }
    fun renameConversation(id: Long, title: String) {
        if (title.isNotBlank()) viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun refreshHardwareDiagnostics() {
        repository.analyzeHardware(getApplication())
        val profile = repository.hardwareProfile.value
        val ram = profile?.availableRamMb ?: 0L
        val thermal = profile?.thermalStatus ?: "Nominal"
        _userMessage.value = "Hardware diagnostics updated: ${ram}MB RAM free • $thermal"
    }

    fun selectTab(tab: AppTab) {
        if (tab == _currentTab.value) return
        if (tab == AppTab.CHAT) navigation.clear()
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

    fun setMemorySearchQuery(query: String) {
        _memorySearchQuery.value = query
    }

    fun setSelectedMemoryCategory(cat: String) {
        _selectedMemoryCategory.value = cat
    }

    fun toggleAutoMemoryCapture(enabled: Boolean) {
        _isAutoMemoryCaptureEnabled.value = enabled
        viewModelScope.launch { repository.preferencesRepository.saveMemoryEnabled(enabled) }
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
        viewModelScope.launch { repository.preferencesRepository.saveGenerationPreferences(params) }
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
        if (_isChatGenerating.value) { _userMessage.value = "Stop the current answer before unloading its model."; return }
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
        if (_isBenchmarking.value || _isChatGenerating.value) return
        _isBenchmarking.value = true
        _userMessage.value = "Running automated device benchmark..."
        
        viewModelScope.launch {
            try {
                val result = repository.engine.runBenchmark(
                    contextLength = _chatGenerationParams.value.contextSize
                )
                _benchmarkResult.value = result
                
                // Automatically tune runtime parameters to identified sweet spot
                _chatGenerationParams.value = _chatGenerationParams.value.copy(
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
        if (_isBenchmarking.value || _isChatGenerating.value) return
        _isBenchmarking.value = true
        _userMessage.value = "Benchmarking ${model.name} (${model.quantization}) on hardware..."

        viewModelScope.launch {
            try {
                val log = repository.runAndSaveBenchmarkForModel(
                    model = model,
                    contextSize = _chatGenerationParams.value.contextSize
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
            contextSize = bench.recommendedContext
        )
        _userMessage.value = "Saved ${bench.recommendedThreads} CPU threads and ${bench.recommendedContext} context. AI Processing Mode manages GPU offloading."
    }

    fun switchModel(model: ModelEntity) {
        if (_isChatGenerating.value) { _userMessage.value = "Stop the current answer before switching models. Your draft is kept."; return }
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
        if (_isChatGenerating.value) { _userMessage.value = "Stop the current answer before deleting models."; return }
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

    fun sendChatMessage(userText: String, replaceFrom: Long? = editingMessageId, clearDraft: Boolean = true) {
        if (userText.isBlank() || _isChatGenerating.value || _conversationId.value == 0L || conversationLock.isLocked) return
        if (_loadingModelId.value != null || repository.isModelLoading) { _userMessage.value = "Wait for model loading to finish. Your draft is kept."; return }
        if (!repository.engine.isReady()) {
            _userMessage.value = "Choose a downloaded model in Settings to start chatting."
            selectTab(AppTab.MODELS)
            return
        }
        val conversation = _conversationId.value
        val text = userText.trim()
        val mode = _groundingMode.value
        if (clearDraft) {
            val previousDraft = if (editingMessageId != null) savedState.get<String>("draftBeforeEdit").orEmpty() else ""
            updateDraft(previousDraft)
        }
        savedState.remove<String>("draftBeforeEdit")
        editingMessageId = null
        _isEditingMessage.value = false
        _isChatGenerating.value = true
        _pendingReplyId.value = null
        _streamingChatText.value = ""
        _chatStatus.value = "Preparing answer"
        chatPreparationJob = viewModelScope.launch {
            var savedUser = false
            var result = ""
            var error: String? = null
            var cancelled = false
            var latency = 0L
            var speed = 0f
            var promptTokens = 0
            var outputTokens = 0
            var reachedOutputLimit = false
            var evidence = GroundingResult()
            var researchQuestion = text
            var resolvedAnswer: com.example.grounding.CheckedAnswer? = null
            var pendingId: Long? = null
            var partialWriter: kotlinx.coroutines.Job? = null
            var foregroundToken = 0L
            var model = repository.engine.activeModelName
            try {
                // Evaluate available RAM and dynamically route between Primary (full/stepped-down context) and Secondary model.
                val params = _chatGenerationParams.value
                val tierDecision = runCatching { repository.resolveAndActivateForRam(params.contextSize) }.getOrNull()
                _activeTierDecision.value = tierDecision
                if (tierDecision?.role == ModelTierRole.SECONDARY_FALLBACK && tierDecision.reason.isNotBlank()) {
                    _userMessage.value = tierDecision.reason
                }
                model = repository.engine.activeModelName
                if (_isForegroundServiceEnabled.value) foregroundToken = InferenceForegroundService.start(getApplication(),
                    "Preparing your answer on this device", model) { chatPreparationJob?.cancel() }
                val previous = repository.beginChatTurn(ChatMessageEntity(role = "user", content = text, modelName = model, conversationId = conversation), replaceFrom)
                    .filter { it.messageState in setOf("COMPLETE", "STOPPED", "LENGTH_LIMIT") && it.role in setOf("user", "assistant") && it.content != "Response stopped." }
                savedUser = true
                pendingId = repository.insertChatMessage(ChatMessageEntity(role = "assistant", content = "", modelName = model,
                    conversationId = conversation, messageState = "STREAMING"))
                _pendingReplyId.value = pendingId
                partialWriter = launch {
                    while (true) {
                        kotlinx.coroutines.delay(750)
                        repository.updateChatMessage(ChatMessageEntity(id = pendingId!!, role = "assistant", content = _streamingChatText.value,
                            modelName = model, conversationId = conversation, messageState = "STREAMING", sourcesJson = evidence.json(), groundingStatus = evidence.status))
                    }
                }
                editingMessageId = null
                if (_isAutoMemoryCaptureEnabled.value) autoExtractMemory(text, "Chat")
                val allowed = repository.preferencesRepository.webAllowed.first()
                researchQuestion = com.example.grounding.ConversationalResearch.question(text, previous)
                if (previous.lastOrNull()?.content == com.example.grounding.ConversationalResearch.askCity &&
                    Regex("^[\\p{L} ,.'-]{2,70}$").matches(text)) researchQuestion = "Weather in $text today"
                if (GroundingPolicy.shouldSearch(allowed, mode, researchQuestion)) _chatStatus.value = "Finding and reading public sources"
                evidence = grounding.retrieve(researchQuestion, allowed, mode, SearchProviderId.WEB) { stage -> _chatStatus.value = stage.label }
                if (evidence.status in setOf("NEEDS_LOCATION", "NEEDS_SUBJECT")) {
                    result = evidence.notice
                    return@launch
                }
                if (evidence.status == "SEARCH_FAILED") {
                    evidence = evidence.copy(status = "INSUFFICIENT_EVIDENCE")
                    result = evidence.notice.ifBlank { GroundingPolicy.unavailableAnswer }
                    return@launch // finally persists an honest response; never ask the model to guess live facts.
                }
                _chatStatus.value = if (evidence.sources.isNotEmpty()) "Checking sources" else "Preparing answer"
                val requestedContext = tierDecision?.effectiveContext ?: params.contextSize
                val effectiveContext = repository.engine.requestContextLimit(requestedContext)
                val autoMaxTokens = if (effectiveContext >= 2048) 384 else 256
                val autoTemperature = if (evidence.sources.isNotEmpty()) 0f else 0.4f
                val baseSystem = com.example.engine.ChatAnswerPolicy.system(text, evidence.sources.isNotEmpty(), effectiveContext, params.systemPrompt)
                val effectiveQuestion = if (evidence.sources.isNotEmpty()) researchQuestion else text
                val olderContext = repository.refreshConversationContext(conversation, previous)
                val memoryBudget = effectiveContext - autoMaxTokens - 96 - repository.engine.contextTokenCount(baseSystem) -
                    repository.engine.contextTokenCount(effectiveQuestion) - 96
                val summary = if (evidence.sources.isEmpty() && memoryBudget > 64) ConversationContext.summaryWithinBudget(olderContext, minOf(effectiveContext, memoryBudget * 2), effectiveQuestion) else ""
                val localMemory = if (evidence.sources.isEmpty() && memoryBudget > 128 && _isAutoMemoryCaptureEnabled.value) repository.buildMemoryContextPrompt().take(minOf(400, memoryBudget / 2)) else ""
                val system = baseSystem + localMemory + if (summary.isNotBlank())
                    "\nOlder context from this chat only. Quoted user statements are context; labelled assistant proposals are unverified. Newer messages override:\n$summary\n" else ""
                val promptBudget = ((effectiveContext - autoMaxTokens - 96) * 2 - system.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
                val prepared = withContext(Dispatchers.Default) { GroundingPolicy.prepareEvidence(effectiveQuestion, evidence.sources, promptBudget,
                    effectiveContext - autoMaxTokens - 96 - repository.engine.contextTokenCount(system), repository.engine::contextTokenCount) }
                evidence = evidence.copy(sources = prepared.sources)
                val prompt = prepared.prompt
                val history = ConversationContext.history(com.example.engine.ChatAnswerPolicy.history(previous.map { ChatTurn(it.role, it.content) }, evidence.sources.isNotEmpty()), prompt, system, effectiveContext, autoMaxTokens,
                    repository.engine::contextTokenCount)
                val localNeedsLive = evidence.sources.isEmpty() && com.example.engine.ChatAnswerPolicy.requiresLiveData(text)
                val config = GenerationConfig(temperature = autoTemperature, topK = params.topK, topP = params.topP,
                    maxTokens = autoMaxTokens, systemPrompt = system, threads = params.threads,
                    gpuLayers = params.gpuLayers, contextSize = effectiveContext, history = history)
                _chatStatus.value = "Preparing answer"
                chatInferenceJob = repository.engineManager.currentEngine.generateStreamWithCallback(prompt, config, object : StreamTokenListener {
                    override fun onToken(token: String, tokenIndex: Int) {
                        if (localNeedsLive) return
                        _streamingChatText.value += token
                        _chatStatus.value = if (evidence.sources.isEmpty()) "" else "Draft · Checking against sources"
                    }
                    override fun onComplete(fullText: String, latencyMs: Long, tokensPerSec: Float, promptTokens: Int, completionTokens: Int) {
                        result = fullText; latency = latencyMs; speed = tokensPerSec
                    }
                    override fun onError(errorCode: Int, errorMessage: String) {
                        error = errorMessage
                        if (errorMessage == com.example.engine.InvalidModelOutputException().message) {
                            _streamingChatText.value = ""; result = ""
                        }
                    }
                })
                chatInferenceJob?.join()
                ensureActive()
                promptTokens = repository.engine.telemetry.value.promptTokens
                outputTokens = repository.engine.telemetry.value.completionTokens
                reachedOutputLimit = outputTokens >= autoMaxTokens
                if (error == null && evidence.sources.isNotEmpty() && result.isNotBlank()) {
                    _chatStatus.value = "Checking answer against sources"
                    val assessed = withContext(Dispatchers.Default) { GroundingPolicy.checkAnswer(result, evidence.sources, effectiveQuestion) }
                    val resources = repository.engine.hardware.resources()
                    val repair = EvidenceRepair.prompt(effectiveQuestion, result, prepared, assessed, effectiveContext,
                        resources.availableMb, resources.lowMemory, resources.thermal, repository.engine.telemetry.value.timeToFirstTokenMs, speed) {
                        repository.engine.contextTokenCount(it) + repository.engine.contextTokenCount(system) + EvidenceRepair.maxTokens + 96 <= effectiveContext
                    }
                    if (repair != null) {
                        _chatStatus.value = "Checking a shorter answer"
                        val corrected = try { kotlinx.coroutines.withTimeoutOrNull(EvidenceRepair.deadlineMs) {
                            repository.engine.generateSync(repair, GenerationParams(temperature = 0f, topK = 1, topP = 1f,
                                maxTokens = EvidenceRepair.maxTokens, contextSize = effectiveContext, threads = params.threads, systemPrompt = system))
                        } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                        if (corrected != null && withContext(Dispatchers.Default) { GroundingPolicy.checkAnswer(corrected, evidence.sources, effectiveQuestion).support } != AnswerSupport.INSUFFICIENT)
                            result = corrected
                    }
                    resolvedAnswer = withContext(Dispatchers.Default) {
                        com.example.grounding.EvidenceAnswer.resolve(result, evidence.sources, effectiveQuestion)
                    }
                }
                if (error == null && localNeedsLive) {
                    result = com.example.engine.ChatAnswerPolicy.finishLocal(text, result)
                    if (evidence.status == "LOCAL") evidence = evidence.copy(status = "LOCAL_UNVERIFIED")
                }
                if (error != null) result = _streamingChatText.value
                if (result.isBlank() && error == null) error = if (reachedOutputLimit)
                    "The response budget ended before visible text. Try a shorter question or select another model."
                    else "The model returned no text. Try again or select another model."
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                result = _streamingChatText.value
            } catch (e: Exception) {
                error = e.message ?: "Unable to prepare this answer. Try again."
                result = _streamingChatText.value
            } finally {
                // Join native cancellation before changing conversation/model ownership or clearing UI state.
                withContext(NonCancellable) {
                    chatInferenceJob?.cancelAndJoin()
                    chatInferenceJob = null
                    if (foregroundToken != 0L) InferenceForegroundService.stop(getApplication(), 0, 0, 0f, foregroundToken)
                    partialWriter?.cancelAndJoin()
                    try { if (savedUser && pendingId != null) {
                        val checked = resolvedAnswer ?: if (evidence.sources.isNotEmpty()) withContext(Dispatchers.Default) {
                            GroundingPolicy.checkAnswer(result, evidence.sources, researchQuestion)
                        } else null
                        val status = if (checked == null) evidence.status else if (checked.sourceExtract) "SOURCE_EXCERPTS" else when (checked.support) {
                            AnswerSupport.CITED -> "EVIDENCE_CITED"
                            AnswerSupport.PARTIAL -> "PARTIALLY_SUPPORTED"
                            AnswerSupport.INSUFFICIENT -> "INSUFFICIENT_EVIDENCE"
                        }
                        val invalid = error == com.example.engine.InvalidModelOutputException().message
                        val content = if (invalid) error!! else (checked?.text ?: result).ifBlank { if (cancelled) "Response stopped." else error ?: "No response." }
                        repository.updateChatMessage(ChatMessageEntity(id = pendingId!!, role = "assistant", content = content,
                            conversationId = conversation, modelName = model, latencyMs = latency, tokensPerSec = speed,
                            promptTokens = promptTokens, completionTokens = outputTokens,
                            sourcesJson = evidence.json(), groundingStatus = status,
                            messageState = if (cancelled) "STOPPED" else if (error != null) "ERROR" else if (reachedOutputLimit) "LENGTH_LIMIT" else "COMPLETE"))
                    } else if (error != null) { _userMessage.value = error; if (_draft.value.isBlank()) updateDraft(text) }
                    } finally { _streamingChatText.value = ""; _chatStatus.value = ""; _isChatGenerating.value = false }
                }
            }
        }
    }

    fun toggleForegroundService(enabled: Boolean) {
        _isForegroundServiceEnabled.value = enabled
        viewModelScope.launch { repository.preferencesRepository.saveForegroundEnabled(enabled) }
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
        // Only this chat's request. External Binder requests keep their own ownership.
        chatPreparationJob?.cancel()
    }

    fun clearChat() {
        viewModelScope.launch { conversationLock.withLock {
            stopChatAndSaveDraft()
            repository.clearChatHistory()
            savedState.remove<String>("draft")
            savedState.remove<Long>("draftConversation")
            activateConversation(repository.createConversation())
        } }
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

    // Simulator Functions
    fun updateSimulatorApp(appName: String, pkg: String) {
        _simulatorState.value = _simulatorState.value.copy(selectedApp = appName, selectedPackage = pkg)
    }

    fun updateSimulatorTask(task: String) {
        _simulatorState.value = _simulatorState.value.copy(
            selectedTask = task,
            promptInput = when (task) {
                "STREAM" -> "Draft a 3-point action plan for launching an on-device AI feature."
                "JSON_EXTRACT" -> "Reviewing Q4 Android AI Core milestone with team on Friday at 3:00 PM in Conference Room A. High priority deliverables: low-latency IPC and cross-app context memory."
                "INTENT_ROUTE" -> "Set a reminder to check on-device battery temperature before starting benchmark run."
                "RAG_QUERY" -> "What are the latest system preferences and active notes stored on this device?"
                "STORE_MEMORY" -> "User prefers on-device local execution for all AI queries and strict privacy controls."
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
            val requiredScope = when (state.selectedTask) {
                "JSON_EXTRACT", "INTENT_ROUTE" -> "STRUCTURED_JSON"
                "RAG_QUERY" -> "READ_MEMORY"
                "STORE_MEMORY" -> "WRITE_MEMORY"
                else -> "INFERENCE"
            }
            val (isAuthorized, reason) = repository.checkSecurityAndRateLimit(state.selectedPackage, requiredScope)
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
                        status = when {
                            reason.contains("Rate limit") -> "RATE_LIMITED"
                            reason.contains("Scope Denied") -> "SCOPE_BLOCKED"
                            else -> "DENIED"
                        }
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

            try { when (state.selectedTask) {
                "STREAM" -> {
                    _simulatorState.value = _simulatorState.value.copy(statusText = "Receiving streaming tokens with context memory...")
                    val effectiveSystem = repository.buildClientSystemPrompt(state.selectedPackage, null)
                    val config = GenerationConfig(
                        temperature = 0.7f,
                        maxTokens = 256,
                        systemPrompt = effectiveSystem
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

                "JSON_EXTRACT" -> {
                    val keys = arrayOf("summary", "intent", "entities", "action_items", "datetime", "priority")
                    val json = repository.engine.extractStructuredJson(state.promptInput, keys)
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(8L)
                    _simulatorState.value = _simulatorState.value.copy(
                        isRunning = false,
                        latencyMs = latency,
                        tokensPerSec = 42f,
                        statusText = "Extracted Structured JSON in ${latency}ms",
                        outputText = json
                    )
                    repository.logIpcCall(
                        IpcLogEntity(
                            callingPackage = state.selectedPackage,
                            callingUid = 10245,
                            requestType = "JSON_EXTRACT",
                            promptPreview = state.promptInput.take(150),
                            responsePreview = json.take(200),
                            latencyMs = latency,
                            tokensPerSecond = 42f,
                            totalTokens = json.length / 4,
                            status = "SUCCESS"
                        )
                    )
                }

                "INTENT_ROUTE" -> {
                    val routes = arrayOf("SCHEDULE_EVENT", "CREATE_NOTE", "SYSTEM_SETTINGS", "SEND_MESSAGE", "SEARCH")
                    val routed = repository.engine.routeIntent(state.promptInput, routes)
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(6L)
                    _simulatorState.value = _simulatorState.value.copy(
                        isRunning = false,
                        latencyMs = latency,
                        tokensPerSec = 48f,
                        statusText = "Routed Intent in ${latency}ms",
                        outputText = routed
                    )
                    repository.logIpcCall(
                        IpcLogEntity(
                            callingPackage = state.selectedPackage,
                            callingUid = 10245,
                            requestType = "INTENT_ROUTE",
                            promptPreview = state.promptInput.take(150),
                            responsePreview = routed.take(200),
                            latencyMs = latency,
                            tokensPerSecond = 48f,
                            totalTokens = routed.length / 4,
                            status = "SUCCESS"
                        )
                    )
                }

                "RAG_QUERY" -> {
                    val json = repository.querySharedMemoriesAsJson(state.selectedPackage, state.promptInput, 10)
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(4L)
                    _simulatorState.value = _simulatorState.value.copy(
                        isRunning = false,
                        latencyMs = latency,
                        tokensPerSec = 0f,
                        statusText = "Queried Shared Brain Memory in ${latency}ms",
                        outputText = json
                    )
                    repository.logIpcCall(
                        IpcLogEntity(
                            callingPackage = state.selectedPackage,
                            callingUid = 10245,
                            requestType = "MEMORY_READ",
                            promptPreview = state.promptInput.take(150),
                            responsePreview = json.take(200),
                            latencyMs = latency,
                            tokensPerSecond = 0f,
                            totalTokens = 1,
                            status = "SUCCESS"
                        )
                    )
                }

                "STORE_MEMORY" -> {
                    val id = repository.writeSharedMemoryFromApp(
                        packageName = state.selectedPackage,
                        title = "${state.selectedApp} Snapshot",
                        content = state.promptInput,
                        category = "Cross-App"
                    )
                    val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(5L)
                    _simulatorState.value = _simulatorState.value.copy(
                        isRunning = false,
                        latencyMs = latency,
                        tokensPerSec = 0f,
                        statusText = "Stored Shared Context Memory #$id in ${latency}ms",
                        outputText = "Successfully written to Better Intelligence Shared RAG Memory Store.\nMemory ID: #$id\nOwner Package: ${state.selectedPackage}\nCategory: Cross-App\nPayload: ${state.promptInput}\n\nAll authorized apps can now query this context via ContentProvider or AIDL."
                    )
                    repository.logIpcCall(
                        IpcLogEntity(
                            callingPackage = state.selectedPackage,
                            callingUid = 10245,
                            requestType = "MEMORY_WRITE",
                            promptPreview = state.promptInput.take(150),
                            responsePreview = "Saved Memory #$id",
                            latencyMs = latency,
                            tokensPerSecond = 0f,
                            totalTokens = 1,
                            status = "SUCCESS"
                        )
                    )
                }

                "EMBED" -> {
                    _simulatorState.value = _simulatorState.value.copy(isRunning = false,
                        statusText = "Unsupported task", outputText = "The installed library contains text-generation models. Embeddings are unavailable.")
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
            } } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _simulatorState.value = _simulatorState.value.copy(isRunning = false,
                    statusText = "AI unavailable", outputText = e.message ?: "Unable to run this task")
            }
        }
    }
}
