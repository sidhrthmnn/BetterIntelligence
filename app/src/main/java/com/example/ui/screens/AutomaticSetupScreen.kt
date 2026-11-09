package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import com.example.ui.components.*

@Composable
fun AutomaticSetupScreen(viewModel: AiCoreViewModel) {
    val state by viewModel.automaticSetup.collectAsState()
    val models by viewModel.allModels.collectAsState()
    val downloading = models.filter { it.downloadState in setOf("DOWNLOADING", "QUEUED") }
    var approval by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf<com.example.engine.AutomaticModelPair?>(null) }
    LaunchedEffect(Unit) { viewModel.recheckDevice() }
    LazyColumn(Modifier.fillMaxSize().testTag("automatic_setup_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenCard {
            Text("Your device", style = MaterialTheme.typography.titleLarge)
            Text(state.status, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("automatic_setup_status"))
            if (state.error.isNotBlank()) Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            state.device?.let { d ->
                Text("${ModelPresentation.size(d.profile.availableRamMb)} RAM free · ${"%.1f".format(d.profile.internalStorageFreeGb)} GB storage free", style = MaterialTheme.typography.bodyMedium)
                Text(if (d.capabilities.gpuAvailable) "CPU ready · GPU available for model testing" else "CPU ready · GPU unavailable on this device", style = MaterialTheme.typography.bodySmall)
                DetailDisclosure("Device details", "Processors and compatibility") {
                    Text("${d.profile.cpuAbi} · ${d.profile.cpuCores} CPU cores", style = MaterialTheme.typography.bodySmall)
                    Text(d.capabilities.gpuReason, style = MaterialTheme.typography.bodySmall)
                    Text(if (d.capabilities.npuAvailable) "NPU validated for a compatible model" else "NPU not validated for this setup", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = viewModel::recheckDevice, modifier = Modifier.testTag("recheck_device")) { Text("Recheck my device") }
        } }
        state.pair?.let { pair ->
            items(listOfNotNull(pair.primary?.let { "Primary" to it }, pair.secondary?.let { "Low-memory backup" to it })) { (role, model) -> ScreenCard {
                Text(role, style = MaterialTheme.typography.labelLarge)
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                Text("${if (model.isInstalled) "Installed · " else ""}${ModelPresentation.size(model.fileSizeMb)} ${if (model.isInstalled) "storage" else "download"} · ~${ModelPresentation.size(com.example.engine.ModelLibrary.memoryEstimate(model, 1024).totalMb)} RAM", style = MaterialTheme.typography.bodySmall)
                val report = state.reports.lastOrNull { it.modelId == model.id && it.key.startsWith(com.example.engine.ModelQualityBenchmark.key(model, com.example.engine.ModelLibrary.configuredContext(model, 1024)) + ":") }
                Text(report?.let { "${if (it.passed) "Checked on this phone" else "Needs manual review"}: ${it.quality}/100 checks · ${"%.1f".format(it.tokensPerSecond)} tokens/s · ${it.backend}" }
                    ?: "Preliminary estimate · Not benchmarked on this device", style = MaterialTheme.typography.bodySmall)
                Text(if (role == "Primary") "For everyday answers and grounded summaries" else "For lower memory use", style = MaterialTheme.typography.bodySmall)
            } }
            item { Text(pair.reason, style = MaterialTheme.typography.bodySmall) }
            if (!state.pending && pair.primary != null) item { Button(onClick = { reviewed = pair; approval = true }, modifier = Modifier.fillMaxWidth().testTag("automatic_setup_approve")) { Text("Set up automatically") } }
        }
        items(downloading, key = { it.id }) { model -> ScreenCard {
            Text(model.name, style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { model.downloadProgress }, modifier = Modifier.fillMaxWidth())
            Text("${(model.downloadProgress * 100).toInt()}% · ${if (model.downloadState == "QUEUED") "Queued / waiting for network" else "Downloading"}", style = MaterialTheme.typography.bodySmall)
        } }
        item { OutlinedButton(onClick = viewModel::chooseModelsManually, modifier = Modifier.fillMaxWidth()) { Text("Choose models manually") } }
        item { DetailDisclosure("Setup tools", "Benchmarks, model choices and downloads") {
            TextButton(onClick = viewModel::rerunModelBenchmarks, enabled = !state.pending) { Text("Re-run benchmarks") }
            TextButton(onClick = { viewModel.selectTab(AppTab.MODELS) }) { Text("Change primary or backup model") }
            state.reports.filter { report -> models.any { model -> model.id == report.modelId && report.key.startsWith(com.example.engine.ModelQualityBenchmark.key(model, com.example.engine.ModelLibrary.configuredContext(model, 1024)) + ":") } }
                .forEach { report -> Text("Model ${report.modelId}: ${report.quality}/100 · ${if (report.passed) "Passed" else "Core checks not passed"} · ${report.backend} · load ${report.loadMs} ms · first token ${report.firstTokenMs} ms · peak ${report.peakMb} MB", style = MaterialTheme.typography.bodySmall) }
        } }
    }
    if (approval) AlertDialog(onDismissRequest = { approval = false }, title = { Text("Download selected models?") }, text = {
        Column { Text("${ModelPresentation.size(reviewed?.downloadMb ?: 0)} total download. Only the selected models are downloaded, one at a time. Local checks run when battery, temperature and RAM permit.")
            Text("Inference and quality checks stay on this device. Model publishers receive download requests.", style = MaterialTheme.typography.bodySmall) }
    }, confirmButton = { TextButton(onClick = { approval = false; viewModel.setUpAutomatically(viewModel.downloadsWifiOnly, reviewed) }) { Text("Approve setup") } },
        dismissButton = { TextButton(onClick = { approval = false }) { Text("Cancel") } })
}
