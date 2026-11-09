package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import com.example.ui.components.*
import java.text.DateFormat
import java.util.Date

@Composable
private fun SettingsRow(title: String, subtitle: String, icon: ImageVector? = null, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("setting_$title"),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
            supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
            leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
            trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
    }
}

@Composable
fun SettingsScreen(viewModel: AiCoreViewModel) {
    val active by viewModel.activeModel.collectAsState()
    val allowed by viewModel.webAllowed.collectAsState()
    val appearance by viewModel.appearance.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsSection("Your AI") }
        item { SettingsRow("Optimise your AI", "Check device, set up models and view results", Icons.Default.AutoAwesome) { viewModel.recheckDevice(); viewModel.selectTab(AppTab.OPTIMISE) } }
        item { SettingsRow("Models", active?.name?.substringBefore(" · ") ?: "Find, download and manage models", Icons.Default.Memory) { viewModel.selectTab(AppTab.MODELS) } }
        item { DetailDisclosure("Models & Downloads", "Background downloads, mobile data, and notifications", Modifier.testTag("setting_Models_Downloads")) {
            ModelsDownloadsPreferences(viewModel)
        } }
        item { SettingsRow("Connected apps", "Choose which apps can use your AI", Icons.Default.Apps) { viewModel.selectTab(AppTab.CONNECT) } }
        item { SettingsRow("Web & grounding", if (allowed) "On · Answers with sources" else "Off · Local answers", Icons.Default.Language) { viewModel.selectTab(AppTab.WEB) } }
        item { SettingsSection("Personalise") }
        item { SettingsRow("Appearance", if (appearance == "LIGHT") "Light mode" else "Dark mode", Icons.Default.Palette) { viewModel.selectTab(AppTab.APPEARANCE) } }
        item { SettingsRow("Memory", "Chat context and saved memories", Icons.Default.Psychology) { viewModel.selectTab(AppTab.MEMORY) } }
        item { SettingsSection("Privacy & about") }
        item { SettingsRow("Privacy & data", "Your data, storage and permissions", Icons.Default.Lock) { viewModel.selectTab(AppTab.PRIVACY) } }
        item { SettingsRow("Advanced & about", "Processing, performance and app details", Icons.Default.Tune) { viewModel.selectTab(AppTab.ADVANCED) } }
    }
}

@Composable
fun ModelsDownloadsPreferences(viewModel: AiCoreViewModel) {
    var bgEnabled by remember { mutableStateOf(viewModel.backgroundDownloadsEnabled) }
    var wifiOnly by remember { mutableStateOf(viewModel.downloadsWifiOnly) }
    var mobileData by remember { mutableStateOf(viewModel.allowMobileData) }
    var autoResume by remember { mutableStateOf(viewModel.autoResumeDownloads) }
    var notifications by remember { mutableStateOf(viewModel.downloadNotificationsEnabled) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable background downloads", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = bgEnabled,
                onCheckedChange = {
                    bgEnabled = it
                    viewModel.setBackgroundDownloadsEnabled(it)
                },
                modifier = Modifier.testTag("pref_background_downloads")
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi only downloads", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    mobileData = !it
                    viewModel.setDownloadsWifiOnly(it)
                },
                modifier = Modifier.testTag("pref_wifi_only")
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Allow mobile data", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = mobileData,
                onCheckedChange = {
                    mobileData = it
                    wifiOnly = !it
                    viewModel.setAllowMobileData(it)
                },
                modifier = Modifier.testTag("pref_allow_mobile_data")
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic download resumption", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = autoResume,
                onCheckedChange = {
                    autoResume = it
                    viewModel.setAutoResumeDownloads(it)
                },
                modifier = Modifier.testTag("pref_auto_resume")
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Download notifications", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = notifications,
                onCheckedChange = {
                    notifications = it
                    viewModel.setDownloadNotificationsEnabled(it)
                },
                modifier = Modifier.testTag("pref_download_notifications")
            )
        }
    }
}

@Composable
private fun SettingsSection(text: String) { Text(text, Modifier.padding(top = 12.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
fun HistoryScreen(viewModel: AiCoreViewModel) {
    val conversations by viewModel.conversations.collectAsState()
    var deleting by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<com.example.data.ConversationEntity?>(null) }
    var name by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().testTag("history_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Saved on this device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (conversations.isEmpty()) item { ScreenCard { Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
            Text("Start a chat and it will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { viewModel.selectTab(AppTab.CHAT) }) { Text("Open chat") } } }
        items(conversations, key = { it.id }) { conversation ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { viewModel.openConversation(conversation.id) }.testTag("conversation_${conversation.id}"),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                ListItem(headlineContent = { Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text((if (conversation.draft.isNotEmpty()) "Draft · " else "") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(conversation.updatedAt)), style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { Row {
                        IconButton(onClick = { renaming = conversation; name = conversation.title }) { Icon(Icons.Default.Edit, "Rename ${conversation.title}", Modifier.size(19.dp)) }
                        IconButton(onClick = { deleting = conversation.id }) { Icon(Icons.Default.DeleteOutline, "Delete ${conversation.title}", Modifier.size(19.dp)) }
                    } }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
            }
        }
    }
    renaming?.let { conversation -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename conversation") },
        text = { OutlinedTextField(name, { name = it.take(100) }, label = { Text("Conversation name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { viewModel.renameConversation(conversation.id, name); renaming = null }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }) }
    deleting?.let { id -> DeleteDialog("Delete conversation?", "This removes its messages and draft from this device.", { deleting = null }) { viewModel.deleteConversation(id); deleting = null } }
}

@Composable
fun AppearanceScreen(viewModel: AiCoreViewModel) {
    val appearance by viewModel.appearance.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose your look", style = MaterialTheme.typography.titleMedium)
        listOf("LIGHT" to "Light mode", "AMOLED" to "Dark mode").forEach { (value, label) ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { viewModel.setAppearance(value) },
                colors = CardDefaults.outlinedCardColors(containerColor = if (appearance == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(appearance == value, { viewModel.setAppearance(value) }, modifier = Modifier.testTag("appearance_$value"))
                    Text(label, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Text("Uses your Android font and display size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun WebSettingsScreen(viewModel: AiCoreViewModel) {
    val allowed by viewModel.webAllowed.collectAsState()
    val diagnostics by viewModel.researchDiagnosticReport.collectAsState()
    val encoderPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.importLocalEncoder(uri) }
    LazyColumn(Modifier.fillMaxSize().testTag("web_settings_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("Web research", style = MaterialTheme.typography.titleMedium)
                    Text(if (allowed) "On · Check public facts" else "Off · Local answers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(allowed, { viewModel.setWebAllowed(it) }, modifier = Modifier.testTag("allow_web"))
            }
            Text("Your public question goes to Exa. Visited websites see your IP address.", style = MaterialTheme.typography.bodyMedium)
            Text("Private memories and connected-app requests stay local. Keep secrets out of web questions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { ScreenCard {
            Text("Grounding mode", style = MaterialTheme.typography.titleMedium)
            val groundingMode by viewModel.groundingMode.collectAsState()
            listOf(
                com.example.grounding.GroundingMode.AUTO to ("Auto (Model first)" to "Model answers first, web research when needed"),
                com.example.grounding.GroundingMode.ON to ("Always on" to "Always research public facts with web sources"),
                com.example.grounding.GroundingMode.OFF to ("Off" to "Local model answers only")
            ).forEach { (mode, pair) ->
                val (label, desc) = pair
                OutlinedCard(
                    Modifier.fillMaxWidth().clickable { viewModel.selectGroundingMode(mode) },
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (groundingMode == mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = groundingMode == mode,
                            onClick = { viewModel.selectGroundingMode(mode) },
                            modifier = Modifier.testTag("grounding_mode_${mode.name}")
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } }
        item { ScreenCard {
            Text("Find. Read. Answer.", style = MaterialTheme.typography.titleMedium)
            listOf(Icons.Default.Search to "Find relevant websites", Icons.Default.Article to "Read and compare evidence", Icons.Default.Link to "Answer with sources").forEach { (icon, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.bodyMedium) }
            }
            Text("Missing details stay uncertain. Useful supported facts are kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { DetailDisclosure("Privacy and tools", "Search limits, stored data and diagnostics", Modifier.testTag("research_details")) {
            Text("No API key, account or cloud answer model. Exa has free-service limits. BI can also check its small public-source directory. Websites may restrict access; BI does not bypass them. Citations are not a guarantee of accuracy.", style = MaterialTheme.typography.bodySmall)
            Text("Only this chat’s focused public topic is searched. Temporary evidence expires within 15 minutes, sooner for live information. Source cards stay with the chat until deleted.", style = MaterialTheme.typography.bodySmall)
            Text("Up to 48 local diagnostic records contain timing and outcome codes. Exports omit questions, URLs, chat IDs and page text. Disabling web or clearing data removes them.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::inspectResearchDiagnostics, modifier = Modifier.testTag("view_research_diagnostics")) { Text("View diagnostics") }
            TextButton(onClick = viewModel::clearSearchData) { Text("Clear temporary search data") }
            Text("Optional local semantic ranking: import a BERT-family GGUF encoder (up to 128 MB). Nothing is downloaded automatically. Without a compatible encoder or enough RAM, lexical ranking is used.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { encoderPicker.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Import local passage encoder") }
            TextButton(onClick = viewModel::removeLocalEncoder, enabled = viewModel.hasLocalEncoder) { Text("Remove local encoder") }
        } }
    }
    diagnostics?.let { report -> AlertDialog(onDismissRequest = viewModel::dismissResearchDiagnostics, title = { Text("Research diagnostics") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("Timing and outcome codes only. Select to copy.")
            SelectionContainer { Text(report, modifier = Modifier.testTag("research_diagnostics_report"), style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = viewModel::dismissResearchDiagnostics) { Text("Close") } }) }
}

@Composable
fun MemoryScreen(viewModel: AiCoreViewModel) {
    val enabled by viewModel.isAutoMemoryCaptureEnabled.collectAsState()
    val memories by viewModel.allMemories.collectAsState()
    var clear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("memory_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenCard { Text("Each chat remembers itself", style = MaterialTheme.typography.titleMedium)
            Text("History and compact context notes stay in that conversation, even after changing models or restarting.", style = MaterialTheme.typography.bodyMedium)
            Text("Other chats and connected apps do not receive this context. Delete a conversation to remove it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { ScreenCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Remember across chats", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Switch(enabled, { viewModel.toggleAutoMemoryCapture(it) }) }
            Text("Save explicit reminders and brief profile details for future chats. Off by default.", style = MaterialTheme.typography.bodyMedium)
            Text("Web search never receives saved memories. Connected apps need separate memory permission.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        items(memories, key = { it.id }) { memory -> ScreenCard {
            Text(memory.title, style = MaterialTheme.typography.titleSmall); Text(memory.content, style = MaterialTheme.typography.bodyMedium)
            Row { TextButton(onClick = { viewModel.toggleMemoryActive(memory) }) { Text(if (memory.isActive) "Pause" else "Use in chat") }
                TextButton(onClick = { viewModel.deleteMemorySnippet(memory.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } }
        } }
        if (memories.isNotEmpty()) item { TextButton(onClick = { clear = true }) { Text("Delete all memories") } }
    }
    if (clear) DeleteDialog("Delete all memories?", "This removes every saved memory from this device.", { clear = false }) { viewModel.clearAllMemories(); clear = false }
}

@Composable
fun PrivacyScreen(viewModel: AiCoreViewModel) {
    var clear by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().testTag("privacy_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenCard { Text("Your AI stays on your device", style = MaterialTheme.typography.titleMedium)
            Text("Chats, drafts, models and app approvals are stored locally. Inference has no automatic cloud fallback.", style = MaterialTheme.typography.bodyMedium)
            Text("Connected-app inference, including TypeRight polish, never uses web search. Memory sharing needs separate permission.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { DetailDisclosure("Internet and backups", "What leaves the device") {
            Text("Internet is used to find and download models. Optional research sends your public question to Exa, then reads publisher websites directly.", style = MaterialTheme.typography.bodyMedium)
            Text("Android backup may include settings and conversations according to your system preferences. Model files and provider credentials are excluded.", style = MaterialTheme.typography.bodyMedium)
        } }
        item { ScreenCard { Text("Manage your data", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { viewModel.selectTab(AppTab.MODELS) }) { Text("Model storage") }
            TextButton(onClick = { viewModel.selectTab(AppTab.CONNECT) }) { Text("App permissions") }
            TextButton(onClick = viewModel::clearSearchData) { Text("Clear temporary search data") }
            TextButton(onClick = { clear = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete all conversations") }
            Text("Deleting a model keeps chats. Revoking app access prevents new requests.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { TextButton(onClick = { runCatching { uri.openUri("https://haveanidea-privacy-policy.ai.studio") } }) { Text("Privacy policy") } }
    }
    if (clear) DeleteDialog("Delete all conversations?", "All saved messages and drafts will be removed. Models, app approvals and memories are kept.", { clear = false }) { viewModel.clearChat(); clear = false }
}

@Composable
private fun DeleteDialog(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(confirm) { Text("Delete") } }, dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

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
    LazyColumn(Modifier.fillMaxSize().testTag("advanced_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenCard(Modifier.testTag("processing_AUTO")) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoMode, null, tint = MaterialTheme.colorScheme.primary)
                Column { Text("AI processing", style = MaterialTheme.typography.titleMedium); Text("Automatic · Recommended", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            Text("Chooses compatible processors using measured performance and available memory.", style = MaterialTheme.typography.bodyMedium)
        } }
        item { ScreenCard(Modifier.testTag("response_preferences_AUTO")) {
            Text("Longer replies, automatically", style = MaterialTheme.typography.titleMedium)
            Text("Replies can continue within the model’s context, memory and time limits.", style = MaterialTheme.typography.bodyMedium)
            Text(secondary?.let { "Low-memory fallback: ${it.name.substringBefore(" · ")}" } ?: "Context and response settings adapt to your device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { DetailDisclosure("Hardware monitoring", if (telemetry.isModelLoaded) "${telemetry.activeBackend} · ${"%.1f".format(telemetry.tokensPerSecond)} tokens/s" else "No model loaded") {
            Text(telemetry.modelName, style = MaterialTheme.typography.titleSmall)
            Text("${telemetry.activeBackend} · First token ${telemetry.timeToFirstTokenMs} ms · ${telemetry.memoryUsageMb.toInt()} MB app RAM", style = MaterialTheme.typography.bodyMedium)
            Text("${telemetry.activeThreads} CPU threads · ${telemetry.activeGpuLayers} GPU layers · Thermal: ${telemetry.thermalState}", style = MaterialTheme.typography.bodySmall)
            Text(telemetry.backendStatus, style = MaterialTheme.typography.bodySmall)
            if (telemetry.backendDecision.isNotBlank()) Text(telemetry.backendDecision, style = MaterialTheme.typography.bodySmall)
            if (telemetry.fallbackCount > 0) Text("Fallbacks: ${telemetry.fallbackCount} · ${telemetry.lastFallback}", style = MaterialTheme.typography.bodySmall)
            Text("GPU memory is shared. App RAM measurements exclude some driver allocations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HardwareDiagnosticCard(hardware, { viewModel.refreshHardwareDiagnostics() })
        } }
        item { DetailDisclosure("Processing details", "Compatibility and saved measurements") {
            Text("CPU and supported GPU backends are measured for correctness, speed, memory and thermal headroom. GGUF and LiteRT use their compatible runtimes.", style = MaterialTheme.typography.bodyMedium)
            if (BuildConfig.QUALCOMM_NPU_ENABLED) Text(if (acceleration.npuAvailable) "Qualcomm NPU validated for this model." else acceleration.npuReason,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("qualcomm_npu_status"))
            else Text("NPU requires a compatible runtime, device and compiled model; it is unavailable in this build.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::resetBackendDecisions, enabled = !telemetry.isRunning) { Text("Reset backend measurements") }
        } }
        item { DetailDisclosure("Benchmarks", "Compare installed models") {
            HardwarePerformanceComparisonCard(logs, models, hardware, quantization, sort, benchmarking,
                { viewModel.setQuantizationFilter(it) }, { viewModel.setSortMetric(it) }, { viewModel.runBenchmarkForModel(it) },
                { viewModel.deletePerformanceLog(it) }, { viewModel.clearAllPerformanceLogs() })
        } }
        item { ScreenCard {
            Text("About Better Intelligence", style = MaterialTheme.typography.titleMedium)
            Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = viewModel::unloadActiveModel, enabled = !telemetry.isRunning) { Text("Free model memory") }
            TextButton(onClick = viewModel::clearIpcLogs) { Text("Clear app access logs") }
            TextButton(onClick = { runCatching { uri.openUri("https://github.com/sidhrthmnn/BetterIntelligence/blob/main/docs/THIRD_PARTY_NOTICES.md") } }) { Text("Licences and model terms") }
        } }
    }
}
