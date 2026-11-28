package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ModelEntity
import com.example.engine.HardwareProfile
import com.example.engine.ModelLibrary
import com.example.tasks.TaskModels
import com.example.tasks.TaskType
import com.example.ui.components.ModelMark
import com.example.ui.components.ScreenCard

@Composable
internal fun ModelCollectionSummary(title: String, storage: String?, memory: String?, onImport: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            storage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(memory ?: "Checking available RAM…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("model_ram_summary"))
        }
        if (onImport != null) FilledTonalIconButton(onClick = onImport, modifier = Modifier.testTag("import_gguf_btn")) {
            Icon(Icons.Default.Add, "Import model")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelCollectionCard(
    model: ModelEntity, inDownloads: Boolean, loaded: Boolean, loading: Boolean, busy: Boolean,
    profile: HardwareProfile?, contextSize: Int, filter: String, canDownload: Boolean,
    defaultText: Boolean, textFallback: Boolean, assignments: List<ModelPresentation.Assignment>, error: String?,
    details: () -> Unit, assign: () -> Unit, use: () -> Unit, download: () -> Unit, pause: () -> Unit,
    deletePartial: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val tasks = TaskModels.capabilities(model)
    val required = ModelPresentation.estimatedRam(model, contextSize, filter)
    val compatibility = ModelPresentation.memoryLabel(required, profile, loaded && filter in setOf("All", "Text"))
    ScreenCard(Modifier.testTag("model_card_${model.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelMark(model.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.name.substringBefore(" · "), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${model.filename.substringAfterLast('.').uppercase()} · ${ModelPresentation.size(model.fileSizeMb)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("model_details_${model.id}")) {
                    Icon(Icons.Default.MoreVert, "Manage ${model.name}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Model details") }, onClick = { menu = false; details() })
                    if (model.isInstalled && tasks.isNotEmpty()) DropdownMenuItem(text = { Text("Assign to tasks") },
                        onClick = { menu = false; assign() })
                }
            }
        }
        if (inDownloads && !model.isInstalled) {
            TransferProgress(model, error, pause, download, deletePartial)
        } else {
            val labels = tasks.map(ModelPresentation::label).distinct()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (model.isInstalled) {
                    ModelChip(when { loading -> "Loading…"; loaded -> "In memory"; else -> "Downloaded" },
                        highlight = loaded, modifier = Modifier.testTag("model_status_${model.id}"))
                    if (defaultText) ModelChip("Text default")
                    if (textFallback) ModelChip("Text fallback")
                } else labels.forEach { ModelChip(it, highlight = it != "Text") }
            }
            if (assignments.isNotEmpty() && model.isInstalled) {
                val names = assignments.take(2).joinToString(" · ") { assignment ->
                    assignment.type.label + if (!assignment.enabled) " (off)" else if (assignment.fallback) " fallback" else " primary"
                }
                Text(names + if (assignments.size > 2) " +${assignments.size - 2}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("model_roles_${model.id}"))
            } else if (model.isInstalled && labels.isNotEmpty()) {
                Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (loaded) "${ModelPresentation.size(required)} estimated RAM" else
                "${ModelPresentation.size(required)} RAM · ${compatibility.substringAfter(" · ")}",
                style = MaterialTheme.typography.bodySmall,
                color = if (compatibility.startsWith("Can't run")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("model_memory_${model.id}"))
            if (!model.isInstalled && ModelLibrary.category(model) == "EXPERIMENTAL") Text("Experimental",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (model.isInstalled && TaskType.TEXT_GENERATION in tasks && !inDownloads) {
                val textFits = ModelPresentation.memoryLabel(ModelPresentation.estimatedRam(model, contextSize, "Text"), profile, false).startsWith("Can run ·")
                if (!loaded && !loading) FilledTonalButton(onClick = use, enabled = !busy && textFits,
                    modifier = Modifier.testTag("load_model_${model.id}")) { Text("Set as text default") }
            } else if (model.isInstalled && !inDownloads && tasks.isNotEmpty()) {
                FilledTonalButton(onClick = assign) { Text("Assign to tasks") }
            } else if (!model.isInstalled) {
                if (!canDownload) Text("Free storage to download", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                val pending = ModelPresentation.hasTransfer(model)
                Button(onClick = download, enabled = canDownload || ModelPresentation.transferring(model),
                    modifier = Modifier.testTag("download_model_${model.id}")) {
                    Text(if (ModelPresentation.transferring(model)) "View download" else if (pending) "Resume download" else "Download")
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TransferProgress(model: ModelEntity, error: String?, pause: () -> Unit, resume: () -> Unit, delete: () -> Unit) {
    val verifying = model.checksumVerificationStatus == "VERIFYING"
    val active = ModelPresentation.transferring(model)
    val progress = model.downloadProgress.coerceIn(0f, .99f)
    Text(ModelPresentation.status(model), style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.testTag("model_status_${model.id}"))
    if (verifying && active) LinearProgressIndicator(Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    Text("${ModelPresentation.size((model.fileSizeMb * progress).toLong())} of ${ModelPresentation.size(model.fileSizeMb)}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (model.downloadState == "ERROR" && error != null) Text(error, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (model.downloadState == "QUEUED" && error != null) Text("Retrying automatically", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = if (active) pause else resume, enabled = !verifying) {
            Text(if (active) "Pause" else if (model.downloadState == "ERROR") "Retry" else "Resume")
        }
        IconButton(onClick = delete, enabled = !verifying, modifier = Modifier.testTag("delete_partial_${model.id}")) {
            Icon(Icons.Default.DeleteOutline, "Delete partial download")
        }
    }
}

@Composable
private fun ModelChip(label: String, highlight: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = MaterialTheme.shapes.extraSmall,
        color = if (highlight) colors.primaryContainer else colors.surfaceVariant,
        contentColor = if (highlight) colors.onPrimaryContainer else colors.onSurfaceVariant) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}
