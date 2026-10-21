package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ChatMessageEntity
import com.example.grounding.*
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import com.example.ui.components.MarkdownText
import com.example.engine.ModelLibrary

@Composable
fun ChatScreen(viewModel: AiCoreViewModel) {
    val messages by viewModel.chatMessages.collectAsState()
    val model by viewModel.activeModel.collectAsState()
    val generating by viewModel.isChatGenerating.collectAsState()
    val stream by viewModel.streamingChatText.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val conversation by viewModel.conversationId.collectAsState()
    val status by viewModel.chatStatus.collectAsState()
    val allowed by viewModel.webAllowed.collectAsState()
    val mode by viewModel.groundingMode.collectAsState()
    val ready by viewModel.telemetry.collectAsState()
    val editing by viewModel.isEditingMessage.collectAsState()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var showWebMenu by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { viewModel.selectTab(AppTab.MODELS) }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(model?.let { selected -> listOfNotNull(ModelLibrary.categoryLabel(selected), selected.name.substringBefore(" · ")).joinToString(" · ") } ?: "Choose a model",
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        if (messages.isEmpty() && !generating) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
                Text("What’s on your mind?", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text("Think it through, write something, or ask a question.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!ready.isModelLoaded && !keyboardVisible) {
                    Spacer(Modifier.height(20.dp))
                    Text("Download a model to get started. Your AI runs on this device.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { viewModel.selectTab(AppTab.MODELS) }) { Text("Choose a model") }
                }
            }
        } else key(conversation) {
            ChatTranscript(messages, generating, stream, model?.name ?: "Better Intelligence",
                { clipboard.setText(AnnotatedString(it)) }, Modifier.weight(1f).fillMaxWidth(),
                onEdit = { viewModel.editMessage(it) }, onRegenerate = { viewModel.regenerateResponse() }, status = status)
        }
        Surface(Modifier.fillMaxWidth().testTag("chat_composer"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(8.dp)) {
                        if (editing) Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Editing a message", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { viewModel.cancelEditingMessage() }) { Text("Cancel edit") }
                        }
                        if (editing) Text("Sending replaces later replies in this conversation.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                        TextField(value = draft, onValueChange = { viewModel.updateDraft(it) },
                            placeholder = { Text("Message Better Intelligence…") },
                            modifier = Modifier.fillMaxWidth().testTag("chat_input_field"), maxLines = 5,
                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                TextButton(onClick = { if (allowed) showWebMenu = true else viewModel.selectTab(AppTab.WEB) },
                                    modifier = Modifier.testTag("chat_grounding")) {
                                    Icon(Icons.Default.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                    Text("Web: ${if (!allowed || mode == GroundingMode.OFF) "Off" else if (mode == GroundingMode.AUTO) "Auto" else "On"}")
                                }
                                DropdownMenu(showWebMenu, { showWebMenu = false }) {
                                    GroundingMode.entries.forEach { option -> DropdownMenuItem(text = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                        onClick = { viewModel.setGroundingMode(option); showWebMenu = false }) }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            FilledIconButton(onClick = { if (generating) viewModel.cancelGeneration() else { keyboard?.hide(); viewModel.sendChatMessage(draft) } },
                                enabled = generating || (draft.isNotBlank() && conversation != 0L),
                                modifier = Modifier.size(48.dp).testTag(if (generating) "stop_generation_btn" else "send_message_btn")) {
                                Icon(if (generating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (generating) "Stop response" else "Send message")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Follow the bottom sentinel while allowing the reader to scroll through a streaming answer. */
@Composable
internal fun ChatTranscript(
    messages: List<ChatMessageEntity>, isGenerating: Boolean, streamingText: String, modelName: String,
    onCopy: (String) -> Unit, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onEdit: (ChatMessageEntity) -> Unit = {}, onRegenerate: () -> Unit = {}, status: String = ""
) {
    val awayFromLatest by remember { derivedStateOf { listState.canScrollForward } }
    var followLatest by remember { mutableStateOf(true) }
    val scrollConnection = remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) followLatest = false
            return Offset.Zero
        }
    } }
    LaunchedEffect(messages.lastOrNull { it.role == "user" }?.id) { followLatest = true }
    LaunchedEffect(listState) { snapshotFlow { !listState.canScrollForward }.collect { if (it) followLatest = true } }
    LaunchedEffect(listState, followLatest) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        if (followLatest) snapshotFlow {
            val layout = listState.layoutInfo; val last = layout.visibleItemsInfo.lastOrNull()
            listOf(layout.totalItemsCount, layout.viewportEndOffset, last?.index, last?.offset, last?.size,
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress)
        }.collect {
            val index = listState.layoutInfo.totalItemsCount - 1
            val movedEarlier = listState.firstVisibleItemIndex < previousIndex ||
                (listState.firstVisibleItemIndex == previousIndex && listState.firstVisibleItemScrollOffset < previousOffset)
            // TalkBack/keyboard scroll actions can bypass the touch nested-scroll
            // callback. Respect those readers too, before following another token.
            if (listState.canScrollForward && (movedEarlier || listState.isScrollInProgress)) followLatest = false
            // Schedule the next measure instead of synchronously forcing a
            // remeasure from a layoutInfo snapshot (not safe during recreation).
            else if (index >= 0 && listState.canScrollForward) listState.requestScrollToItem(index)
            previousIndex = listState.firstVisibleItemIndex
            previousOffset = listState.firstVisibleItemScrollOffset
        }
    }
    Box(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection).testTag("chat_transcript"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(messages, key = { "message_${it.id}" }) { message ->
                MessageItem(message, { onCopy(message.content) }, { onEdit(message) },
                    onRegenerate.takeIf { message.id == messages.lastOrNull()?.id }, !isGenerating)
            }
            if (isGenerating) item(key = "streaming") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (streamingText.isNotEmpty()) Text(streamingText, style = MaterialTheme.typography.bodyLarge)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(status.ifBlank { "Preparing answer" }, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
        }
        if (awayFromLatest) FilledTonalButton(onClick = { followLatest = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("jump_to_latest")) {
            Icon(Icons.Default.KeyboardArrowDown, null); Text("Jump to latest")
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessageEntity, onCopy: () -> Unit, onEdit: () -> Unit,
    onRegenerate: (() -> Unit)?, actionsEnabled: Boolean) {
    val user = message.role == "user"
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Surface(color = if (user) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(18.dp), modifier = if (user) Modifier.padding(start = 32.dp) else Modifier.fillMaxWidth()) {
            SelectionContainer {
                if (user) Text(message.content, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                else MarkdownText(message.content)
            }
        }
        if (!user) {
            val notice = when (message.groundingStatus) {
                "EVIDENCE_CITED" -> "Sources cited · Check the evidence"
                "CITATIONS_MISSING" -> "Sources retrieved; the answer did not cite them"
                "SEARCH_FAILED" -> "Live sources unavailable · Model-only answer"
                "PRIVACY_BLOCKED" -> "Search skipped to protect possible credentials · Local-only answer"
                else -> ""
            }
            if (notice.isNotEmpty()) Text(notice, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            GroundingResult.sources(message.sourcesJson).forEach { source ->
                TextButton(onClick = { runCatching { uri.openUri(source.url) } }) {
                    Column {
                        Text("[${source.id}] ${source.title}")
                        Text(when (source.kind) { "WEB_PAGE" -> "Page text retrieved · Check the source"; "SEARCH_EXCERPT" -> "Saved search excerpt · Page not fetched"; else -> "Saved article summary" },
                            style = MaterialTheme.typography.labelSmall)
                        if (source.updatedAt.isNotBlank()) Text(if (source.kind == "SEARCH_EXCERPT") "Search-reported date: ${source.updatedAt.take(40)}" else "Article revised ${source.updatedAt.take(10)}", style = MaterialTheme.typography.labelSmall)
                        Text("Retrieved ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(source.retrievedAt))}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (message.messageState != "COMPLETE") Text(when (message.messageState) {
                "STOPPED" -> "Response stopped"
                "LENGTH_LIMIT" -> "Response length limit reached. Ask it to continue, or increase the response length in Advanced settings."
                else -> "Unable to complete this response. You can retry."
            },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row {
            IconButton(onCopy) { Icon(Icons.Default.ContentCopy, "Copy message", Modifier.size(18.dp)) }
            if (user) IconButton(onEdit, enabled = actionsEnabled) { Icon(Icons.Default.Edit, "Edit and resend", Modifier.size(18.dp)) }
            else if (onRegenerate != null) IconButton(onRegenerate, enabled = actionsEnabled) { Icon(Icons.Default.Refresh, "Regenerate response", Modifier.size(18.dp)) }
        }
    }
}
