package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.engine.HuggingFaceGgufFile
import com.example.engine.HuggingFaceModelSearchResult
import com.example.ui.components.HuggingFaceDiscoveryCard
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Deterministic UI fixture: all callbacks stay local, no live file is downloaded. */
class ModelDiscoveryCardUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun cardsKeepDownloadsAvailableUnderRamPressureAndShowVersionDetailsAtLargeFont() {
        var query by mutableStateOf("")
        var selected by mutableStateOf<String?>(null)
        var searched = ""
        var downloaded = ""
        val repo = HuggingFaceModelSearchResult("demo/Qwen-GGUF", "demo", "Qwen", 12500, 50, "", "text-generation", 1)
        val file = HuggingFaceGgufFile("Qwen-example-Q4_K_M.gguf", "https://publisher.example/model.gguf", 1048576000,
            1000, "a".repeat(64), "Q4_K_M", 1800, 0f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MyApplicationTheme(darkTheme = false) { Surface { Column(Modifier.padding(16.dp)) {
                    HuggingFaceDiscoveryCard(query, listOf(repo), false, selected, listOf(file), false,
                        { query = it }, { searched = it }, { selected = it }, { selected = null }, { id, value -> downloaded = "$id/${value.filename}" },
                        initiallyExpanded = true, availableRamMb = 512)
                } } }
            }
        }
        compose.onNodeWithTag("hf_search_input").performTextReplacement("Qwen")
        compose.onNodeWithTag("hf_search_submit_btn").performClick()
        assertEquals("Qwen", searched)
        compose.onNodeWithTag("hf_model_${repo.repoId}").assertIsDisplayed()
        compose.onNodeWithTag("hf_download_${repo.repoId}").performClick()
        compose.onNodeWithText("Choose a version").assertIsDisplayed()
        compose.onNodeWithText("More free RAM may be needed to run this version.").assertIsDisplayed()
        compose.onNodeWithTag("hf_file_download_${file.filename}").assertIsEnabled().performClick()
        assertEquals("${repo.repoId}/${file.filename}", downloaded)
        assertNull(selected)
    }
}
