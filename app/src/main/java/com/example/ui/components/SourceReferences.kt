package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.grounding.EvidenceSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.DateFormat
import java.util.Date

/** Only persisted, URL-validated evidence can enter this component. */
@Composable
fun SourceReferences(sources: List<EvidenceSource>, selectedId: Int?, onDismiss: () -> Unit) {
    if (sources.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<EvidenceSource?>(null) }
    LaunchedEffect(selectedId) { selectedId?.let { id -> selected = sources.firstOrNull { it.id == id } } }
    TextButton(
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.testTag("answer_sources")
    ) {
        Text("Sources · ${sources.size} ${if (expanded) "⌃" else "⌄"}", style = MaterialTheme.typography.labelMedium)
    }
    if (sources.any { it.kind == "STRUCTURED_DATA" }) {
        val uri = LocalUriHandler.current
        Column {
            TextButton(onClick = { uri.openUri("https://open-meteo.com/") }, contentPadding = PaddingValues(0.dp)) {
                Text("Weather data by Open-Meteo", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { uri.openUri("https://creativecommons.org/licenses/by/4.0/") }, contentPadding = PaddingValues(0.dp)) {
                Text("CC BY 4.0", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(180, easing = FastOutSlowInEasing)) + fadeIn(tween(150)),
        exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) + fadeOut(tween(120))
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
            sources.forEach { source ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selected = source }
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("[${source.id}] ${source.title}", style = MaterialTheme.typography.bodyMedium)
                        Text(source.url.toHttpUrl().host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    selected?.let { source ->
        val uri = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { selected = null; onDismiss() },
            title = { Text("[${source.id}] ${source.title}") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(source.url.toHttpUrl().host, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(source.url, style = MaterialTheme.typography.bodySmall)
                    if (source.updatedAt.isNotBlank()) Text(when (source.dateKind) {
                        "PUBLISHED" -> "Page-reported publication: ${source.updatedAt.take(10)}"
                        "MODIFIED" -> "Page-reported update: ${source.updatedAt.take(10)}"
                        "SEARCH_REPORTED" -> "Search-reported date: ${source.updatedAt.take(40)}"
                        else -> "Reported date: ${source.updatedAt.take(40)}"
                    })
                    Text("Retrieved ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(source.retrievedAt))}")
                    Text("${if (source.kind == "STRUCTURED_DATA") "Public data response" else "Page text retrieved"}. Check the original source.", style = MaterialTheme.typography.bodySmall)
                    if (source.excerpt.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(source.excerpt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { runCatching { uri.openUri(source.url) } }) { Text("Open source") } },
            dismissButton = { TextButton(onClick = { selected = null; onDismiss() }) { Text("Close") } }
        )
    }
}
