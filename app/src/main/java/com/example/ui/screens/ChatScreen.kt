package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.flow.conflate
import com.example.ui.components.SourceReferences
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
import com.example.engine.ModelTierRole

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(viewModel: AiCoreViewModel) {
    val messages by viewModel.chatMessages.collectAsState()
    val model by viewModel.activeModel.collectAsState()
    val secondary by viewModel.secondaryModel.collectAsState()
    val tierDecision by viewModel.activeTierDecision.collectAsState()
    val hardware by viewModel.hardwareProfile.collectAsState()
    val generating by viewModel.isChatGenerating.collectAsState()
    val stream by viewModel.streamingChatText.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val conversation by viewModel.conversationId.collectAsState()
    val status by viewModel.chatStatus.collectAsState()
    val allowed by viewModel.webAllowed.collectAsState()
    val ready by viewModel.telemetry.collectAsState()
    val editing by viewModel.isEditingMessage.collectAsState()
    val pendingReplyId by viewModel.pendingReplyId.collectAsState()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(
                onClick = { viewModel.selectTab(AppTab.MODELS) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 34.dp)
            ) {
                Text(
                    model?.let { selected ->
                        listOfNotNull(ModelLibrary.categoryLabel(selected), selected.name.substringBefore(" · ")).joinToString(" · ")
                    } ?: "Choose a model",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp))
            }
            if (tierDecision?.role == ModelTierRole.SECONDARY_FALLBACK) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        "Secondary · Low RAM",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
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
                } else if (ready.isModelLoaded && !keyboardVisible) {
                    Spacer(Modifier.height(20.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Explain how on-device AI works",
                            "Give me 5 daily productivity tips",
                            "Write a polite follow-up email"
                        ).forEach { suggestion ->
                            SuggestionChip(
                                onClick = { viewModel.updateDraft(suggestion) },
                                label = { Text(suggestion, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }
        } else key(conversation) {
            ChatTranscript(messages, generating, stream, model?.name ?: "Better Intelligence",
                { clipboard.setText(AnnotatedString(it)) }, Modifier.weight(1f).fillMaxWidth(),
                onEdit = { viewModel.editMessage(it) }, onRegenerate = { viewModel.regenerateResponse() }, status = status, pendingReplyId = pendingReplyId)
        }
        Surface(Modifier.fillMaxWidth().testTag("chat_composer"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(8.dp)) {
                        AnimatedVisibility(
                            visible = editing,
                            enter = expandVertically(tween(160, easing = FastOutSlowInEasing)) + fadeIn(tween(140)),
                            exit = shrinkVertically(tween(140, easing = FastOutSlowInEasing)) + fadeOut(tween(100))
                        ) {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Editing a message", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    TextButton(onClick = { viewModel.cancelEditingMessage() }) { Text("Cancel edit") }
                                }
                                Text("Sending replaces later replies in this conversation.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextField(value = draft, onValueChange = { viewModel.updateDraft(it) },
                            placeholder = { Text("Message Better Intelligence…") },
                            modifier = Modifier.fillMaxWidth().testTag("chat_input_field"), maxLines = 5,
                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                TextButton(onClick = { viewModel.selectTab(AppTab.WEB) },
                                    modifier = Modifier.testTag("chat_grounding")) {
                                    Icon(Icons.Default.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                    Text(if (allowed) "Research: Automatic" else "Enable web research", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
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
    onEdit: (ChatMessageEntity) -> Unit = {}, onRegenerate: () -> Unit = {}, status: String = "", pendingReplyId: Long? = null
) {
    val density = LocalDensity.current
    val showDistance = with(density) { 160.dp.toPx() }
    val hideDistance = with(density) { 48.dp.toPx() }
    var awayFromLatest by remember { mutableStateOf(false) }
    var followLatest by remember { mutableStateOf(true) }
    var programmatic by remember { mutableStateOf(false) }
    val contentSignal by rememberUpdatedState(Triple(streamingText.length, messages.lastOrNull(), messages.size))
    val scrollConnection = remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y > 0f) followLatest = false
            return Offset.Zero
        }
    } }
    LaunchedEffect(messages.lastOrNull { it.role == "user" }?.id) { followLatest = true }
    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            val layout = listState.layoutInfo; val last = layout.visibleItemsInfo.lastOrNull()
            listOf(layout.totalItemsCount, layout.viewportEndOffset, last?.index, last?.offset, last?.size,
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress)
        }.collect {
            val movedEarlier = listState.firstVisibleItemIndex < previousIndex ||
                (listState.firstVisibleItemIndex == previousIndex && listState.firstVisibleItemScrollOffset < previousOffset)
            if (!programmatic && listState.isScrollInProgress && movedEarlier) followLatest = false
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            val distance = if (!listState.canScrollForward) 0f else if (last == null) 0f
                else if (last.index >= layout.totalItemsCount - 2)
                    (last.offset + last.size - layout.viewportEndOffset + with(density) { 22.dp.toPx() }).coerceAtLeast(0f)
                else showDistance + 1
            if (followLatest) awayFromLatest = false
            else if (distance >= showDistance) awayFromLatest = true
            else if (distance <= hideDistance) {
                awayFromLatest = false
                if (!listState.isScrollInProgress || !movedEarlier &&
                    (listState.firstVisibleItemIndex > previousIndex || listState.firstVisibleItemScrollOffset > previousOffset)) followLatest = true
            }
            previousIndex = listState.firstVisibleItemIndex
            previousOffset = listState.firstVisibleItemScrollOffset
        }
    }
    // Content signals, not every layout measurement. Frame-coalesce rapid tokens;
    // scrollToItem clamps to the actual bottom, including a very tall final answer.
    LaunchedEffect(listState) {
        snapshotFlow { listOf(followLatest, contentSignal, listState.layoutInfo.viewportEndOffset) }
            .conflate().collect {
                withFrameNanos { }
                if (followLatest) {
                    programmatic = true
                    try { listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                    finally { programmatic = false }
                }
            }
    }
    Box(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection).testTag("chat_transcript"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(messages, key = { "message_${it.id}" }) { message ->
                if (message.messageState == "STREAMING") StreamingAnswer(streamingText.ifEmpty { message.content }, status)
                else MessageItem(message, { onCopy(message.content) }, { onEdit(message) },
                    onRegenerate.takeIf { message.id == messages.lastOrNull()?.id }, !isGenerating)
            }
            if (isGenerating && messages.none { it.messageState == "STREAMING" } &&
                (pendingReplyId == null || messages.none { it.id == pendingReplyId })) item(key = "streaming") { StreamingAnswer(streamingText, status) }
            item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
        }
        if (awayFromLatest) FilledTonalIconButton(onClick = { awayFromLatest = false; followLatest = true }, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).size(48.dp).testTag("jump_to_latest")) {
            Icon(Icons.Default.KeyboardArrowDown, "Jump to latest response")
        }
    }
}

@Composable
private fun StreamingAnswer(text: String, status: String) {
    if (text.isNotEmpty()) MarkdownText(text)
    else Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            AnimatedContent(
                targetState = status.ifBlank { "Preparing answer" },
                transitionSpec = { fadeIn(tween(150)).togetherWith(fadeOut(tween(120))) },
                label = "research_stage"
            ) { stageText ->
                Text(stageText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessageEntity, onCopy: () -> Unit, onEdit: () -> Unit,
    onRegenerate: (() -> Unit)?, actionsEnabled: Boolean) {
    val user = message.role == "user"
    val sources = remember(message.sourcesJson) { GroundingResult.sources(message.sourcesJson) }
    var selectedSource by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(.92f).clipToBounds()) {
            SelectionContainer { Text(message.content, Modifier.padding(14.dp).fillMaxWidth(), style = MaterialTheme.typography.bodyLarge) }
        } else SelectionContainer { MarkdownText(message.content, sources.map { it.id }.toSet()) { selectedSource = it } }
        if (!user) {
            val notice = when (message.groundingStatus) {
                "EVIDENCE_CITED" -> "Web answer · Passage checks passed; check sources"
                "PARTIALLY_SUPPORTED" -> "Partial web answer · Unsupported details omitted"
                "SOURCE_EXCERPTS" -> "Quoted web evidence · Check the source and reported time"
                "CITATIONS_MISSING" -> "Sources retrieved; the answer did not cite them"
                "SEARCH_FAILED" -> "Web search unavailable"
                "INSUFFICIENT_EVIDENCE" -> "Web answer could not be verified"
                "PRIVACY_BLOCKED" -> "Search skipped to protect possible credentials · Local-only answer"
                "LOCAL" -> "Local answer · Web not used"
                "LOCAL_UNVERIFIED" -> "Live facts not checked · Web not used"
                else -> ""
            }
            if (notice.isNotEmpty()) Text(notice, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SourceReferences(sources, selectedSource) { selectedSource = null }
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
