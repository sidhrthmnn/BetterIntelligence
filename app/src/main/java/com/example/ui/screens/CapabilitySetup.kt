package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.ModelEntity
import com.example.engine.ModelLibrary
import com.example.engine.ProcessingMode
import com.example.tasks.*
import com.example.ui.AiCoreViewModel
import com.example.ui.components.DetailDisclosure

@Composable
internal fun CapabilityFilters(selection: String, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("All", "Text", "Vision", "Image", "Voice", "Audio", "Video").forEach { label ->
            FilterChip(selected = selection == label, onClick = { select(label) }, label = { Text(label) },
                modifier = Modifier.testTag("capability_filter_$label"))
        }
    }
}

@Composable
internal fun MyAiSetup(viewModel: AiCoreViewModel, models: List<ModelEntity>) {
    val settings by viewModel.taskSettings.collectAsState()
    val acceleration by viewModel.accelerationCapabilities.collectAsState()
    var selected by rememberSaveable { mutableStateOf(TaskType.TEXT_GENERATION) }
    val configured = settings.getValue(selected)
    val options = models.filter { selected in TaskModels.capabilities(it) }
    DetailDisclosure("My AI Setup", "Choose what each AI capability uses") {
        OptionMenu("Capability", selected, TaskType.entries, { it.label }, { selected = it })
        if (selected !in TaskModels.implemented) {
            Text("Unavailable on this build", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TaskSwitch("Enabled", configured.enabled) { viewModel.setTaskSettings(selected, configured.copy(enabled = it)) }
            if (selected == TaskType.SPEECH_GENERATION) {
                Text("Uses an installed Android voice that works offline.", style = MaterialTheme.typography.bodySmall)
            } else {
                TaskSwitch("Choose models automatically", configured.auto) { viewModel.setTaskSettings(selected, configured.copy(auto = it)) }
                val ids = listOf<Long?>(null) + options.sortedByDescending { it.isInstalled }.map { it.id }
                fun label(id: Long?): String = options.find { it.id == id }?.let {
                    it.name.substringBefore(" · ") + if (it.isInstalled) "" else " (download required)"
                } ?: if (selected in TaskModels.text) "Default text setup" else "Automatic"
                OptionMenu("Primary model", configured.primaryId, ids, ::label) {
                    viewModel.setTaskSettings(selected, configured.copy(primaryId = it, fallbackId = configured.fallbackId.takeUnless { fallback -> fallback == it }))
                }
                OptionMenu("Fallback model", configured.fallbackId, ids.filter { it == null || it != configured.primaryId }, ::label) {
                    viewModel.setTaskSettings(selected, configured.copy(fallbackId = it))
                }
            }
            val backends = if (selected in TaskModels.text) buildList {
                add(ProcessingMode.AUTO); add(ProcessingMode.CPU)
                if (acceleration.gpuAvailable) add(ProcessingMode.GPU)
                if (BuildConfig.QUALCOMM_NPU_ENABLED && acceleration.npuAvailable) add(ProcessingMode.NPU)
            } else listOf(ProcessingMode.AUTO, ProcessingMode.CPU)
            if (selected != TaskType.SPEECH_GENERATION) {
                OptionMenu("Processor", configured.backend, backends, { it.title }) {
                    viewModel.setTaskSettings(selected, configured.copy(backend = it))
                }
                OptionMenu("Quality", configured.quality, TaskQuality.entries, { it.label }) {
                    viewModel.setTaskSettings(selected, configured.copy(quality = it))
                }
            }
        }
    }
}

@Composable
internal fun TaskResourceSettings(viewModel: AiCoreViewModel) {
    val policy by viewModel.taskResourcePolicy.collectAsState()
    DetailDisclosure("Task resources", "Memory and battery for connected apps") {
        TaskSwitch("Keep the text model ready", policy.keepModelReady) {
            viewModel.setTaskResourcePolicy(policy.copy(keepModelReady = it))
        }
        TaskSwitch("Pause intensive tasks in Battery Saver", policy.respectBatterySaver) {
            viewModel.setTaskResourcePolicy(policy.copy(respectBatterySaver = it))
        }
        OptionMenu("Model memory limit", policy.maxMemoryMb, listOf(0, 1024, 2048, 3072, 4096, 6144, 8192),
            { if (it == 0) "Automatic" else ModelPresentation.size(it.toLong()) }) {
            viewModel.setTaskResourcePolicy(policy.copy(maxMemoryMb = it))
        }
        Text("Android’s memory and thermal limits still apply.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun DownloadRequests(viewModel: AiCoreViewModel, models: List<ModelEntity>) {
    val requests by viewModel.modelDownloadApprovals.collectAsState()
    val approvedApps by viewModel.clientPolicies.collectAsState()
    val modelMap = models.associateBy { it.id }
    val fresh = requests.filter { approvedApps.any { app -> app.packageName == it.packageName && app.isWhitelisted && !app.isBlocked } &&
        modelMap[it.modelId]?.isInstalled == false }
    if (fresh.isEmpty()) return
    DetailDisclosure("Requested downloads", "${fresh.size} awaiting your approval") {
        fresh.forEach { request ->
            val model = modelMap.getValue(request.modelId)
            val caller = approvedApps.first { it.packageName == request.packageName }
            Text("${caller.appName} requested ${model.name}", style = MaterialTheme.typography.bodyMedium)
            Text("${ModelPresentation.size(model.fileSizeMb)} download", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.reviewRequestedDownload(request, true) }) { Text("Download") }
                TextButton(onClick = { viewModel.reviewRequestedDownload(request, false) }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
internal fun SmartModelCollection(viewModel: AiCoreViewModel, models: List<ModelEntity>) {
    val hardware by viewModel.hardwareProfile.collectAsState()
    val resources by viewModel.taskResourcePolicy.collectAsState()
    var tier by rememberSaveable { mutableStateOf("Balanced") }
    var confirm by remember { mutableStateOf<Triple<ModelEntity, ModelEntity?, Long>?>(null) }
    DetailDisclosure("Smart Setup", "Review an AI collection for this device") {
        OptionMenu("Collection", tier, listOf("Essential", "Balanced", "Advanced"), { it }, { tier = it })
        val memory = hardware?.takeIf { !it.isLowMemory }?.safeModelRamLimitMb ?: 0
        val cap = resources.maxMemoryMb.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE
        val candidates = models.filter {
            TaskType.TEXT_GENERATION in TaskModels.capabilities(it) && ModelLibrary.automaticUseAllowed(it) &&
                (!it.architecture.startsWith("gemma") || it.architecture.startsWith("gemma4")) &&
                (it.isInstalled || it.downloadUrl != null && !it.sha256Checksum.isNullOrBlank()) &&
                TaskModels.estimateMb(it, TaskType.TEXT_GENERATION) <= minOf(memory, cap)
        }
        val text = when (tier) {
            "Essential" -> candidates.minByOrNull { TaskModels.estimateMb(it, TaskType.TEXT_GENERATION) }
            "Advanced" -> candidates.maxByOrNull { TaskModels.estimateMb(it, TaskType.TEXT_GENERATION) }
            else -> candidates.filter { ModelLibrary.category(it) == "BALANCED" }
                .minByOrNull { TaskModels.estimateMb(it, TaskType.TEXT_GENERATION) }
                ?: candidates.minByOrNull { TaskModels.estimateMb(it, TaskType.TEXT_GENERATION) }
        }
        val media = if (tier == "Essential") null else candidates.firstOrNull {
            val capabilities = TaskModels.capabilities(it).intersect(TaskModels.gemmaMedia)
            capabilities.isNotEmpty() && TaskModels.estimateMb(it, capabilities.first()) <= minOf(memory, cap)
        }
        if (text == null) Text("No model fits the available memory.", style = MaterialTheme.typography.bodySmall)
        else {
            Text("Text · ${text.name.substringBefore(" · ")}", style = MaterialTheme.typography.bodyMedium)
            media?.let { Text("Media · ${it.name.substringBefore(" · ")}", style = MaterialTheme.typography.bodyMedium) }
            val size = listOfNotNull(text, media).distinctBy { it.id }.filterNot { it.isInstalled }.sumOf { it.fileSizeMb }
            val fits = size == 0L || size + 128 <= (hardware?.internalStorageFreeGb ?: 0f) * 1024
            Text(if (size == 0L) "Already installed" else "${ModelPresentation.size(size)} total download",
                style = MaterialTheme.typography.bodySmall)
            if (!fits) Text("Free storage or choose a smaller collection.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { confirm = Triple(text, media, size) }, enabled = fits,
                modifier = Modifier.testTag("smart_setup_review")) { Text("Review setup") }
        }
    }
    confirm?.let { (text, media, size) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Apply this AI setup?") },
            text = { Text(listOfNotNull(text, media).distinctBy { it.id }.joinToString("\n") { it.name } +
                if (size > 0) "\n\n${ModelPresentation.size(size)} will download using your network settings." else "") },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.applyCapabilityCollection(text, media, size) }) {
                Text(if (size > 0) "Approve downloads" else "Apply")
            } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun TaskSwitch(label: String, checked: Boolean, enabled: Boolean = true, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = update, enabled = enabled)
    }
}

@Composable
private fun <T> OptionMenu(label: String, selected: T, values: List<T>, name: (T) -> String, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { expanded = true }) { Text(name(selected), maxLines = 2) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { value ->
                    DropdownMenuItem(text = { Text(name(value)) }, onClick = { expanded = false; select(value) })
                }
            }
        }
    }
}
