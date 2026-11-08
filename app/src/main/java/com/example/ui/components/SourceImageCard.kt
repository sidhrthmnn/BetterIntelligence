package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.grounding.EvidenceSource
import com.example.grounding.ReadableSourceSummary
import com.example.grounding.SourceImages
import kotlinx.coroutines.CancellationException

@Composable
fun SourceImageCard(source: EvidenceSource, webAllowed: Boolean, onSource: () -> Unit) {
    var requested by remember(source.imageUrl) { mutableStateOf(false) }
    var bitmap by remember(source.imageUrl) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(source.imageUrl) { mutableStateOf(false) }
    val currentAllowed by rememberUpdatedState(webAllowed)
    LaunchedEffect(source.imageUrl, requested, webAllowed) {
        bitmap = null; failed = false
        if (!webAllowed) { requested = false; return@LaunchedEffect }
        if (requested) try { bitmap = SourceImages.load(source) { currentAllowed } }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { failed = true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    if (!webAllowed) return
    Column(Modifier.fillMaxWidth().testTag("source_image")) {
        if (!requested) TextButton(onClick = { requested = true }) { Text("Show source image") }
        else if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), source.imageAlt.ifBlank { "Illustration from ${ReadableSourceSummary.publisher(source.url)}" },
                Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit)
            TextButton(onClick = onSource) { Text("Image · ${ReadableSourceSummary.publisher(source.url)} [${source.id}]", style = MaterialTheme.typography.labelSmall) }
        } else if (failed) Text("Source image unavailable", style = MaterialTheme.typography.labelSmall)
        else CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
    }
}
