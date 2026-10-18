package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AiCoreViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by viewModel.appearance.collectAsState()
            val generating by viewModel.isChatGenerating.collectAsState()
            LaunchedEffect(appearance) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = appearance == "LIGHT"
                    isAppearanceLightNavigationBars = appearance == "LIGHT"
                }
            }
            LaunchedEffect(generating) {
                if (generating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MyApplicationTheme(darkTheme = appearance != "LIGHT", amoled = appearance == "AMOLED") { MainAppContent(viewModel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(viewModel: AiCoreViewModel) {
    val destination by viewModel.currentTab.collectAsState()
    val message by viewModel.userMessage.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.clearUserMessage() } }
    fun back() = viewModel.navigateBack()
    BackHandler(destination != AppTab.CHAT) { back() }
    Scaffold(
        // A single IME owner. Screens consume these insets and never add another keyboard spacer.
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = {
                Text(if (destination == AppTab.CHAT) "Better Intelligence" else destination.title,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }, navigationIcon = {
                IconButton(onClick = { if (destination == AppTab.CHAT) viewModel.selectTab(AppTab.HISTORY) else back() },
                    modifier = Modifier.testTag(if (destination == AppTab.CHAT) "open_history" else "navigate_back")) {
                    Icon(if (destination == AppTab.CHAT) Icons.Default.History else Icons.AutoMirrored.Filled.ArrowBack,
                        if (destination == AppTab.CHAT) "Conversation history" else "Back")
                }
            }, actions = {
                if (destination in setOf(AppTab.CHAT, AppTab.HISTORY)) {
                    IconButton(onClick = { viewModel.newConversation() }, modifier = Modifier.testTag("new_chat")) { Icon(Icons.Default.Add, "New conversation") }
                }
                if (destination == AppTab.CHAT) IconButton(onClick = { viewModel.selectTab(AppTab.SETTINGS) }, modifier = Modifier.testTag("open_settings")) {
                    Icon(Icons.Default.Settings, "Settings")
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                // A content-sized notice must not cover Send/Stop or the IME.
                SnackbarHost(snackbar)
                Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(destination, viewModel) }
            }
        }
    }
}

@Composable
fun TabContent(tab: AppTab, viewModel: AiCoreViewModel) {
    when (tab) {
        AppTab.CHAT -> ChatScreen(viewModel)
        AppTab.HISTORY -> HistoryScreen(viewModel)
        AppTab.SETTINGS -> SettingsScreen(viewModel)
        AppTab.MODELS -> ModelsScreen(viewModel)
        AppTab.CONNECT -> ConnectScreen(viewModel)
        AppTab.WEB -> WebSettingsScreen(viewModel)
        AppTab.APPEARANCE -> AppearanceScreen(viewModel)
        AppTab.PRIVACY -> PrivacyScreen(viewModel)
        AppTab.MEMORY -> MemoryScreen(viewModel)
        AppTab.ADVANCED -> AdvancedSettingsScreen(viewModel)
    }
}
