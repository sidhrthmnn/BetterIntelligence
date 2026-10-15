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
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import com.example.ui.components.HardwareDiagnosticCard
import com.example.ui.components.HuggingFaceDiscoveryCard
import com.example.ui.components.HardwarePerformanceComparisonCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(viewModel: AiCoreViewModel) {
    val models by viewModel.allModels.collectAsState()
    val active by viewModel.activeModel.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val hardware by viewModel.hardwareProfile.collectAsState()
    val recommendation by viewModel.modelRecommendation.collectAsState()
    val loading by viewModel.loadingModelId.collectAsState()
    val terms by viewModel.licenseModel.collectAsState()
    val verifying by viewModel.verifyingModelId.collectAsState()
    val context = LocalContext.current
    var source by rememberSaveable { mutableStateOf("library") }
    var tier by rememberSaveable { mutableStateOf("ALL") }
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
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("import_gguf_btn")) { Text("Import a GGUF file") }
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
                listOf("ALL" to "All", "LIGHTWEIGHT" to "Lightweight", "BALANCED" to "Balanced", "QUALITY" to "More capable").forEach { (key, label) ->
                    FilterChip(tier == key, { tier = key }, label = { Text(label) })
                }
            }
        }
        val visibleModels = models.filter {
            when (source) {
                "library" -> !it.isCustom && (tier == "ALL" || it.recommendedTier == tier)
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
            val fits = hardware?.let { ModelLibrary.fits(model, it) } ?: true
            val canDownload = hardware?.let { ModelLibrary.canDownload(model, it) } ?: true
            OutlinedCard(Modifier.fillMaxWidth().testTag("model_card_${model.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text("${model.fileSizeMb} MB download · About ${model.ramRequiredMb} MB memory",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected) Text("Selected · available to approved apps", color = MaterialTheme.colorScheme.primary)
                    Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!selected && !fits) Text("You can keep this model downloaded, but it may need more free memory before it can run.", style = MaterialTheme.typography.bodySmall)
                    if (!installed && !canDownload) Text("Free more storage before downloading this model.", color = MaterialTheme.colorScheme.error)
                    if (model.downloadState == "ERROR") Text("Download failed. Check your connection and try again.", color = MaterialTheme.colorScheme.error)
                    if (downloading) {
                        LinearProgressIndicator(progress = { model.downloadProgress }, modifier = Modifier.fillMaxWidth())
                        Text(if (model.checksumVerificationStatus == "VERIFYING") "Checking download…" else "Downloading ${(model.downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            downloading -> OutlinedButton(onClick = { viewModel.cancelDownload(model.id) }) { Text("Cancel") }
                            installed -> Button(onClick = { viewModel.switchModel(model) }, enabled = loading == null && !selected && fits,
                                modifier = Modifier.testTag("load_model_${model.id}")) {
                                Text(if (loading == model.id) "Loading…" else if (selected) "In use" else "Use model")
                            }
                            else -> Button(onClick = { viewModel.downloadModel(model) }, enabled = canDownload,
                                modifier = Modifier.testTag("download_model_${model.id}")) { Text(if (model.downloadState == "ERROR") "Retry download" else "Download") }
                        }
                        TextButton(onClick = { details = model }) { Text("Details") }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced tools" else "Advanced tools") }
        }
        if (advanced) {
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
                    TextButton(onClick = { viewModel.verifyModelChecksum(model) }, enabled = verifying == null) { Text(if (verifying == model.id) "Verifying…" else "Verify file") }
                    if (active?.id != model.id) TextButton(onClick = { details = null; deleting = model }) { Text("Remove download") }
                }
            }
        }, confirmButton = { TextButton(onClick = { details = null }) { Text("Done") } })
    }
    deleting?.let { model ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${model.name}?") },
            text = { Text("You can download it again later.") },
            confirmButton = { TextButton(onClick = { viewModel.uninstallModel(model); deleting = null }) { Text("Remove") } },
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
