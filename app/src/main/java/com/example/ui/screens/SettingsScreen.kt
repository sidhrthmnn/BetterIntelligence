package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
private fun SettingsRow(title: String, subtitle: String, icon: ImageVector? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("setting_$title")
    )
}

@Composable
fun SettingsScreen(viewModel: AiCoreViewModel) {
    val active by viewModel.activeModel.collectAsState()
    val secondary by viewModel.secondaryModel.collectAsState()
    val allowed by viewModel.webAllowed.collectAsState()
    val appearance by viewModel.appearance.collectAsState()
    val modelSubtitle = buildString {
        append(active?.name?.substringBefore(" · ") ?: "Choose, download and manage storage")
        secondary?.takeIf { it.id != active?.id }?.let {
            append(" · Fallback: ${it.name.substringBefore(" · ")}")
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
        item { SettingsSection("AI & models") }
        item { SettingsRow("Models", modelSubtitle, Icons.Default.Memory) { viewModel.selectTab(AppTab.MODELS) } }
        item { SettingsRow("Connected apps", "Manage access to local AI", Icons.Default.Apps) { viewModel.selectTab(AppTab.CONNECT) } }
        item { SettingsRow("Web & grounding", if (allowed) "Enabled for chat · Connected apps stay local" else "Off · Fully local answers", Icons.Default.Language) { viewModel.selectTab(AppTab.WEB) } }
        item { SettingsSection("Personalisation") }
        item { SettingsRow("Appearance", appearance.lowercase().replaceFirstChar { it.uppercase() }, Icons.Default.Palette) { viewModel.selectTab(AppTab.APPEARANCE) } }
        item { SettingsRow("Memory", "Choose what your chat remembers", Icons.Default.Psychology) { viewModel.selectTab(AppTab.MEMORY) } }
        item { SettingsSection("Privacy & about") }
        item { SettingsRow("Privacy & data", "Conversations, network and local storage", Icons.Default.Lock) { viewModel.selectTab(AppTab.PRIVACY) } }
        item { SettingsRow("Advanced & about", "Response settings, processing, diagnostics and licences", Icons.Default.Tune) { viewModel.selectTab(AppTab.ADVANCED) } }
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
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (appearance == value) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.setAppearance(value) }
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(appearance == value, { viewModel.setAppearance(value) }, modifier = Modifier.testTag("appearance_$value"))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text("Text follows your Android font and display size settings.", Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSettingsScreen(viewModel: AiCoreViewModel) {
    val allowed by viewModel.webAllowed.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("web_settings_list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Allow web search", style = MaterialTheme.typography.titleMedium); Text("Off by default", style = MaterialTheme.typography.bodySmall) }
                Switch(allowed, { viewModel.setWebAllowed(it) }, modifier = Modifier.testTag("allow_web"))
            }
            Spacer(Modifier.height(12.dp))
            Text("Research sends focused public questions to Exa's free search service to find relevant links. BI reads the publisher pages and writes the answer using your on-device model. Exa and visited sites receive your question or requested URL and connection information, including your IP address. Stored private history, memories and connected-app requests are not sent. A follow-up can reuse the public topic already searched in this chat. Do not include secrets or sensitive information in a web-enabled question. Enable this permission once after reading the updated disclosure.")
        }
        item {
            Text("Always automatic", style = MaterialTheme.typography.titleMedium)
            Text("Current facts, casting, movie reviews, prices, release dates and explicit source requests trigger research. Other public questions start locally; if the model says it cannot answer, BI makes one research attempt and answers from the sources. Reasoning, writing, calculations and personal conversation stay local. The switch above controls web permission.", style = MaterialTheme.typography.bodySmall)
            Text("No API key or account is required. Exa's free service has usage limits and may be unavailable. BI uses no paid fallback or other search engine. You can also give BI a public HTTPS source to read directly.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Find, read and cite", style = MaterialTheme.typography.titleMedium)
            Text("Search highlights and summaries only help find pages; they are not answer evidence. BI reads publisher text, keeps source dates when available, and cites the passages used. No cloud answer model or weather API is used. Public sites can block access.")
            Text("Search can be blocked or unavailable. Pages requiring sign-in, scripts, CAPTCHA or unsupported file formats are skipped. Sources can be incomplete or wrong; citations show where text came from, not a guarantee of accuracy.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Questions and retrieved page text are excluded from app logs. Temporary public evidence is held in memory for up to 15 minutes; weather expires after one minute and changing facts after five. Sources attached to an answer stay with that conversation until you delete it.", style = MaterialTheme.typography.bodySmall)
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
        item { Text("AI inference stays on this device with no automatic cloud fallback. Internet is used for model discovery and downloads. Optional web research uses Exa's key-free search to find links, then reads publisher websites directly. Connected-app inference, including TypeRight AI Polish, never invokes search.") }
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
    val telemetry by viewModel.telemetry.collectAsState()
    val acceleration by viewModel.accelerationCapabilities.collectAsState()
    val hardware by viewModel.hardwareProfile.collectAsState()
    val models by viewModel.allModels.collectAsState()
    val secondary by viewModel.secondaryModel.collectAsState()
    val logs by viewModel.performanceLogs.collectAsState()
    val quantization by viewModel.selectedQuantizationFilter.collectAsState()
    val sort by viewModel.selectedSortMetric.collectAsState()
    val benchmarking by viewModel.isBenchmarking.collectAsState()
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("AI Processing Mode", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth().testTag("processing_AUTO")
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.AutoMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Automatic", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (com.example.BuildConfig.QUALCOMM_NPU_ENABLED) "Compares compatible CPU, GPU and Qualcomm NPU paths using measured correctness, speed, RAM and thermal headroom. Compiled NPU models must match this phone."
                            else "Automatically benchmarks and selects the optimal CPU or GPU backend, thread count, and layer offload based on live RAM and thermal headroom.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            TextButton(onClick = { viewModel.resetBackendDecisions() }, enabled = !telemetry.isRunning) {
                Text(if (com.example.BuildConfig.QUALCOMM_NPU_ENABLED) "Reset backend measurements and retry accelerators" else "Reset backend measurements and retry GPU")
            }
            if (com.example.BuildConfig.QUALCOMM_NPU_ENABLED) {
                Text(if (acceleration.npuAvailable) "Qualcomm NPU validation passed for this model. Automatic compares its performance with other compatible processors."
                    else acceleration.npuReason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("qualcomm_npu_status"))
            }
        }
        item {
            Text("Response preferences", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth().testTag("response_preferences_AUTO")
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Automatic", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Context window (up to 2048 tokens), response length, and factual vs. creative temperature are tuned automatically for each question and available RAM.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            secondary?.let { "Standby secondary model: ${it.name.substringBefore(" · ")} · Background generation active." }
                                ?: "Automatic RAM context step-down and background generation are active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
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
