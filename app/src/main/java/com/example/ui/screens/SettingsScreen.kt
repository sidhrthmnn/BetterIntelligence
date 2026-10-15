package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.AiCoreViewModel

@Composable
fun SettingsScreen(viewModel: AiCoreViewModel) {
    val dark by viewModel.isDarkTheme.collectAsState()
    val capture by viewModel.isAutoMemoryCaptureEnabled.collectAsState()
    val memories by viewModel.allMemories.collectAsState()
    var clear by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item {
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark theme", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(dark, { viewModel.setDarkTheme(it) }, modifier = Modifier.testTag("dark_theme_toggle"))
                    }
                    Text("Your appearance choice is saved on this device.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text("Privacy", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("AI requests use the selected model on this device. Internet is used to find and download models. There is no automatic cloud fallback.")
            Spacer(Modifier.height(8.dp))
            Text("Connected apps need your approval in Apps. Read and write access to shared memories can be controlled separately.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remember facts from chat", style = MaterialTheme.typography.titleMedium)
                            Text("Off by default. Applies to this chat only; AI Polish does not save your text.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(capture, { viewModel.toggleAutoMemoryCapture(it) })
                    }
                    Text("Saved memories: ${memories.size}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(memories, key = { it.id }) { memory ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(memory.title, style = MaterialTheme.typography.titleMedium)
                    Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { viewModel.deleteMemorySnippet(memory.id) }) { Text("Delete memory") }
                }
            }
        }
        item {
            Text("Local data", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { clear = "chat" }) { Text("Clear chat history") }
            TextButton(onClick = { clear = "logs" }) { Text("Clear app access logs") }
            if (memories.isNotEmpty()) TextButton(onClick = { clear = "memories" }) { Text("Delete all memories") }
        }
        item {
            Text("About", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Better Intelligence · Local AI API v2", style = MaterialTheme.typography.bodyMedium)
            Text("GGUF models run with llama.cpp on the CPU. Model quality and speed vary by device. Review AI results before using them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (clear.isNotEmpty()) AlertDialog(onDismissRequest = { clear = "" },
        title = { Text(if (clear == "memories") "Delete all memories?" else if (clear == "logs") "Clear access logs?" else "Clear chat history?") },
        text = { Text("This deletes the selected local data permanently.") },
        confirmButton = { TextButton(onClick = {
            when (clear) {
                "chat" -> viewModel.clearChat()
                "logs" -> viewModel.clearIpcLogs()
                "memories" -> viewModel.clearAllMemories()
            }
            clear = ""
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { clear = "" }) { Text("Cancel") } })
}
