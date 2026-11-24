package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.service.ModelDownloadQueue
import kotlinx.coroutines.launch
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import com.example.ui.components.BiAvatar
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AiCoreViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloadQueue.ensureScheduled(applicationContext)
                ModelDownloadQueue.runWhileVisible(applicationContext)
            }
        }
        setContent {
            val appearance by viewModel.appearance.collectAsState()
            LaunchedEffect(appearance) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = appearance == "LIGHT"
                    isAppearanceLightNavigationBars = appearance == "LIGHT"
                }
            }
            MyApplicationTheme(darkTheme = appearance != "LIGHT") { MainAppContent(viewModel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(viewModel: AiCoreViewModel) {
    val destination by viewModel.currentTab.collectAsState()
    val message by viewModel.userMessage.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val roots = setOf(AppTab.MODELS, AppTab.CONNECT, AppTab.SETTINGS)
    val selectedRoot = when (destination) {
        AppTab.MODELS, AppTab.OPTIMISE -> AppTab.MODELS
        AppTab.CONNECT -> AppTab.CONNECT
        else -> AppTab.SETTINGS
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.clearUserMessage() } }
    BackHandler(destination != AppTab.MODELS) { viewModel.navigateBack() }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text(destination.title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (destination in roots) BiAvatar(false)
                    else IconButton(onClick = viewModel::navigateBack, modifier = Modifier.testTag("navigate_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                listOf(Triple(AppTab.MODELS, "Models", Icons.Default.Memory),
                    Triple(AppTab.CONNECT, "Apps", Icons.Default.Apps),
                    Triple(AppTab.SETTINGS, "Settings", Icons.Default.Settings)).forEach { (tab, label, icon) ->
                    NavigationBarItem(selected = selectedRoot == tab, onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(icon, null) }, label = { Text(label) }, modifier = Modifier.testTag("nav_${tab.name}"))
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) { TabContent(destination, viewModel) }
    }
}

@Composable
fun TabContent(tab: AppTab, viewModel: AiCoreViewModel) {
    when (tab) {
        AppTab.MODELS -> ModelsScreen(viewModel)
        AppTab.CONNECT -> ConnectScreen(viewModel)
        AppTab.SETTINGS -> SettingsScreen(viewModel)
        AppTab.OPTIMISE -> AutomaticSetupScreen(viewModel)
        AppTab.APPEARANCE -> AppearanceScreen(viewModel)
        AppTab.PRIVACY -> PrivacyScreen(viewModel)
        AppTab.ADVANCED -> AdvancedSettingsScreen(viewModel)
    }
}
