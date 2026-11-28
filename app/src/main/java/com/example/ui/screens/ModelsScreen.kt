package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.ModelEntity
import com.example.engine.ModelLibrary
import com.example.engine.ModelMemoryPlanner
import com.example.tasks.TaskModels
import com.example.tasks.TaskType
import com.example.ui.AiCoreViewModel
import com.example.ui.components.HardwareDiagnosticCard
import com.example.ui.components.HardwarePerformanceComparisonCard
import com.example.ui.components.HuggingFaceDiscoveryCard
import com.example.ui.components.ScreenCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: AiCoreViewModel) {
    val models by viewModel.allModels.collectAsState()
    val downloadErrors by viewModel.modelDownloadErrors.collectAsState()
    val active by viewModel.activeModel.collectAsState()
    val primaryId by viewModel.primaryModelId.collectAsState()
    val fallbackId by viewModel.secondaryModelId.collectAsState()
    val secondary by viewModel.secondaryModel.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val hardware by viewModel.hardwareProfile.collectAsState()
    val generationParams by viewModel.generationParams.collectAsState()
    val settings by viewModel.taskSettings.collectAsState()
    val approvals by viewModel.modelDownloadApprovals.collectAsState()
    val loading by viewModel.loadingModelId.collectAsState()
    val terms by viewModel.licenseModel.collectAsState()
    val verifying by viewModel.verifyingModelId.collectAsState()
    val deletingId by viewModel.deletingModelId.collectAsState()
    val context = LocalContext.current
    var capability by rememberSaveable { mutableStateOf(ModelPresentation.ALL) }
    var source by rememberSaveable { mutableStateOf("my_models") }
    var installedQuery by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var detailsId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAssignments by rememberSaveable { mutableStateOf(false) }
    var assignmentModelId by rememberSaveable { mutableStateOf<Long?>(null) }
    var partialDelete by remember { mutableStateOf<ModelEntity?>(null) }
    var deleting by remember { mutableStateOf<ModelEntity?>(null) }
    var recentTransfers by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var revealFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var previousRunning by remember { mutableStateOf<List<Long>?>(null) }
    val listState = rememberLazyListState()
    fun showSource(next: String) {
        source = next
        revealFrom = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) showSource("my_models")
        uris.forEach { viewModel.importGgufFromUri(it, context) }
    }
    val pending = models.filter(ModelPresentation::hasTransfer)
    val runningIds = pending.filter(ModelPresentation::transferring).map { it.id }
    LaunchedEffect(runningIds) {
        val started = runningIds.filterNot { it in recentTransfers }
        recentTransfers = (started + recentTransfers).take(16)
        val newlyRunning = runningIds.any { it !in previousRunning.orEmpty() }
        if (newlyRunning && revealFrom == source) showSource("downloads")
        previousRunning = runningIds
    }
    LaunchedEffect(source) { listState.scrollToItem(0) }

    val loadProfile = hardware?.let { profile ->
        val available = (profile.availableRamMb + viewModel.releasableGpuWeightsMb()).coerceAtMost(profile.totalRamMb)
        profile.copy(availableRamMb = available, safeModelRamLimitMb = ModelMemoryPlanner.budget(profile.totalRamMb, available))
    }
    val installed = models.filter { it.isInstalled }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    val library = models.filter(ModelLibrary::visibleInLibrary)
    val visibleModels = when (source) {
        "downloads" -> ModelPresentation.transfers(models, recentTransfers)
        "library" -> ModelPresentation.byRam(library.filter { ModelPresentation.matches(it, capability) }, generationParams.contextSize, capability)
        "find" -> emptyList()
        else -> installed.filter { it.name.contains(installedQuery.trim(), ignoreCase = true) }
    }
    val loadedId = active?.id.takeIf { telemetry.isModelLoaded }
    val modelBusy = loading != null || deletingId != null || telemetry.isRunning
    fun openAssignments(model: ModelEntity? = null) {
        assignmentModelId = model?.id
        showAssignments = true
    }
    fun download(model: ModelEntity) {
        if (source != "downloads") revealFrom = source
        viewModel.downloadModel(model)
    }

    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = when (source) { "downloads" -> 1; "library", "find" -> 2; else -> 0 }) {
            listOf("my_models" to "Installed", "downloads" to "Downloads", "library" to "Library")
                .forEach { (key, label) ->
                    Tab(selected = source == key || key == "library" && source == "find",
                        onClick = { showSource(key) }, text = {
                            val count = pending.size + approvals.count { approval -> models.any { it.id == approval.modelId && !it.isInstalled } }
                            if (key == "downloads" && count > 0) BadgedBox(badge = { Badge { Text(count.toString()) } }) {
                                Text(label, maxLines = 1)
                            } else Text(label, maxLines = 1)
                        },
                        modifier = Modifier.testTag("model_source_$key"))
                }
        }
        LazyColumn(Modifier.weight(1f).testTag("models_list"), state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("summary") {
                ModelCollectionSummary(
                    title = when (source) { "my_models" -> "On this device"; "downloads" -> "Download queue"; else -> "Find a model" },
                    storage = when (source) {
                        "my_models" -> "${installed.size} installed · ${ModelPresentation.size(installed.sumOf { it.fileSizeMb })} on device"
                        "downloads" -> if (pending.isEmpty()) "No pending transfers" else "${pending.size} pending"
                        else -> hardware?.let { "${ModelPresentation.size((it.internalStorageFreeGb * 1024).toLong())} storage free" }
                    },
                    memory = hardware?.let { "${ModelPresentation.size(it.availableRamMb)} RAM free" },
                    onImport = if (source == "my_models") ({ picker.launch(arrayOf("*/*")) }) else null
                )
            }
            when (source) {
                "my_models" -> item("installed_controls") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { openAssignments() }, modifier = Modifier.testTag("model_assignments")) { Text("AI assignments") }
                        TextButton(onClick = { showSource("library") }) { Text("Get models") }
                    }
                    if (installed.size >= 4 || installedQuery.isNotBlank()) {
                        OutlinedTextField(value = installedQuery, onValueChange = { installedQuery = it },
                            label = { Text("Search installed models") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            modifier = Modifier.fillMaxWidth().testTag("search_installed_models"))
                    }
                }
                "downloads" -> item("download_approvals") { DownloadRequests(viewModel, models) }
                "library" -> item("library_controls") {
                    OutlinedButton(onClick = { showSource("find") }, modifier = Modifier.fillMaxWidth().testTag("model_source_find")) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search Hugging Face")
                    }
                    CapabilityFilters(capability, ModelPresentation.ALL.let { all ->
                        listOf(all, "Text", "Vision", "Voice", "Audio", "Embeddings")
                            .filter { filter -> library.any { ModelPresentation.matches(it, filter) } }
                    }) { capability = it }
                }
                "find" -> item("hub") {
                    TextButton(onClick = { showSource("library") }) { Text("Back to library") }
                    val query by viewModel.hfSearchQuery.collectAsState()
                    val results by viewModel.hfSearchResults.collectAsState()
                    val searching by viewModel.isHfSearching.collectAsState()
                    val repo by viewModel.selectedHfRepo.collectAsState()
                    val files by viewModel.hfRepoGgufFiles.collectAsState()
                    val loadingFiles by viewModel.isLoadingRepoFiles.collectAsState()
                    HuggingFaceDiscoveryCard(query, results, searching, repo, files, loadingFiles,
                        { viewModel.setHfSearchQuery(it) }, { viewModel.searchHuggingFace(it) },
                        { viewModel.selectHfRepo(it) }, { viewModel.clearHfRepoSelection() },
                        { id, file -> revealFrom = source; viewModel.addAndDownloadHfModel(id, file) },
                        initiallyExpanded = true, availableRamMb = loadProfile?.safeModelRamLimitMb)
                }
            }
            if (visibleModels.isEmpty() && source != "find") item("empty") {
                ScreenCard {
                    Icon(if (source == "downloads") Icons.Default.Download else Icons.Default.Inventory2, null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text(when {
                        source == "downloads" -> "No downloads yet"
                        source == "library" -> "No models for this filter"
                        installedQuery.isNotBlank() -> "No matching installed models"
                        else -> "No models installed"
                    }, style = MaterialTheme.typography.titleMedium)
                    if (source == "downloads") TextButton(onClick = { showSource("library") }) { Text("Browse models") }
                    if (source == "my_models" && installedQuery.isBlank()) Text("Download a model from the Library or import a file.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(visibleModels, key = { it.id }) { model ->
                val filter = capability.takeIf { source == "library" } ?: ModelPresentation.ALL
                ModelCollectionCard(
                    model, source == "downloads", loadedId == model.id, loading == model.id, modelBusy,
                    loadProfile, generationParams.contextSize, filter, hardware?.let { ModelLibrary.canDownload(model, it) } ?: true,
                    primaryId == model.id, fallbackId == model.id, ModelPresentation.assignments(model, settings),
                    downloadErrors[model.id], details = { detailsId = model.id }, assign = { openAssignments(model) },
                    use = { viewModel.switchModel(model) }, download = {
                        if (ModelPresentation.transferring(model)) showSource("downloads") else download(model)
                    }, pause = { viewModel.cancelDownload(model.id) }, deletePartial = { partialDelete = model }
                )
            }
            if (source == "my_models") {
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
        }
    }
    if (showAssignments) MyAiSetup(viewModel, models, installed.firstOrNull { it.id == assignmentModelId }) {
        showAssignments = false
    }
    detailsId?.let { id -> models.firstOrNull { it.id == id } }?.let { model ->
        AlertDialog(onDismissRequest = { detailsId = null }, title = { Text(model.name) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model.description, style = MaterialTheme.typography.bodyMedium)
                val memoryContext = ModelPresentation.loadContext(model, generationParams.contextSize)
                Text("${ModelPresentation.size(model.fileSizeMb)} download · ~${ModelPresentation.size(ModelPresentation.estimatedRam(model, generationParams.contextSize))} RAM", style = MaterialTheme.typography.bodyMedium)
                val mediaLabels = TaskModels.capabilities(model).intersect(TaskModels.mediaTypes)
                if (mediaLabels.isNotEmpty()) Text("Image and audio memory estimates are listed in the Library filters.", style = MaterialTheme.typography.bodySmall)
                Text("RAM changes with context size and processor. GPU shares system memory.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                loadProfile?.let { profile -> ModelLibrary.incompatibilityReason(model, profile, memoryContext)?.let { Text(it) } }
                Text("${model.quantization} · ${model.contextLength} token model context")
                Text("Integrity: ${model.checksumVerificationStatus}", style = MaterialTheme.typography.bodySmall)
                downloadErrors[model.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                model.huggingFaceRepo?.let { repo ->
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/$repo"))) }) { Text("Model source and license") }
                }

                if (model.isInstalled && TaskType.TEXT_GENERATION in TaskModels.capabilities(model) && active?.id != model.id && secondary?.id != model.id && ModelLibrary.category(model) != "EXPERIMENTAL") {
                    TextButton(onClick = { viewModel.setSecondaryModel(model); detailsId = null }) { Text("Use as low-memory fallback") }
                }
                if (model.isInstalled && TaskModels.capabilities(model).isNotEmpty()) {
                    TextButton(onClick = { detailsId = null; assignmentModelId = model.id; showAssignments = true }) { Text("Assign to tasks") }
                }
                if (model.isInstalled) {
                    TextButton(onClick = { viewModel.verifyModelChecksum(model) }, enabled = verifying == null && deletingId == null) { Text(if (verifying == model.id) "Verifying…" else "Verify file") }

                }
                if (model.isInstalled || model.isCustom) {
                    TextButton(onClick = { detailsId = null; deleting = model },
                        modifier = Modifier.testTag("delete_model_${model.id}"),
                        enabled = deletingId == null && loading == null && verifying == null && !(active?.id == model.id && telemetry.isRunning),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete from device") }
                }
            }
        }, confirmButton = { TextButton(onClick = { detailsId = null }) { Text("Done") } })
    }
    partialDelete?.let { model -> AlertDialog(onDismissRequest = { partialDelete = null }, title = { Text("Delete partial download?") },
        text = { Text("Remove the unfinished download for ${model.name}. Any complete installed model is kept.") },
        confirmButton = { TextButton(onClick = { partialDelete = null; viewModel.deletePartialDownload(model) }, modifier = Modifier.testTag("confirm_delete_partial")) { Text("Delete partial") } },
        dismissButton = { TextButton(onClick = { partialDelete = null }) { Text("Cancel") } }) }
    deleting?.let { model ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${model.name}?") },
            text = { Text(buildString {
                append("This removes the model from this device and frees its storage. ")
                if (active?.id == model.id) append("The model will be unloaded. ")
                append(if (model.isCustom && model.downloadUrl.isNullOrBlank()) "You can import the original file again. Your original file stays where you imported it from." else "You can download it again later.")
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
