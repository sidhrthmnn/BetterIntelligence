package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.HuggingFaceGgufFile
import com.example.engine.HuggingFaceModelSearchResult
import com.example.ui.screens.ModelPresentation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceDiscoveryCard(
    searchQuery: String, searchResults: List<HuggingFaceModelSearchResult>, isSearching: Boolean,
    selectedRepoId: String?, repoGgufFiles: List<HuggingFaceGgufFile>, isLoadingFiles: Boolean,
    onSearchQueryChange: (String) -> Unit, onSearch: (String) -> Unit, onSelectRepo: (String) -> Unit,
    onClearRepoSelection: () -> Unit, onDownloadGgufFile: (String, HuggingFaceGgufFile) -> Unit,
    modifier: Modifier = Modifier, initiallyExpanded: Boolean = false, availableRamMb: Long? = null
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var lastSearch by remember { mutableStateOf<String?>(null) }
    fun search(value: String) { lastSearch = value; onSearch(value) }
    Column(modifier.fillMaxWidth().testTag("hf_discovery_card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!initiallyExpanded) TextButton(onClick = { expanded = !expanded }) {
            Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Find models")
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(searchQuery, onSearchQueryChange, placeholder = { Text("Search models or repository", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { onSearchQueryChange("") }) { Icon(Icons.Default.Close, "Clear search", Modifier.size(18.dp)) } },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).testTag("hf_search_input"))
                FilledIconButton(onClick = { search(searchQuery) }, enabled = !isSearching && searchQuery.isNotBlank(),
                    modifier = Modifier.size(48.dp).testTag("hf_search_submit_btn"), shape = RoundedCornerShape(14.dp)) {
                    if (isSearching) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Default.Search, "Search models")
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Qwen" to "qwen3 gguf", "Gemma" to "gemma gguf", "LFM" to "lfm3 gguf", "Small models" to "1b gguf")) { (label, value) ->
                    SuggestionChip(onClick = { onSearchQueryChange(value); search(value) }, label = { Text(label) }, shape = RoundedCornerShape(50))
                }
            }
            if (isSearching) Text("Finding models…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (searchResults.isNotEmpty()) {
                Text("${searchResults.size} models", style = MaterialTheme.typography.labelLarge)
                searchResults.forEach { repo ->
                    ScreenCard(Modifier.testTag("hf_model_${repo.repoId}")) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ModelMark(repo.repoId)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(repo.repoId.substringAfter('/').removeSuffix("-GGUF").replace('-', ' '), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(repo.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            val count = repo.downloads.toLong()
                            Text(if (count >= 1000000) "${"%.1f".format(count / 1000000.0)}M downloads" else if (count >= 1000) "${count / 1000}K downloads" else "$count downloads",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { onSelectRepo(repo.repoId) }, contentPadding = PaddingValues(horizontal = 14.dp),
                                modifier = Modifier.testTag("hf_download_${repo.repoId}")) {
                                Icon(Icons.Default.Download, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Download")
                            }
                        }
                    }
                }
                Text("Choose a file size before downloading. Compatibility is checked before loading.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!isSearching && lastSearch == searchQuery && searchQuery.isNotBlank()) {
                ScreenCard { Text("No models found", style = MaterialTheme.typography.titleMedium)
                    Text("Try another name or a public repository ID.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (selectedRepoId != null) ModalBottomSheet(onDismissRequest = onClearRepoSelection,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Choose a version", style = MaterialTheme.typography.titleLarge)
                    Text(selectedRepoId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis) }
                IconButton(onClick = onClearRepoSelection) { Icon(Icons.Default.Close, "Close model versions") }
            }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/$selectedRepoId"))) }) {
                Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Model card & licence")
            }
            when {
                isLoadingFiles -> Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("Checking available files…")
                }
                repoGgufFiles.isEmpty() -> ScreenCard {
                    Text("No supported download files", style = MaterialTheme.typography.titleMedium)
                    Text("For gated models, download from the publisher and use Import model.", style = MaterialTheme.typography.bodyMedium)
                }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { Text("Smallest RAM estimate first. Estimates depend on context and model architecture.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(repoGgufFiles.sortedBy { it.recommendedRamMb }, key = { it.filename }) { file -> ScreenCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(file.filename, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            QuantBadge(file.quantization)
                        }
                        Text("${ModelPresentation.size(file.sizeMb.toLong())} download · ~${ModelPresentation.size(file.recommendedRamMb.toLong())} RAM",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (availableRamMb != null && file.recommendedRamMb > availableRamMb) Text("More free RAM may be needed to run this version.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (file.sha256Oid.isNullOrBlank()) "No publisher checksum" else "Checksum available", Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { onDownloadGgufFile(selectedRepoId, file); onClearRepoSelection() }, modifier = Modifier.testTag("hf_file_download_${file.filename}")) {
                                Icon(Icons.Default.Download, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Download")
                            }
                        }
                    } }
                }
            }
        }
    }
}
