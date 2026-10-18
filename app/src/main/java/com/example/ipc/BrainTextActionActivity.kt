package com.example.ipc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.AiCoreRepository
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-mediated text selection/share action. Nothing runs until the user taps Polish. */
class BrainTextActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> intent.getStringExtra("prompt")
        }.orEmpty()
        val replace = intent.action == Intent.ACTION_PROCESS_TEXT &&
            !intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        setContent {
            MyApplicationTheme {
                PolishSheet(text, replace, onReplace = {
                    setResult(Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, it))
                    finish()
                }, onClose = { finish() })
            }
        }
    }

    companion object {
        const val ACTION_BRAIN_QUERY = "com.aistudio.betterintelligence.mobile.ACTION_BRAIN_QUERY"
    }
}

@Composable
private fun PolishSheet(text: String, canReplace: Boolean, onReplace: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { AiCoreRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val telemetry by repository.engine.telemetry.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)
        .verticalScroll(rememberScrollState()).testTag("brain_text_action_sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Polish text", style = MaterialTheme.typography.headlineMedium)
        Text("English proofreading, using your local model.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Original", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Text(text.take(PolishText.MAX_CHARACTERS + 1).ifBlank { "No text was shared." })
            }
        }
        if (!telemetry.isModelLoaded) {
            Text("Download and select a model in Better Intelligence first.")
            TextButton(onClick = { context.startActivity(Intent(context, com.example.MainActivity::class.java)) }) { Text("Open model setup") }
        }
        Button(enabled = telemetry.isModelLoaded && !busy && text.isNotBlank() && text.length <= PolishText.MAX_CHARACTERS,
            onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    output = ""
                    try {
                        output = withContext(Dispatchers.Default) {
                            val params = PolishText.parameters(repository.engine.activeContextLength)
                            val result = repository.engine.generateSync(PolishText.prompt(text), params)
                            check(repository.engine.telemetry.value.completionTokens < params.maxTokens)
                            PolishText.validateOutput(text, result)
                        }
                    } catch (e: Exception) {
                        error = "Could not polish this passage. Try a shorter passage or another model."
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Polishing…" else "Polish on this device") }
        if (text.length > PolishText.MAX_CHARACTERS) Text("Select at most ${PolishText.MAX_CHARACTERS} characters.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (output.isNotEmpty()) {
            Text("Review changes", style = MaterialTheme.typography.titleLarge)
            Text(output)
            Text("Check the result before replacing your text.", style = MaterialTheme.typography.bodySmall)
            if (canReplace) Button(onClick = { onReplace(output) }) { Text("Replace selected text") }
            OutlinedButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Polished text", output))
            }) { Text("Copy result") }
        }
        TextButton(onClick = onClose) { Text("Close") }
    }
    }
}
