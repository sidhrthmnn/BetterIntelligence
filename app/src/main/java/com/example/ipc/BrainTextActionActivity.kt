package com.example.ipc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AiCoreRepository
import com.example.data.IpcLogEntity
import com.example.engine.GenerationParams
import com.example.ui.components.StatusPulseIndicator
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System-wide Brain Action Activity that responds to:
 * - `Intent.ACTION_PROCESS_TEXT` (Android text selection floating toolbar in any app)
 * - `Intent.ACTION_SEND` (Android Share Sheet for `text/plain`)
 * - `com.aistudio.localaicore.ACTION_BRAIN_QUERY` (Direct explicit intent from other apps)
 */
class BrainTextActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val incomingText = extractIncomingText(intent)
        val isReadOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true) ?: true
        } else {
            true
        }
        val initialAction = intent?.getStringExtra("brain_action") ?: "SUMMARIZE"
        val callerPkg = callingPackage ?: referrer?.host ?: "android.system.selection"

        setContent {
            MyApplicationTheme(darkTheme = true) {
                BrainActionSheet(
                    initialText = incomingText,
                    initialAction = initialAction,
                    callerPackage = callerPkg,
                    canReplaceOriginalText = !isReadOnly && intent?.action == Intent.ACTION_PROCESS_TEXT,
                    onReplaceTextAndFinish = { replacement ->
                        val resultIntent = Intent().apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                putExtra(Intent.EXTRA_PROCESS_TEXT, replacement)
                            }
                            putExtra("brain_result", replacement)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onDismiss = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    private fun extractIncomingText(intent: Intent?): String {
        if (intent == null) return ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && intent.action == Intent.ACTION_PROCESS_TEXT) {
            val seq = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            if (!seq.isNullOrBlank()) return seq.toString()
        }
        if (intent.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) return shared
        }
        return intent.getStringExtra("prompt")
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: "Reviewing Q4 Android AI Brain integration milestone scheduled for Friday at 2:00 PM."
    }

    companion object {
        const val ACTION_BRAIN_QUERY = "com.aistudio.localaicore.ACTION_BRAIN_QUERY"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrainActionSheet(
    initialText: String,
    initialAction: String,
    callerPackage: String,
    canReplaceOriginalText: Boolean,
    onReplaceTextAndFinish: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { AiCoreRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    var selectedAction by remember { mutableStateOf(initialAction) }
    var outputResult by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var latencyMs by remember { mutableStateOf(0L) }

    fun executeBrainAction(action: String) {
        scope.launch {
            isProcessing = true
            val start = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                when (action) {
                    "SUMMARIZE" -> repository.engine.summarize(initialText, 50)
                    "JSON_EXTRACT" -> repository.engine.extractStructuredJson(
                        initialText,
                        arrayOf("summary", "intent", "entities", "priority", "date_time")
                    )
                    "INTENT_ROUTE" -> repository.engine.routeIntent(
                        initialText,
                        arrayOf("CREATE_NOTE", "SCHEDULE_CALENDAR", "REPLY_MESSAGE", "SAVE_KNOWLEDGE")
                    )
                    "SAVE_MEMORY" -> {
                        val id = repository.writeSharedMemoryFromApp(
                            packageName = callerPackage,
                            title = "Captured via System Text Selection",
                            content = initialText,
                            category = "Knowledge"
                        )
                        "Saved directly to Better Intelligence Shared RAG Memory (#$id).\nAll authorized apps on this device can now recall this context."
                    }
                    else -> {
                        val sys = repository.buildClientSystemPrompt(callerPackage, null)
                        repository.engine.generateSync(initialText, GenerationParams(systemPrompt = sys, maxTokens = 256))
                    }
                }
            }
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(8L)
            latencyMs = elapsed
            outputResult = result
            isProcessing = false

            repository.logIpcCall(
                IpcLogEntity(
                    callingPackage = callerPackage,
                    callingUid = Process.myUid(),
                    requestType = "PROCESS_TEXT_$action",
                    promptPreview = initialText.take(150),
                    responsePreview = result.take(200),
                    latencyMs = elapsed,
                    tokensPerSecond = 44f,
                    totalTokens = (result.length / 4).coerceAtLeast(1),
                    status = "SUCCESS"
                )
            )
        }
    }

    LaunchedEffect(selectedAction) {
        executeBrainAction(selectedAction)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("brain_text_action_sheet"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPulseIndicator(isActive = true)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "BETTER INTELLIGENCE • BRAIN ACTION",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Caller: $callerPackage",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_brain_action_sheet")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Brain Sheet",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Selected Input Preview
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "SELECTED INPUT PAYLOAD",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = initialText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 4
                        )
                    }
                }

                // Brain Operation Chips
                val actions = listOf(
                    Triple("SUMMARIZE", "Summarize", Icons.Default.Summarize),
                    Triple("JSON_EXTRACT", "Extract JSON", Icons.Default.DataObject),
                    Triple("INTENT_ROUTE", "Route Intent", Icons.Default.Hub),
                    Triple("SAVE_MEMORY", "Save to Brain RAG", Icons.Default.Psychology),
                    Triple("ASK_BRAIN", "Ask Brain", Icons.Default.AutoAwesome)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    actions.forEach { (id, label, icon) ->
                        val selected = selectedAction == id
                        FilterChip(
                            selected = selected,
                            onClick = { selectedAction = id },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                // Output Result Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.background,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ON-DEVICE BRAIN OUTPUT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.secondary
                            )
                            if (!isProcessing && latencyMs > 0) {
                                Text(
                                    text = "${latencyMs}ms • Local GGUF",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (isProcessing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Processing on-device via Better Intelligence...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            Text(
                                text = outputResult,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Better Intelligence Output", outputResult))
                            Toast.makeText(context, "Copied Brain output to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Output",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Result")
                    }

                    if (canReplaceOriginalText) {
                        Button(
                            onClick = { onReplaceTextAndFinish(outputResult) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Use in Calling App",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Replace Text")
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Done")
                        }
                    }
                }
            }
        }
    }
}
