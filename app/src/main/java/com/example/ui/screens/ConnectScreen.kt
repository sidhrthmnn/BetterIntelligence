package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.example.tasks.TaskAccess
import com.example.tasks.TaskModels
import com.example.tasks.TaskType
import com.example.ui.components.ScreenCard
import com.example.ui.components.DetailDisclosure
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.ClientAppPolicyEntity
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab

@Composable
fun ConnectScreen(viewModel: AiCoreViewModel) {
    val policies by viewModel.clientPolicies.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val active by viewModel.activeModel.collectAsState()
    val loadingModelId by viewModel.loadingModelId.collectAsState()
    val preparing = !telemetry.isModelLoaded && (active != null || loadingModelId != null)
    var developer by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().testTag("connect_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Your local AI, in the apps you allow.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (telemetry.isModelLoaded) "Local AI is ready" else if (preparing) "Preparing local AI" else "Set up a model first", style = MaterialTheme.typography.titleMedium)
                    Text(if (telemetry.isModelLoaded) active?.name?.substringBefore(" · ") ?: "Local model" else if (preparing) "Your selected model is loading. Connected apps can retry when it is ready." else "Download and select a model to enable AI for your apps.")
                    if (!telemetry.isModelLoaded && !preparing) Button(onClick = { viewModel.selectTab(AppTab.MODELS) }) { Text("Choose a model") }
                }
            }
        }
        item { DetailDisclosure("Connect an app", "Set up local AI in TypeRight or another supported app") {
            Text("1. Select Better Intelligence in the app’s AI settings.\n2. Request a polish or AI action.\n3. Allow the app here, then retry.", style = MaterialTheme.typography.bodyMedium)
            Text("The other app must support this connection. Installing BI does not change its AI provider.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Reinstalled BI? Retry the connection in the other app, then allow it here again. You do not need to reinstall the other app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { Text("App access", style = MaterialTheme.typography.titleMedium) }
        if (policies.isEmpty()) item {
            OutlinedCard(Modifier.fillMaxWidth().testTag("no_connected_apps")) {
                Column(Modifier.padding(20.dp)) {
                    Text("No apps have requested access yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Select Better Intelligence in a supported app. Its request will appear here for approval.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(policies.sortedBy { it.isWhitelisted }, key = { it.packageName }) { policy ->
            AppAccessCard(policy, viewModel)
        }
        item {
            Text("Requests stay local. Memory sharing needs separate permission.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { developer = !developer }) { Text(if (developer) "Hide developer details" else "For app developers") }
            if (developer) {
                Text("Use ContentResolver.call() with authority com.aistudio.betterintelligence.mobile.provider and methods status or polish. Declare the local AI permission and package visibility. Run calls off the main thread. See the integration guide in the repository.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AppAccessCard(policy: ClientAppPolicyEntity, viewModel: AiCoreViewModel) {
    var expanded by rememberSaveable(policy.packageName) { mutableStateOf(false) }
    var approving by rememberSaveable(policy.packageName) { mutableStateOf(false) }
    val activity by viewModel.taskActivity.collectAsState(initial = emptyList())
    val last = activity.firstOrNull { it.callingPackage == policy.packageName }
    val allModels by viewModel.allModels.collectAsState()
    val installedModels = remember(allModels) { allModels.filter { it.isInstalled } }
    val textModels = installedModels.filter { TaskType.TEXT_GENERATION in TaskModels.capabilities(it) }
    val connected = policy.isWhitelisted && !policy.isBlocked
    val taskPermissions = listOf(TaskAccess.TEXT, TaskAccess.VISION, TaskAccess.AUDIO, TaskAccess.VOICE, TaskAccess.INTENSIVE, TaskAccess.DOWNLOAD)
    fun availability(access: TaskAccess): String? = when (access) {
        TaskAccess.INTENSIVE -> "Required for image and audio analysis"
        TaskAccess.DOWNLOAD -> "Each download still needs your approval"
        TaskAccess.VOICE -> "Uses an installed offline Android voice"
        else -> if (installedModels.none { model -> TaskModels.capabilities(model).any { it.access == access } })
            "Install a compatible model first" else null
    }
    val context = LocalContext.current
    val icon by produceState<android.graphics.Bitmap?>(null, policy.packageName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching {
            context.packageManager.getApplicationIcon(policy.packageName).toBitmap(48, 48)
        }.getOrNull() }
    }
    ScreenCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon!!.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                else Icon(Icons.Default.Apps, null, Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(policy.appName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (policy.isBlocked) "Denied" else if (policy.isWhitelisted) "Allowed · On-device AI" else "Needs approval",
                        style = MaterialTheme.typography.labelMedium)
                }
                Switch(checked = connected,
                    onCheckedChange = { if (it) approving = true else { viewModel.setClientConnection(policy.packageName, false); expanded = false } },
                    enabled = !policy.isBlocked,
                    modifier = Modifier.testTag("allow_app_${policy.packageName}"))
            }
            if (policy.totalRequests > 0) Text("Last used ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(policy.lastAccessTimestamp))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connected) {
                val skills = TaskModels.implemented.map { it.access }.distinct().filter { access ->
                    if (access == TaskAccess.TEXT) policy.allowInference else policy.taskScopes and access.bit != 0
                }.map { permissionLabel(it) }
                Text(if (skills.isEmpty()) "Choose what this app can use" else skills.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                last?.let { Text("Last task: ${it.requestType.lowercase().replace('_', ' ')} · ${it.status.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall) }
            }
            if (connected || policy.isBlocked) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide permissions" else "Permissions and limits") }
            if (expanded && (connected || policy.isBlocked)) {
                Text(policy.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connected) {
                    taskPermissions.forEach { access ->
                        val enabled = if (access == TaskAccess.TEXT) policy.allowInference else policy.taskScopes and access.bit != 0
                        val detail = if (enabled && access in setOf(TaskAccess.VISION, TaskAccess.AUDIO) &&
                            policy.taskScopes and TaskAccess.INTENSIVE.bit == 0) "Also enable high-memory tasks" else availability(access)
                        PermissionRow(permissionLabel(access), enabled, detail, tag = "permission_${access.name}") {
                            if (access == TaskAccess.TEXT) viewModel.togglePolicyInference(policy)
                            else viewModel.setTaskAccess(policy.packageName, access, !enabled)
                        }
                    }
                    HorizontalDivider()
                ModelSelectionRow(
                    label = "Preferred text model",
                    selectedId = policy.primaryModelId,
                    models = textModels,
                    onSelect = { viewModel.updateClientPrimaryModel(policy, it) }
                )
                ModelSelectionRow(
                    label = "Fallback text model",
                    selectedId = policy.fallbackModelId,
                    models = textModels,
                    onSelect = { viewModel.updateClientFallbackModel(policy, it) }
                )
                PermissionRow("Structured text operations", policy.allowStructuredJson) { viewModel.togglePolicyStructuredJson(policy) }
                PermissionRow("App-specific context", policy.allowAppContext) { viewModel.togglePolicyAppContext(policy) }
                PermissionRow("Read shared memories", policy.allowReadMemory) { viewModel.togglePolicyReadMemory(policy) }
                PermissionRow("Write shared memories", policy.allowWriteMemory) { viewModel.togglePolicyWriteMemory(policy) }
                Text("Requests per minute: ${policy.rateLimitPerMin}", style = MaterialTheme.typography.bodyMedium)
                Slider(value = policy.rateLimitPerMin.toFloat().coerceIn(1f, 120f), onValueChange = { viewModel.updatePolicyRateLimit(policy, it.toInt()) },
                    valueRange = 1f..120f, steps = 118)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (connected) TextButton(onClick = { viewModel.revokeClientPermissions(policy) },
                        modifier = Modifier.testTag("revoke_permissions_${policy.packageName}")) {
                        Text("Revoke permissions", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { viewModel.toggleClientBlocked(policy.packageName, !policy.isBlocked) }) {
                        Text(if (policy.isBlocked) "Unblock app" else "Block app")
                    }
                }
            }
        }
    }
    if (approving) {
        var grants by rememberSaveable { mutableIntStateOf(0) }
        var text by rememberSaveable { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { approving = false }, title = { Text("Allow ${policy.appName}?") },
            text = {
                Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState())) {
                    taskPermissions.forEach { access ->
                        val selected = if (access == TaskAccess.TEXT) text else grants and access.bit != 0
                        PermissionRow(permissionLabel(access), selected, availability(access), tag = "grant_${access.name}") {
                            if (access == TaskAccess.TEXT) text = !text
                            else grants = if (selected) grants and access.bit.inv() else grants or access.bit
                        }
                    }
                }
            }, confirmButton = { TextButton(modifier = Modifier.testTag("approve_connection"), enabled = text || grants != 0, onClick = {
                val selected = taskPermissions.filter { if (it == TaskAccess.TEXT) text else grants and it.bit != 0 }.toSet()
                viewModel.setClientConnection(policy.packageName, true, selected)
                approving = false
            }) { Text("Allow selected") } },
            dismissButton = { TextButton(onClick = { approving = false }) { Text("Cancel") } })
    }
}

private fun permissionLabel(access: TaskAccess): String = when (access) {
    TaskAccess.TEXT -> "Text and proofreading"
    TaskAccess.INTENSIVE -> "High-memory tasks"
    else -> access.label
}

@Composable
private fun ModelSelectionRow(
    label: String,
    selectedId: Long?,
    models: List<com.example.data.ModelEntity>,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentName = if (selectedId == null) "Default" else (models.find { it.id == selectedId }?.name?.substringBefore(" · ") ?: "Default")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(currentName)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Default") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.name.substringBefore(" · ")) },
                        onClick = {
                            onSelect(model.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, detail: String? = null, tag: String? = null, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = { onToggle() }, modifier = if (tag == null) Modifier else Modifier.testTag(tag))
    }
}
