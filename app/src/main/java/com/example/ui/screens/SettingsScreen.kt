package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.engine.ProcessingMode
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import com.example.ui.components.HardwareDiagnosticCard
import com.example.ui.components.HardwarePerformanceComparisonCard
import java.text.DateFormat
import java.util.Date

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("setting_$title"))
}

@Composable
fun SettingsScreen(viewModel: AiCoreViewModel) {
    val active by viewModel.activeModel.collectAsState()
    val allowed by viewModel.webAllowed.collectAsState()
    val appearance by viewModel.appearance.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
        item { SettingsSection("AI & models") }
        item { SettingsRow("Models", active?.name?.substringBefore(" · ") ?: "Choose, download and manage storage") { viewModel.selectTab(AppTab.MODELS) } }
        item { SettingsRow("Connected apps", "Manage access to local AI") { viewModel.selectTab(AppTab.CONNECT) } }
        item { SettingsRow("Web & grounding", if (allowed) "Enabled for chat · Connected apps stay local" else "Off · Fully local answers") { viewModel.selectTab(AppTab.WEB) } }
        item { SettingsSection("Personalisation") }
        item { SettingsRow("Appearance", appearance.lowercase().replaceFirstChar { it.uppercase() }) { viewModel.selectTab(AppTab.APPEARANCE) } }
        item { SettingsRow("Memory", "Choose what your chat remembers") { viewModel.selectTab(AppTab.MEMORY) } }
        item { SettingsSection("Privacy & about") }
        item { SettingsRow("Privacy & data", "Conversations, network and local storage") { viewModel.selectTab(AppTab.PRIVACY) } }
        item { SettingsRow("Advanced & about", "Response settings, processing, diagnostics and licences") { viewModel.selectTab(AppTab.ADVANCED) } }
    }
}

@Composable
private fun SettingsSection(text: String) { Text(text, Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }

@Composable
fun HistoryScreen(viewModel: AiCoreViewModel) {
    val conversations by viewModel.conversations.collectAsState()
    var deleting by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<com.example.data.ConversationEntity?>(null) }
    var name by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item { Text("Your conversations stay on this device.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(conversations, key = { it.id }) { conversation ->
            ListItem(headlineContent = { Text(conversation.title) }, supportingContent = {
                Text((if (conversation.draft.isNotEmpty()) "Draft · " else "") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(conversation.updatedAt)))
            }, trailingContent = { Row {
                IconButton(onClick = { renaming = conversation; name = conversation.title }) { Icon(Icons.Default.Edit, "Rename ${conversation.title}") }
                IconButton(onClick = { deleting = conversation.id }) { Icon(Icons.Default.DeleteOutline, "Delete ${conversation.title}") }
            } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.clickable { viewModel.openConversation(conversation.id) }.testTag("conversation_${conversation.id}"))
        }
    }
    renaming?.let { conversation -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename conversation") },
        text = { OutlinedTextField(name, { name = it.take(100) }, label = { Text("Conversation name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { viewModel.renameConversation(conversation.id, name); renaming = null }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }) }
    deleting?.let { id -> DeleteDialog("Delete conversation?", "This removes its messages and draft from this device.", { deleting = null }) {
        viewModel.deleteConversation(id); deleting = null
    } }
}

@Composable
fun AppearanceScreen(viewModel: AiCoreViewModel) {
    val appearance by viewModel.appearance.collectAsState()
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Make yourself at home", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        listOf("LIGHT" to "Light", "DARK" to "Dark", "AMOLED" to "AMOLED black").forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().clickable { viewModel.setAppearance(value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(appearance == value, { viewModel.setAppearance(value) }, modifier = Modifier.testTag("appearance_$value"))
                Text(label)
            }
        }
        Text("Text follows your Android font and display size settings.", Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSettingsScreen(viewModel: AiCoreViewModel) {
    val allowed by viewModel.webAllowed.collectAsState()
    val mode by viewModel.defaultWebMode.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Allow web search", style = MaterialTheme.typography.titleMedium); Text("Off by default", style = MaterialTheme.typography.bodySmall) }
                Switch(allowed, { viewModel.setWebAllowed(it) }, modifier = Modifier.testTag("allow_web"))
            }
            Spacer(Modifier.height(12.dp))
            Text("Search sends terms from your current question to DuckDuckGo's public HTML page, then reads public websites directly. Those sites receive ordinary connection information, including your IP address. Chat history, saved memories and connected-app requests are not sent. Do not include passwords or sensitive information in a web-enabled question.")
        }
        item {
            Text("Default behaviour for new conversations", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("OFF" to "Off", "AUTO" to "Automatic", "ON" to "On").forEach { (value, label) ->
                    FilterChip(mode == value, { viewModel.setDefaultWebMode(value) }, enabled = allowed, label = { Text(label) })
                }
            }
            Text("Automatic searches questions about current facts or verification. You can change this in each conversation.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Find, read and cite", style = MaterialTheme.typography.titleMedium)
            Text("BI extracts and ranks page passages on this device, checks publisher restrictions and reported dates, and supplies cited evidence to your local model. It uses no search, weather or grounding API. Public sites can block automated access; then BI cannot verify an answer. Paste a public HTTPS link to read it directly.")
            Text("Search can be blocked or unavailable. Pages requiring sign-in, scripts, CAPTCHA or unsupported file formats are skipped. Sources can be incomplete or wrong; citations show where text came from, not a guarantee of accuracy.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Questions and retrieved page text are excluded from app logs. Temporary search data is held in memory for up to five minutes. Sources attached to an answer stay with that conversation until you delete it.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { viewModel.clearSearchData() }) { Text("Clear temporary search data") }
        }
    }
}

@Composable
fun MemoryScreen(viewModel: AiCoreViewModel) {
    val enabled by viewModel.isAutoMemoryCaptureEnabled.collectAsState()
    val memories by viewModel.allMemories.collectAsState()
    var clear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Context for each chat", style = MaterialTheme.typography.titleMedium)
            Text("Each conversation automatically keeps its own local history and compact context notes, including after restarting or changing models. Other chats and connected apps do not receive that context. Delete a conversation to remove it.")
            HorizontalDivider()
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Memory shared across chats", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(enabled, { viewModel.toggleAutoMemoryCapture(it) })
            }
            Text("Off by default. When enabled, explicit reminders and brief profile statements can be saved and used in chat. Connected apps need a separate memory permission. Web search never receives saved memories.", style = MaterialTheme.typography.bodyMedium)
        }
        items(memories, key = { it.id }) { memory ->
            Column {
                Text(memory.title, style = MaterialTheme.typography.titleMedium)
                Text(memory.content)
                Row { TextButton(onClick = { viewModel.toggleMemoryActive(memory) }) { Text(if (memory.isActive) "Pause" else "Use in chat") }
                    TextButton(onClick = { viewModel.deleteMemorySnippet(memory.id) }) { Text("Delete") } }
                HorizontalDivider()
            }
        }
        if (memories.isNotEmpty()) item { TextButton(onClick = { clear = true }) { Text("Delete all memories") } }
    }
    if (clear) DeleteDialog("Delete all memories?", "This removes every saved memory from this device.", { clear = false }) { viewModel.clearAllMemories(); clear = false }
}

@Composable
fun PrivacyScreen(viewModel: AiCoreViewModel) {
    var clear by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("You control your data", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Conversations, drafts, model files and app approvals are stored locally. Android backup may include settings and conversations according to your system backup preferences; model files and provider credentials are excluded.") }
        item { Text("AI inference stays on this device with no automatic cloud fallback. Internet is used for model discovery and downloads. Optional web research uses DuckDuckGo's public HTML page and reads publisher websites directly. Connected-app inference, including TypeRight AI Polish, never invokes search.") }
        item { Text("Deleting a model frees its storage and leaves your conversations intact. Revoking app access prevents new requests. Personal memory sharing has separate app permissions.") }
        item { TextButton(onClick = { viewModel.selectTab(AppTab.MODELS) }) { Text("Manage model storage") }
            TextButton(onClick = { viewModel.selectTab(AppTab.CONNECT) }) { Text("Manage app access") }
            TextButton(onClick = { viewModel.clearSearchData() }) { Text("Clear temporary search data") }
            TextButton(onClick = { clear = true }) { Text("Delete all conversations") }
            TextButton(onClick = { runCatching { uri.openUri("https://haveanidea-privacy-policy.ai.studio") } }) { Text("Privacy policy") }
        }
    }
    if (clear) DeleteDialog("Delete all conversations?", "All saved messages and drafts will be removed. Models, app approvals and memories are kept.", { clear = false }) { viewModel.clearChat(); clear = false }
}

@Composable
private fun DeleteDialog(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(confirm) { Text("Delete") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdvancedSettingsScreen(viewModel: AiCoreViewModel) {
    val mode by viewModel.processingMode.collectAsState()
    val caps by viewModel.accelerationCapabilities.collectAsState()
    val limit by viewModel.gpuLayerLimit.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val hardware by viewModel.hardwareProfile.collectAsState()
    val params by viewModel.chatGenerationParams.collectAsState()
    val models by viewModel.allModels.collectAsState()
    val logs by viewModel.performanceLogs.collectAsState()
    val quantization by viewModel.selectedQuantizationFilter.collectAsState()
    val sort by viewModel.selectedSortMetric.collectAsState()
    val benchmarking by viewModel.isBenchmarking.collectAsState()
    val background by viewModel.isForegroundServiceEnabled.collectAsState()
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("AI Processing Mode", style = MaterialTheme.typography.titleLarge)
            ProcessingMode.entries.forEach { option ->
                val enabled = when(option) { ProcessingMode.GPU -> caps.gpuAvailable; ProcessingMode.NPU -> caps.npuAvailable; else -> true }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == option, { viewModel.setProcessingMode(option) }, enabled = enabled, modifier = Modifier.testTag("processing_${option.name}"))
                    Column(Modifier.weight(1f)) {
                        Text(option.title)
                        if (!enabled) Text(if (option == ProcessingMode.NPU) caps.npuReason else caps.gpuReason, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Automatic measures compatible backends and caches a stable choice. Initial tuning can delay the first answer. CPU and GPU share your phone’s RAM.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { viewModel.resetBackendDecisions() }, enabled = !telemetry.isRunning) { Text("Reset backend measurements and retry GPU") }
            if (caps.gpuAvailable && !caps.gpuName.startsWith("LiteRT")) {
                Text("GPU layer limit")
                FlowRow { listOf(-1, 8, 16, 32).forEach { value -> FilterChip(limit == value, { viewModel.setProcessingMode(mode, value) }, label = { Text(if (value < 0) "Auto" else "$value") }) } }
            }
        }
        item {
            Text("Response preferences", style = MaterialTheme.typography.titleLarge)
            Text("Conversation context")
            FlowRow { listOf(512, 1024, 2048).forEach { size -> FilterChip(params.contextSize == size,
                { viewModel.updateGenerationParams(params.copy(contextSize = size, maxTokens = minOf(params.maxTokens, size / 4))) }, label = { Text("$size") }) } }
            Text("A smaller context reduces memory only when the model supports resizing. Fixed-context LiteRT packages keep their exported allocation; models with a larger working minimum are raised to that size for safe CPU fallback.", style = MaterialTheme.typography.bodySmall)
            Text("Response length: up to ${params.maxTokens} tokens")
            Slider(params.maxTokens.toFloat(), { viewModel.updateGenerationParams(params.copy(maxTokens = it.toInt())) }, valueRange = 64f..minOf(768, params.contextSize / 2).toFloat())
            Text("Creativity: ${"%.1f".format(params.temperature)}")
            Slider(params.temperature, { viewModel.updateGenerationParams(params.copy(temperature = it)) }, valueRange = 0f..1.5f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Continue chat in the background", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(background, { viewModel.toggleForegroundService(it) })
            }
            Text("Uses a foreground service and a bounded wake lock while an answer is running.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Hardware monitoring", style = MaterialTheme.typography.titleLarge)
            Text("${telemetry.modelName} · ${if (telemetry.isModelLoaded) telemetry.activeBackend else "No model loaded"}")
            Text("${"%.1f".format(telemetry.tokensPerSecond)} tokens/s · First token ${telemetry.timeToFirstTokenMs} ms")
            Text("${telemetry.activeThreads} CPU threads · ${telemetry.activeGpuLayers} GGUF GPU layers · ${telemetry.memoryUsageMb.toInt()} MB app PSS")
            Text("Thermal: ${telemetry.thermalState} · Backend init ${telemetry.backendInitializationMs} ms")
            Text(telemetry.backendStatus)
            if (telemetry.backendDecision.isNotBlank()) Text(telemetry.backendDecision, style = MaterialTheme.typography.bodySmall)
            if (telemetry.fallbackCount > 0) Text("Fallbacks: ${telemetry.fallbackCount} · ${telemetry.lastFallback}")
            Text("App PSS excludes some GPU/driver allocations. GPU memory is shared, not extra RAM.", style = MaterialTheme.typography.bodySmall)
        }
        item { HardwareDiagnosticCard(hardware, { viewModel.refreshHardwareDiagnostics() }) }
        item { HardwarePerformanceComparisonCard(logs, models, hardware, quantization, sort, benchmarking,
            { viewModel.setQuantizationFilter(it) }, { viewModel.setSortMetric(it) }, { viewModel.runBenchmarkForModel(it) },
            { viewModel.deletePerformanceLog(it) }, { viewModel.clearAllPerformanceLogs() }) }
        item {
            TextButton(onClick = { viewModel.unloadActiveModel() }, enabled = !telemetry.isRunning) { Text("Unload model from memory") }
            TextButton(onClick = { viewModel.clearIpcLogs() }) { Text("Clear app access logs") }
            Text("Better Intelligence ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · API v2", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { runCatching { uri.openUri("https://github.com/sidhrthmnn/BetterIntelligence/blob/main/docs/THIRD_PARTY_NOTICES.md") } }) { Text("Open-source licences and model terms") }
        }
    }
}
