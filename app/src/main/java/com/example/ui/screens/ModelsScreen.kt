package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.components.ModelMark
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val secondary by viewModel.secondaryModel.collectAsState()
    val autoFallback by viewModel.autoRamFallback.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val hardware by viewModel.hardwareProfile.collectAsState()
    val generationParams by viewModel.chatGenerationParams.collectAsState()
    val recommendation by viewModel.modelRecommendation.collectAsState()
    val loading by viewModel.loadingModelId.collectAsState()
    val terms by viewModel.licenseModel.collectAsState()
    val verifying by viewModel.verifyingModelId.collectAsState()
    val deletingId by viewModel.deletingModelId.collectAsState()
    val context = LocalContext.current
    var source by rememberSaveable { mutableStateOf("library") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<ModelEntity?>(null) }
    var partialDelete by remember { mutableStateOf<ModelEntity?>(null) }
    var deleting by remember { mutableStateOf<ModelEntity?>(null) }
    val listState = rememberLazyListState()
    var pinnedDownloads by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var previousDownloads by remember { mutableStateOf(setOf<Long>()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) source = "my_models"
        uris.forEach { viewModel.importGgufFromUri(it, context) }
    }
    val recommended = models.find { it.filename == recommendation?.recommendedFilename }
    val downloadingIds = models.filter { it.downloadState in setOf("DOWNLOADING", "QUEUED") }.map { it.id }.toSet()
    LaunchedEffect(downloadingIds) {
        val started = downloadingIds - previousDownloads
        previousDownloads = downloadingIds
        if (started.isNotEmpty()) {
            pinnedDownloads = started.sorted() + pinnedDownloads.filterNot { it in started }
            source = "my_models"
            // Focus once per start/resume. Progress and RAM samples never interrupt scrolling.
            listState.scrollToItem(if (telemetry.isModelLoaded || loading != null) 4 else 3)
        }
    }
    val loadProfile = hardware?.let { profile ->
        val available = (profile.availableRamMb + viewModel.releasableGpuWeightsMb()).coerceAtMost(profile.totalRamMb)
        profile.copy(availableRamMb = available, safeModelRamLimitMb = ModelMemoryPlanner.budget(profile.totalRamMb, available))
    }

    LazyColumn(Modifier.fillMaxSize().testTag("models_list"), state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(hardware?.let { "${ModelPresentation.size(it.availableRamMb)} RAM free · ${ModelPresentation.size(it.totalRamMb)} total" }
                        ?: "Checking available RAM…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("model_ram_summary"))
                    hardware?.takeIf { it.totalRamMb > 0 }?.let { hw ->
                        val available = (hw.availableRamMb.toFloat() / hw.totalRamMb).coerceIn(0f, 1f)
                        val animatedRam by animateFloatAsState(available, tween(300), label = "ram_bar")
                        LinearProgressIndicator(progress = { animatedRam }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)))
                    }
                }
                FilledTonalIconButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("import_gguf_btn")) {
                    Icon(Icons.Default.Add, "Import model")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("library" to "Library", "find" to "Find models", "my_models" to "My models").forEach { (key, label) ->
                    FilterChip(selected = source == key, onClick = { source = key }, shape = RoundedCornerShape(50),
                        label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f).testTag("model_source_$key"))
                }
            }
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
                { id, file -> viewModel.addAndDownloadHfModel(id, file); source = "my_models" }, initiallyExpanded = true, availableRamMb = loadProfile?.safeModelRamLimitMb)
        }
        if (source != "find" && (telemetry.isModelLoaded || loading != null)) item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (telemetry.isModelLoaded) "Ready to chat" else if (loading != null) "Loading model…" else "Choose your AI model",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.testTag("model_readiness")
                            )
                            Text(
                                if (telemetry.isModelLoaded) active?.name ?: "Local model" else "Download a model below, then tap Use model.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (telemetry.isModelLoaded) {
                            Button(
                                onClick = { viewModel.selectTab(AppTab.CHAT) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) { Text("Chat", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    if (telemetry.isModelLoaded && (models.count { it.isInstalled } >= 2 || secondary != null)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                secondary?.let { "Low-RAM fallback: ${it.name.substringBefore(" · ")}" }
                                    ?: "Auto low-RAM fallback",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Switch(
                                checked = autoFallback,
                                onCheckedChange = { viewModel.setAutoRamFallback(it) }
                            )
                        }
                    } else if (loading != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (source == "library" && recommended != null && !telemetry.isModelLoaded) item {
            Text("Suggested for this device: ${recommended.name}", style = MaterialTheme.typography.titleSmall)

        }
        if (source == "library") item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("All models · Least RAM first", style = MaterialTheme.typography.titleSmall)
            }
            Text("RAM is estimated. Downloads stay at the top.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val visibleModels = models.filter {
            if (it.id in pinnedDownloads || it.downloadState in setOf("DOWNLOADING", "QUEUED")) true else when (source) {
                "library" -> true
                "my_models" -> ModelPresentation.hasDownload(it) || it.isCustom
                else -> false
            }
        }
        if (source == "my_models" && visibleModels.isEmpty()) item {
            Text("Your downloads and imports will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(ModelPresentation.byRam(visibleModels, generationParams.contextSize, pinnedDownloads), key = { it.id }) { model ->
            val installed = model.isInstalled
            val downloading = model.downloadState in setOf("DOWNLOADING", "QUEUED")
            val selected = active?.id == model.id && telemetry.isModelLoaded
            val isSecondary = secondary?.id == model.id && !selected
            val memoryContext = ModelPresentation.loadContext(model, generationParams.contextSize)
            val fits = loadProfile?.let { ModelLibrary.fits(model, it, memoryContext) } ?: false
            val canDownload = hardware?.let { ModelLibrary.canDownload(model, it) } ?: true
            OutlinedCard(Modifier.fillMaxWidth().testTag("model_card_${model.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ModelMark(model.name)
                        Column(Modifier.weight(1f).clickable { details = model }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(model.name.substringBefore(" · "), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${model.filename.substringAfterLast('.').uppercase()} · ${model.quantization}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (installed) Icon(Icons.Default.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    if (installed || downloading || model.downloadState in setOf("PAUSED", "ERROR") || isSecondary) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (isSecondary) Text("Fallback", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(if (installed) "Downloaded" else ModelPresentation.status(model), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("model_status_${model.id}"))
                    }
                    Text(
                        "${ModelPresentation.size(model.fileSizeMb)} ${if (installed) "on device" else "download"} · ~${ModelPresentation.size(ModelLibrary.memoryEstimate(model, memoryContext).totalMb)} RAM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        ModelPresentation.memoryLabel(model, loadProfile, selected, memoryContext).let {
                            when {
                                selected -> "In use"
                                loadProfile == null -> "Checking available RAM…"
                                fits -> "Fits available RAM"
                                it.contains("than this phone") -> "Too large for this phone"
                                it.contains("64-bit") -> "Requires a supported 64-bit device"
                                it.startsWith("Can run") -> "Not supported on this device"
                                else -> "Free more RAM to run"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fits || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("model_memory_${model.id}")
                    )
                    if (ModelLibrary.category(model) == "EXPERIMENTAL") Text("Experimental · May be unstable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (!installed && !canDownload) Text("Free more storage before downloading this model.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (model.downloadState == "ERROR") Text(downloadErrors[model.id] ?: "Download interrupted. Tap Retry to resume.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (downloading) {
                        val animatedProgress by animateFloatAsState(
                            targetValue = model.downloadProgress.coerceIn(0f, .99f),
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            label = "download_progress"
                        )
                        if (model.checksumVerificationStatus == "VERIFYING") LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
                        Text(if (model.checksumVerificationStatus == "VERIFYING") "Checking the downloaded file…"
                            else "${ModelPresentation.size((model.fileSizeMb * model.downloadProgress.coerceIn(0f, .99f)).toLong())} of ${ModelPresentation.size(model.fileSizeMb)}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(min = 28.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                        when {
                            downloading -> OutlinedButton(
                                onClick = { viewModel.cancelDownload(model.id) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) { Text("Pause", style = MaterialTheme.typography.labelMedium) }
                            installed -> Button(
                                onClick = { viewModel.switchModel(model) },
                                enabled = loading == null && deletingId == null && !selected && fits && !telemetry.isRunning,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 40.dp).testTag("load_model_${model.id}")
                            ) {
                                Text(if (loading == model.id) "Loading…" else if (selected) "In use" else "Use model", style = MaterialTheme.typography.labelMedium)
                            }
                            else -> Button(
                                onClick = { viewModel.downloadModel(model) },
                                enabled = canDownload,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 40.dp).testTag("download_model_${model.id}")
                            ) { Text(if (model.downloadState == "ERROR") "Retry download" else if (model.downloadState == "PAUSED") "Resume download" else "Download", style = MaterialTheme.typography.labelMedium) }
                        }
                        if (model.downloadState in setOf("DOWNLOADING", "QUEUED", "PAUSED", "ERROR")) TextButton(
                            onClick = { partialDelete = model }, enabled = deletingId == null,
                            modifier = Modifier.testTag("delete_partial_${model.id}")) { Text(if (deletingId == model.id) "Deleting…" else "Delete partial download") }
                        TextButton(
                            onClick = { details = model },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 40.dp).testTag("model_details_${model.id}")
                        ) { Text("Details", style = MaterialTheme.typography.labelMedium) }
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
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model.description, style = MaterialTheme.typography.bodyMedium)
                val memoryContext = ModelPresentation.loadContext(model, generationParams.contextSize)
                Text("${ModelPresentation.size(model.fileSizeMb)} download · ~${ModelPresentation.size(ModelLibrary.memoryEstimate(model, memoryContext).totalMb)} RAM", style = MaterialTheme.typography.bodyMedium)
                Text("RAM changes with context size and processor. GPU shares system memory.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                loadProfile?.let { profile -> ModelLibrary.incompatibilityReason(model, profile, memoryContext)?.let { Text(it) } }
                Text("${model.quantization} · ${model.contextLength} token model context")
                Text("Integrity: ${model.checksumVerificationStatus}", style = MaterialTheme.typography.bodySmall)
                downloadErrors[model.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                model.huggingFaceRepo?.let { repo ->
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/$repo"))) }) { Text("Model source and license") }
                }

                if (model.isInstalled && active?.id != model.id && secondary?.id != model.id && ModelLibrary.category(model) != "EXPERIMENTAL") {
                    TextButton(onClick = { viewModel.setSecondaryModel(model); details = null }) { Text("Use as low-memory fallback") }
                }
                if (model.isInstalled) {
                    TextButton(onClick = { viewModel.verifyModelChecksum(model) }, enabled = verifying == null && deletingId == null) { Text(if (verifying == model.id) "Verifying…" else "Verify file") }

                }
                if (model.isInstalled || model.isCustom) {
                    TextButton(onClick = { details = null; deleting = model },
                        modifier = Modifier.testTag("delete_model_${model.id}"),
                        enabled = deletingId == null && loading == null && verifying == null && !(active?.id == model.id && telemetry.isRunning),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete from device") }
                }
            }
        }, confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } })
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
