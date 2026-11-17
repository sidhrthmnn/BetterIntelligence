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
    val appearance by viewModel.appearance.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsSection("Your AI") }
        item { SettingsRow("Optimise your AI", "Check device, set up models and view results", Icons.Default.AutoAwesome) { viewModel.recheckDevice(); viewModel.selectTab(AppTab.OPTIMISE) } }
        item { SettingsRow("Models", active?.name?.substringBefore(" · ") ?: "Find, download and manage models", Icons.Default.Memory) { viewModel.selectTab(AppTab.MODELS) } }
        item { DetailDisclosure("Models & Downloads", "Wi-Fi, background downloads and notifications", Modifier.testTag("setting_Models_Downloads")) {
            ModelsDownloadsPreferences(viewModel)
        } }
        item { SettingsRow("Connected apps", "Choose which apps can use your AI", Icons.Default.Apps) { viewModel.selectTab(AppTab.CONNECT) } }
        item { SettingsSection("Personalise") }
        item { SettingsRow("Appearance", if (appearance == "LIGHT") "Light mode" else "Dark mode", Icons.Default.Palette) { viewModel.selectTab(AppTab.APPEARANCE) } }
        item { SettingsSection("Privacy & about") }
        item { SettingsRow("Privacy & data", "Your data, storage and permissions", Icons.Default.Lock) { viewModel.selectTab(AppTab.PRIVACY) } }
        item { SettingsRow("Advanced & about", "Processing, performance and app details", Icons.Default.Tune) { viewModel.selectTab(AppTab.ADVANCED) } }
    }
}

@Composable
fun ModelsDownloadsPreferences(viewModel: AiCoreViewModel) {
    var bgEnabled by remember { mutableStateOf(viewModel.backgroundDownloadsEnabled) }
    var wifiOnly by remember { mutableStateOf(viewModel.downloadsWifiOnly) }
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
            Text("Download over Wi-Fi only", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    viewModel.setDownloadsWifiOnly(it)
                },
                modifier = Modifier.testTag("pref_wifi_only")
            )
        }
        Text("Turn off to download using mobile data or Wi-Fi.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun PrivacyScreen(viewModel: AiCoreViewModel) {
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().testTag("privacy_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenCard { Text("Your AI stays on your device", style = MaterialTheme.typography.titleMedium)
            Text("Models run locally. Connected apps send only the requests you allow. There is no automatic cloud inference fallback.", style = MaterialTheme.typography.bodyMedium)
            Text("App access and memory sharing have separate permissions.", style = MaterialTheme.typography.bodySmall) } }
        item { ScreenCard { Text("Internet and backups", style = MaterialTheme.typography.titleMedium)
            Text("Internet is used to discover and download models. BI does not browse the web or send connected-app requests to a search service.", style = MaterialTheme.typography.bodyMedium)
            Text("Android backup follows your system settings. Downloaded models and device-specific setup data are excluded.", style = MaterialTheme.typography.bodySmall) } }
        item { ScreenCard { Text("Manage your data", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { viewModel.selectTab(AppTab.MODELS) }) { Text("Model storage") }
            TextButton(onClick = { viewModel.selectTab(AppTab.CONNECT) }) { Text("App permissions") }
            Text("Revoking app access prevents new requests.", style = MaterialTheme.typography.bodySmall) } }
        item { TextButton(onClick = { runCatching { uri.openUri("https://haveanidea-privacy-policy.ai.studio") } }) { Text("Privacy policy") } }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
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
    val parameters by viewModel.generationParams.collectAsState()
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize().testTag("advanced_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { DetailDisclosure("Model context", "Default context for connected apps") {
            Text("${parameters.contextSize} tokens", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(512, 1024, 2048, 4096).forEach { size ->
                    FilterChip(selected = parameters.contextSize == size,
                        onClick = { viewModel.updateGenerationParams(parameters.copy(contextSize = size)) },
                        label = { Text(size.toString()) })
                }
            }
            Text("Larger context uses more RAM. Apps can request their own limits.", style = MaterialTheme.typography.bodySmall)
        } }
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
