package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.ModelEntity
import com.example.engine.ModelLibrary
import com.example.engine.ModelMemoryPlanner
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import com.example.ui.components.HardwareDiagnosticCard
import com.example.ui.components.HuggingFaceDiscoveryCard
import com.example.ui.components.HardwarePerformanceComparisonCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(viewModel: AiCoreViewModel) {
    val models by viewModel.allModels.collectAsState()
    val downloadErrors by viewModel.modelDownloadErrors.collectAsState()
    val active by viewModel.activeModel.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val hardware by viewModel.hardwareProfile.collectAsState()
    val recommendation by viewModel.modelRecommendation.collectAsState()
    val loading by viewModel.loadingModelId.collectAsState()
    val terms by viewModel.licenseModel.collectAsState()
    val verifying by viewModel.verifyingModelId.collectAsState()
    val deletingId by viewModel.deletingModelId.collectAsState()
    val context = LocalContext.current
    var source by rememberSaveable { mutableStateOf("library") }
    var tier by rememberSaveable { mutableStateOf("ALL") }
    var otherModels by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<ModelEntity?>(null) }
    var deleting by remember { mutableStateOf<ModelEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) source = "my_models"
        uris.forEach { viewModel.importGgufFromUri(it, context) }
    }
    val recommended = models.find { it.filename == recommendation?.recommendedFilename }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Models for your phone", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("library" to "Library", "find" to "Find models", "my_models" to "My models").forEach { (key, label) ->
                    FilterChip(selected = source == key, onClick = { source = key }, label = { Text(label) },
                        modifier = Modifier.testTag("model_source_$key"))
                }
            }
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("import_gguf_btn")) { Text("Import GGUF or LiteRT-LM") }
        }
        if (source == "find") item {
            val query by viewModel.hfSearchQuery.collectAsState()
            val results by viewModel.hfSearchResults.collectAsState()
            val searching by viewModel.isHfSearching.collectAsState()
            val repo by viewModel.selectedHfRepo.collectAsState()
            val files by viewModel.hfRepoGgufFiles.collectAsState()
            val loadingFiles by viewModel.isLoadingRepoFiles.collectAsState()
            HuggingFaceDiscoveryCard(query, results, searching, repo, files, loadingFiles,
                { viewModel.setHfSearchQuery(it) }, { viewModel.searchHuggingFace(it) },
                { viewModel.selectHfRepo(it) }, { viewModel.clearHfRepoSelection() },
                { id, file -> viewModel.addAndDownloadHfModel(id, file); source = "my_models" }, initiallyExpanded = true)
        }
        if (source != "find" && (telemetry.isModelLoaded || loading != null)) item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (telemetry.isModelLoaded) "Ready on this device" else if (loading != null) "Loading model…" else "Choose your AI model",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("model_readiness"))
                    Text(if (telemetry.isModelLoaded) active?.name ?: "Local model" else "Download a model below, then tap Use model. Downloads use storage and an internet connection.")
                    if (telemetry.isModelLoaded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.selectTab(AppTab.CHAT) }) { Text("Open chat") }
                            TextButton(onClick = { viewModel.selectTab(AppTab.CONNECT) }) { Text("Connect apps") }
                        }
                    } else if (loading != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (source == "library" && recommended != null && !telemetry.isModelLoaded) item {
            Text("Suggested for this device: ${recommended.name}", style = MaterialTheme.typography.titleSmall)
            Text("Download, then tap Use model to start.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (source == "library") item {
            Text("Mobile-friendly library", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "All", "FAST" to "Fast", "BALANCED" to "Balanced", "ADVANCED" to "Advanced", "EXPERIMENTAL" to "Experimental").forEach { (key, label) ->
                    FilterChip(tier == key, { tier = key }, label = { Text(label) })
                }
            }
            TextButton(onClick = { otherModels = !otherModels; tier = "ALL" }) {
                Text(if (otherModels) "Hide other models" else "Other models and existing downloads")
            }
        }
        val visibleModels = models.filter {
            when (source) {
                "library" -> !it.isCustom && (otherModels || ModelLibrary.category(it) != null || it.isInstalled || it.id == recommended?.id) &&
                    (tier == "ALL" || ModelLibrary.category(it) == tier)
                "my_models" -> it.isInstalled || it.isCustom
                else -> false
            }
        }
        if (source == "my_models" && visibleModels.isEmpty()) item {
            Text("Your downloads and imports will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(visibleModels.sortedWith(compareByDescending<ModelEntity> { it.id == active?.id }
            .thenByDescending { it.id == recommended?.id }.thenBy { it.fileSizeMb }), key = { it.id }) { model ->
            val installed = model.isInstalled
            val downloading = model.downloadState == "DOWNLOADING"
            val selected = active?.id == model.id && telemetry.isModelLoaded
            val loadProfile = hardware?.let { profile ->
                val reclaimable = viewModel.releasableGpuWeightsMb()
                val available = (profile.availableRamMb + reclaimable).coerceAtMost(profile.totalRamMb)
                profile.copy(availableRamMb = available, safeModelRamLimitMb = ModelMemoryPlanner.budget(profile.totalRamMb, available))
            }
            val fits = loadProfile?.let { ModelLibrary.fits(model, it, 512) } ?: true
            val canDownload = hardware?.let { ModelLibrary.canDownload(model, it) } ?: true
            OutlinedCard(Modifier.fillMaxWidth().testTag("model_card_${model.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModelLibrary.categoryLabel(model)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    Text(model.name.substringBefore(" · "), style = MaterialTheme.typography.titleMedium)
                    Text("${model.fileSizeMb} MB download · About ${ModelLibrary.memoryEstimate(model).totalMb} MB RAM",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ModelLibrary.category(model) == "FAST") Text("Small download for basic English tasks. Choose Balanced for more reliable writing and multilingual chat.", style = MaterialTheme.typography.bodySmall)
                    if (ModelLibrary.category(model) == "BALANCED") Text("Recommended for everyday chat and English AI Polish.", style = MaterialTheme.typography.bodySmall)
                    if (ModelLibrary.category(model) == "ADVANCED") Text("Stronger multilingual writing; higher memory use and slower responses.", style = MaterialTheme.typography.bodySmall)
                    if (ModelLibrary.category(model) == "EXPERIMENTAL") Text("Unverified on this phone. Manually test CPU/GPU, memory, chat and TypeRight correctness before relying on it. Not selected automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (selected) Text("Selected · available to approved apps", color = MaterialTheme.colorScheme.primary)
                    Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!selected && !fits) Text(loadProfile?.let { ModelLibrary.incompatibilityReason(model, it, 512) }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (!installed && !canDownload) Text("Free more storage before downloading this model.", color = MaterialTheme.colorScheme.error)
                    if (model.downloadState == "ERROR") Text(downloadErrors[model.id] ?: "Download interrupted. Retry to resume or check the model publisher’s access requirements.", color = MaterialTheme.colorScheme.error)
                    if (downloading) {
                        LinearProgressIndicator(progress = { model.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        Text(if (model.checksumVerificationStatus == "VERIFYING") "Checking download…" else "Downloading ${(model.downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            downloading -> OutlinedButton(onClick = { viewModel.cancelDownload(model.id) }) { Text("Pause") }
                            installed -> Button(onClick = { viewModel.switchModel(model) }, enabled = loading == null && deletingId == null && !selected && fits && !telemetry.isRunning,
                                modifier = Modifier.testTag("load_model_${model.id}")) {
                                Text(if (loading == model.id) "Loading…" else if (selected) "In use" else "Use model")
                            }
                            else -> Button(onClick = { viewModel.downloadModel(model) }, enabled = canDownload,
                                modifier = Modifier.testTag("download_model_${model.id}")) { Text(if (model.downloadState == "ERROR") "Retry download" else if (model.downloadState == "PAUSED") "Resume download" else "Download") }
                        }
                        TextButton(onClick = { details = model }) { Text("Details") }
                        if (installed || (model.isCustom && !downloading)) {
                            TextButton(onClick = { deleting = model },
                                enabled = deletingId == null && loading == null && verifying == null && !(selected && telemetry.isRunning),
                                modifier = Modifier.testTag("delete_model_${model.id}"),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text(if (deletingId == model.id) "Deleting…" else "Delete")
                            }
                        }
                    }
                    if (selected && telemetry.isRunning) Text("Stop the current answer before deleting the model.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced tools" else "Advanced tools") }
        }
        if (advanced) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Hardware monitoring", style = MaterialTheme.typography.titleMedium)
                        Text("${telemetry.modelName} · ${telemetry.activeBackend}")
                        Text("${"%.1f".format(telemetry.tokensPerSecond)} tokens/s · First token ${telemetry.timeToFirstTokenMs} ms")
                        Text("${telemetry.activeThreads} CPU threads · ${telemetry.activeGpuLayers} GPU layers · ${telemetry.memoryUsageMb.toInt()} MB app RAM")
                        Text("Thermal: ${telemetry.thermalState} · Battery sensor: ${if (telemetry.temperatureCelsius > 0) "${telemetry.temperatureCelsius}°C" else "Unavailable"}")
                        Text("Backend load: ${telemetry.backendInitializationMs} ms · ${telemetry.backendStatus}")
                        if (telemetry.fallbackCount > 0) Text("Fallbacks: ${telemetry.fallbackCount} · ${telemetry.lastFallback}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { HardwareDiagnosticCard(hardware, { viewModel.refreshHardwareDiagnostics() }) }
            item {
                val logs by viewModel.performanceLogs.collectAsState()
                val quantization by viewModel.selectedQuantizationFilter.collectAsState()
                val sort by viewModel.selectedSortMetric.collectAsState()
                val benchmarking by viewModel.isBenchmarking.collectAsState()
                HardwarePerformanceComparisonCard(logs, models, hardware, quantization, sort, benchmarking,
                    { viewModel.setQuantizationFilter(it) }, { viewModel.setSortMetric(it) },
                    { viewModel.runBenchmarkForModel(it) }, { viewModel.deletePerformanceLog(it) },
                    { viewModel.clearAllPerformanceLogs() })
            }
            if (telemetry.isModelLoaded) item {
                TextButton(onClick = { viewModel.unloadActiveModel() }) { Text("Unload model to free memory") }
            }
        }
    }
    details?.let { model ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(model.name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.description)
                Text("${model.quantization} · ${model.contextLength} token model context")
                Text("Integrity: ${model.checksumVerificationStatus}")
                model.huggingFaceRepo?.let { repo ->
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/$repo"))) }) { Text("Model source and license") }
                }
                Text("Writing quality depends on the selected model. Review polished text before applying it.")
                if (model.isInstalled) {
                    TextButton(onClick = { viewModel.verifyModelChecksum(model) }, enabled = verifying == null && deletingId == null) { Text(if (verifying == model.id) "Verifying…" else "Verify file") }
                    TextButton(onClick = { details = null; deleting = model },
                        enabled = deletingId == null && loading == null && verifying == null && !(active?.id == model.id && telemetry.isRunning)) { Text("Delete from device") }
                }
            }
        }, confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } })
    }
    deleting?.let { model ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${model.name}?") },
            text = { Text(buildString {
                append("This removes the model from this device and frees its storage. ")
                if (active?.id == model.id) append("The model will be unloaded and connected apps will need another model selected. ")
                append(if (model.isCustom && model.downloadUrl.isNullOrBlank()) "You can import the original file again. The original file and saved chats are kept." else "You can download it again later. Saved chats are kept.")
            }) },
            confirmButton = { TextButton(onClick = { viewModel.uninstallModel(model); deleting = null },
                modifier = Modifier.testTag("confirm_delete_model")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    if (terms != null) AlertDialog(onDismissRequest = { viewModel.dismissModelTerms() }, title = { Text("Gemma model terms") },
        text = { Column {
            Text("Review Google’s Gemma terms and use policy before downloading or using this model.")
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ai.google.dev/gemma/terms"))) }) { Text("Read terms") }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ai.google.dev/gemma/prohibited_use_policy"))) }) { Text("Read use policy") }
        } }, confirmButton = { TextButton(onClick = { viewModel.acceptModelTerms() }) { Text("Accept and continue") } },
        dismissButton = { TextButton(onClick = { viewModel.dismissModelTerms() }) { Text("Cancel") } })
}
