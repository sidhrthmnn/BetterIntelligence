package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Let supported apps use this device’s model for tasks such as AI Polish. You choose which apps have access.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        item {
            Text("How to connect", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("1. Choose Better Intelligence in a supported app’s AI settings.\n2. Request AI Polish or another local AI action.\n3. Return here and allow the app, then retry.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("Apps such as Typeright need to implement this connection. Installing Better Intelligence alone does not change their AI provider.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Text("App access", style = MaterialTheme.typography.titleLarge) }
        if (policies.isEmpty()) item {
            OutlinedCard(Modifier.fillMaxWidth().testTag("no_connected_apps")) {
                Column(Modifier.padding(20.dp)) {
                    Text("No apps have requested access yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Connection requests will appear here. Your AI stays private until you allow an app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(policies.sortedBy { it.isWhitelisted }, key = { it.packageName }) { policy ->
            AppAccessCard(policy, viewModel)
        }
        item {
            Text("Polish requests stay on this device. Text and results are excluded from access logs. Memory sharing is a separate choice.",
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
    val context = LocalContext.current
    val icon by produceState<android.graphics.Bitmap?>(null, policy.packageName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching {
            context.packageManager.getApplicationIcon(policy.packageName).toBitmap(48, 48)
        }.getOrNull() }
    }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon!!.asImageBitmap(), null, Modifier.size(40.dp))
                else Icon(Icons.Default.Apps, null, Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(policy.appName, style = MaterialTheme.typography.titleMedium)
                    Text(policy.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (policy.isBlocked) "Denied" else if (policy.isWhitelisted) "Approved · Local inference" else "Pending approval",
                        style = MaterialTheme.typography.labelMedium)
                }
                Switch(checked = policy.isWhitelisted && !policy.isBlocked,
                    onCheckedChange = { viewModel.togglePolicyWhitelist(policy) }, enabled = !policy.isBlocked,
                    modifier = Modifier.testTag("allow_app_${policy.packageName}"))
            }
            if (policy.totalRequests > 0) Text("Last used ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(policy.lastAccessTimestamp))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide permissions" else "Permissions and limits") }
            if (expanded) {
                PermissionRow("AI generation and polish", policy.allowInference) { viewModel.togglePolicyInference(policy) }
                PermissionRow("Read shared memories", policy.allowReadMemory) { viewModel.togglePolicyReadMemory(policy) }
                PermissionRow("Write shared memories", policy.allowWriteMemory) { viewModel.togglePolicyWriteMemory(policy) }
                Text("Requests per minute: ${policy.rateLimitPerMin}", style = MaterialTheme.typography.bodyMedium)
                Slider(value = policy.rateLimitPerMin.toFloat().coerceIn(1f, 120f), onValueChange = { viewModel.updatePolicyRateLimit(policy, it.toInt()) },
                    valueRange = 1f..120f, steps = 118)
                TextButton(onClick = { viewModel.toggleClientBlocked(policy.packageName, !policy.isBlocked) }) {
                    Text(if (policy.isBlocked) "Unblock app" else "Block app")
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
